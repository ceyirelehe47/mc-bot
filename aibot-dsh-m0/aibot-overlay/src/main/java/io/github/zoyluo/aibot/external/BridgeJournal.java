package io.github.zoyluo.aibot.external;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

/** Single-writer, fsynced, bounded journal. No Java object deserialization. */
public final class BridgeJournal implements AutoCloseable {
    private static final byte[] MAGIC = "AIBODY01".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 44, MAX_FRAME = 131072;
    public static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;
    private final FileChannel channel;
    private final FileLock lock;
    private final LongSupplier clock;
    private final long maxBytes;
    private final List<Frame> frames = new ArrayList<>();
    private long sequence;
    private boolean failed;
    public final String epoch;

    public record Frame(long sequence, long time, Map<String,String> fields) {
        public Frame { fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields)); }
        public Map<String,Object> wire() {
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("sequence", sequence); out.put("observed_at_ms", time);
            out.put("kind", fields.getOrDefault("kind", "unknown"));
            out.put("execution_id", fields.getOrDefault("execution_id", ""));
            out.put("state", fields.getOrDefault("state", ""));
            out.put("reason", fields.getOrDefault("reason", ""));
            String payload = fields.get("payload");
            if (payload != null) out.put("payload", payload); // opaque text, never promoted to instructions
            return out;
        }
    }

    public BridgeJournal(Path path, LongSupplier clock) throws IOException {
        this(path, clock, DEFAULT_MAX_BYTES);
    }
    public BridgeJournal(Path path, LongSupplier clock, long maxBytes) throws IOException {
        this.clock = clock; this.maxBytes = maxBytes;
        Files.createDirectories(path.toAbsolutePath().getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("journal_already_locked");
            if (channel.size() > maxBytes) throw new IOException("journal_size_limit");
            if (channel.size() == 0) {
                String id = UUID.randomUUID().toString();
                writeFully(ByteBuffer.wrap(MAGIC));
                writeFully(ByteBuffer.wrap(id.getBytes(StandardCharsets.US_ASCII)));
                channel.force(true);
                epoch = id;
            } else {
                if (channel.size() < HEADER_SIZE) throw new IOException("journal_invalid_header");
                ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
                readFully(header); header.flip();
                byte[] magic = new byte[8], id = new byte[36];
                header.get(magic); header.get(id);
                if (!Arrays.equals(magic,MAGIC)) throw new IOException("journal_invalid_magic");
                epoch = UUID.fromString(new String(id,StandardCharsets.US_ASCII)).toString();
                recover();
            }
            channel.position(channel.size());
            lock = acquired;
        } catch (IOException | RuntimeException e) {
            if (acquired != null) acquired.release();
            channel.close();
            throw e;
        }
    }

    private void recover() throws IOException {
        long lastGood = HEADER_SIZE;
        while (channel.position() < channel.size()) {
            if (channel.size() - channel.position() < 4) { truncateTail(lastGood); break; }
            ByteBuffer sizeBuf = ByteBuffer.allocate(4); readFully(sizeBuf); sizeBuf.flip();
            int size = sizeBuf.getInt();
            if (size < 16 || size > MAX_FRAME) throw new IOException("journal_invalid_frame_length");
            if (channel.size() - channel.position() < size + 4L) { truncateTail(lastGood); break; }
            ByteBuffer body = ByteBuffer.allocate(size); readFully(body);
            ByteBuffer crcBuf = ByteBuffer.allocate(4); readFully(crcBuf); crcBuf.flip();
            CRC32 crc = new CRC32(); crc.update(body.array());
            if ((int)crc.getValue() != crcBuf.getInt()) throw new IOException("journal_checksum_mismatch");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body.array()))) {
                long seq = in.readLong(), time = in.readLong();
                if (seq != sequence+1) throw new IOException("journal_sequence_gap");
                int count = in.readInt();
                if (count<1 || count>32) throw new IOException("journal_invalid_field_count");
                Map<String,String> fields = new LinkedHashMap<>();
                for(int i=0;i<count;i++) {
                    String k=readString(in), v=readString(in);
                    if(fields.put(k,v)!=null) throw new IOException("journal_duplicate_key");
                }
                if(in.available()!=0) throw new IOException("journal_trailing_bytes");
                frames.add(new Frame(seq,time,fields)); sequence=seq;
            }
            lastGood=channel.position();
        }
    }
    private static String readString(DataInputStream in) throws IOException {
        int len=in.readInt();
        if(len<0 || len>65536 || len>in.available()) throw new IOException("journal_invalid_string");
        return new String(in.readNBytes(len),StandardCharsets.UTF_8);
    }
    private static void writeString(DataOutputStream out,String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>65536) throw new IOException("journal_string_too_large");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private void truncateTail(long pos) throws IOException { channel.truncate(pos); channel.force(true); channel.position(pos); }
    private void readFully(ByteBuffer buf) throws IOException {
        while(buf.hasRemaining()) if(channel.read(buf)<0) throw new EOFException();
    }
    private void writeFully(ByteBuffer buf) throws IOException { while(buf.hasRemaining()) channel.write(buf); }

    public synchronized Frame append(Map<String,String> fields) {
        if(failed) throw new BridgeFault(503,"journal_failed_closed");
        if(fields.isEmpty() || fields.size()>32) throw new IllegalArgumentException("invalid_journal_fields");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            long now=clock.getAsLong();
            try(DataOutputStream out=new DataOutputStream(bytes)) {
                out.writeLong(sequence+1); out.writeLong(now); out.writeInt(fields.size());
                for(var e:fields.entrySet()) { writeString(out,e.getKey()); writeString(out,e.getValue()); }
            }
            byte[] body=bytes.toByteArray();
            if(body.length>MAX_FRAME || channel.size()+body.length+8>maxBytes) {
                failed=true; throw new BridgeFault(503,"journal_capacity_exhausted");
            }
            CRC32 crc=new CRC32(); crc.update(body);
            ByteBuffer frame=ByteBuffer.allocate(body.length+8).putInt(body.length).put(body).putInt((int)crc.getValue());
            frame.flip(); writeFully(frame); channel.force(true);
            Frame result=new Frame(++sequence,now,fields); frames.add(result);
            notifyAll(); return result;
        } catch(IOException e) { failed=true; throw new BridgeFault(503,"journal_io_failure"); }
    }
    public synchronized List<Frame> replay() { return List.copyOf(frames); }
    public synchronized long lastSequence() { return sequence; }
    public synchronized Map<String,Object> readAfter(String requestedEpoch,long after,int limit) {
        if(after<0 || limit<1 || limit>256) throw new BridgeFault(400,"invalid_event_cursor");
        long first=Math.max(1,sequence-4095);
        boolean gap=!epoch.equals(requestedEpoch) || after<first-1 || after>sequence;
        List<Map<String,Object>> events=new ArrayList<>();
        if(!gap) for(Frame f:frames) if(f.sequence()>after && events.size()<limit) events.add(f.wire());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("epoch",epoch); result.put("gap",gap); result.put("first_sequence",first);
        result.put("last_sequence",sequence); result.put("events",events);
        result.put("next_sequence",gap?sequence:events.isEmpty()?after:events.getLast().get("sequence"));
        return result;
    }
    public synchronized Map<String,Object> waitAfter(String requestedEpoch,long after,int waitMs) throws InterruptedException {
        if(waitMs<0 || waitMs>20000) throw new BridgeFault(400,"invalid_wait");
        long deadline=System.nanoTime()+waitMs*1_000_000L;
        while(!failed && epoch.equals(requestedEpoch) && after==sequence && waitMs>0) {
            long remaining=deadline-System.nanoTime(); if(remaining<=0) break;
            wait(Math.max(1,remaining/1_000_000L));
        }
        return readAfter(requestedEpoch,after,128);
    }
    @Override public synchronized void close() throws IOException {
        failed=true; notifyAll();
        if(channel.isOpen()) { lock.release(); channel.close(); }
    }
}
