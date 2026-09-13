package io.github.zoyluo.aibot.external.realclient;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotMod;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authenticated loopback control transport for one real Minecraft client body.
 *
 * <p>Networking threads only update immutable snapshots and bounded queues. They never access the
 * Minecraft server/world. Minecraft access remains inside RealClientBodyBackend on the server
 * thread.</p>
 */
public final class RealClientServerTransport implements AutoCloseable {
    public static final long HEARTBEAT_STALE_MS=3500L;
    private static final int OUTBOUND_CAPACITY=32;
    private static final int EXECUTION_CAPACITY=1024;

    /** One coherent client sample: pose, look and crosshair belong to the same frame. */
    public record SensorSnapshot(
            String playerUuid,double x,double y,double z,float yaw,float pitch,int selectedSlot,
            boolean crosshairPresent,int crosshairX,int crosshairY,int crosshairZ,
            String crosshairBlock,String crosshairSide,
            String gameSession,long frameSeq,long receivedAtMs) {}

    public record RemoteExecution(
            String executionId,String state,double progress,String reason,long receivedAtMs) {}

    public record SessionSnapshot(
            String bodyId,String playerName,String sessionEpoch,boolean connected,
            long lastHeartbeatMs,String gameSession,SensorSnapshot sensor) {
        public boolean fresh(long nowMs) {
            return connected && nowMs-lastHeartbeatMs<=HEARTBEAT_STALE_MS;
        }
    }

    private final String expectedBodyId;
    private final String expectedPlayerName;
    private final byte[] token;
    private final int port;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicReference<Session> active=new AtomicReference<>();
    private ServerSocket listener;
    private Thread acceptThread;

    public RealClientServerTransport(
            String expectedBodyId,String expectedPlayerName,String token,int port) {
        this.expectedBodyId=Objects.requireNonNull(expectedBodyId);
        this.expectedPlayerName=Objects.requireNonNull(expectedPlayerName);
        if(token==null || token.length()<32 || token.length()>256
                || !token.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException(
                    "AIBOT_REAL_CLIENT_TOKEN_must_be_32_to_256_URL_safe_characters");
        if(port<1024 || port>65535)throw new IllegalArgumentException("invalid_real_client_port");
        this.token=token.getBytes(StandardCharsets.UTF_8);
        this.port=port;
    }

    public synchronized void start()throws IOException {
        if(listener!=null)throw new IllegalStateException("real_client_transport_already_started");
        listener=new ServerSocket(port,4,InetAddress.getLoopbackAddress());
        acceptThread=new Thread(this::acceptLoop,"aibot-real-client-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        AIBotMod.LOGGER.info(
                "AIBot real-client transport bound to loopback port {} body_id={} player={}",
                port,expectedBodyId,expectedPlayerName);
    }

    public Optional<SessionSnapshot> session() {
        Session session=active.get();
        return session==null?Optional.empty():Optional.of(session.snapshot());
    }

    /** Loopback port this transport is bound to (for diagnostics and tests). */
    public int port() { return port; }

    public Optional<RemoteExecution> execution(String executionId) {
        Session session=active.get();
        if(session==null)return Optional.empty();
        return Optional.ofNullable(session.executions.get(executionId));
    }

    public boolean sendCommand(String executionId,String operation,String argumentsJson) {
        Session session=active.get();
        if(session==null || !session.connected.get())return false;
        JsonObject command=new JsonObject();
        command.addProperty("type","command");
        command.addProperty("protocol",RealClientWire.PROTOCOL_VERSION);
        command.addProperty("session_epoch",session.sessionEpoch);
        command.addProperty("execution_id",executionId);
        command.addProperty("operation",operation);
        command.addProperty("arguments_json",argumentsJson);
        return session.outbound.offer(command);
    }

    public boolean sendControl(String executionId,String action,String reason) {
        Session session=active.get();
        if(session==null || !session.connected.get())return false;
        JsonObject control=new JsonObject();
        control.addProperty("type","control");
        control.addProperty("protocol",RealClientWire.PROTOCOL_VERSION);
        control.addProperty("session_epoch",session.sessionEpoch);
        control.addProperty("execution_id",executionId==null?"":executionId);
        control.addProperty("action",action);
        control.addProperty("reason",reason==null?"":reason);
        return session.outbound.offer(control);
    }

    private void acceptLoop() {
        while(!closed.get()) {
            try {
                Socket socket=listener.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5000);
                Thread handler=new Thread(
                        ()->handshake(socket),"aibot-real-client-handshake");
                handler.setDaemon(true);
                handler.start();
            } catch(IOException failure) {
                if(!closed.get())
                    AIBotMod.LOGGER.error("real-client accept failed",failure);
            }
        }
    }

    private void handshake(Socket socket) {
        Session replacement=null;
        try {
            DataInputStream input=new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            DataOutputStream output=new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            JsonObject hello=RealClientWire.read(input);
            if(!"hello".equals(RealClientWire.requiredString(hello,"type",32)))
                throw new IOException("real_client_hello_required");
            if(!hello.has("protocol")
                    || hello.get("protocol").getAsInt()!=RealClientWire.PROTOCOL_VERSION)
                throw new IOException("real_client_protocol_mismatch");
            String suppliedToken=RealClientWire.requiredString(hello,"token",256);
            if(!MessageDigest.isEqual(token,suppliedToken.getBytes(StandardCharsets.UTF_8)))
                throw new IOException("real_client_unauthorized");
            String bodyId=RealClientWire.requiredString(hello,"body_id",160);
            String playerName=RealClientWire.requiredString(hello,"player_name",64);
            String sessionEpoch=RealClientWire.requiredString(hello,"session_epoch",160);
            if(!expectedBodyId.equals(bodyId))throw new IOException("real_client_body_id_mismatch");
            if(!expectedPlayerName.equalsIgnoreCase(playerName))
                throw new IOException("real_client_player_name_mismatch");

            replacement=new Session(
                    socket,input,output,bodyId,playerName,sessionEpoch);
            Session prior=active.get();
            if(prior!=null && prior.connected.get())
                throw new IOException("real_client_authority_already_connected");
            if(!active.compareAndSet(prior,replacement))
                throw new IOException("real_client_authority_race_rejected");
            if(prior!=null)prior.close();
            socket.setSoTimeout(0);

            JsonObject welcome=new JsonObject();
            welcome.addProperty("type","welcome");
            welcome.addProperty("protocol",RealClientWire.PROTOCOL_VERSION);
            welcome.addProperty("body_id",expectedBodyId);
            welcome.addProperty("session_epoch",sessionEpoch);
            RealClientWire.write(output,welcome);
            replacement.start();
            AIBotMod.LOGGER.info(
                    "AIBot real client connected body_id={} player={} session={}",
                    bodyId,playerName,sessionEpoch);
        } catch(Exception failure) {
            if(replacement!=null)replacement.close();
            else try { socket.close(); } catch(IOException ignored) {}
            if(!closed.get())
                AIBotMod.LOGGER.warn("real-client handshake rejected: {}",failure.toString());
        }
    }

    @Override public synchronized void close() {
        if(!closed.compareAndSet(false,true))return;
        Session session=active.getAndSet(null);
        if(session!=null)session.close();
        try { if(listener!=null)listener.close(); } catch(IOException ignored) {}
        if(acceptThread!=null)acceptThread.interrupt();
        listener=null;
    }

    private final class Session implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final String bodyId,playerName,sessionEpoch;
        final AtomicBoolean connected=new AtomicBoolean(true);
        final AtomicReference<SensorSnapshot> sensor=new AtomicReference<>();
        final ConcurrentHashMap<String,RemoteExecution> executions=new ConcurrentHashMap<>();
        final ArrayBlockingQueue<JsonObject> outbound=new ArrayBlockingQueue<>(OUTBOUND_CAPACITY);
        volatile long lastHeartbeatMs=System.currentTimeMillis();
        // Minecraft 游戏连接 incarnation(与控制 TCP epoch 分离):-1=客户端尚未 JOIN。
        // 同连接内只允许单调前进;回退或 epoch 漂移一律断连,迟到旧 incarnation 不得污染新会话。
        volatile int gameSessionSeq=-1;
        volatile String gameSession="";
        volatile long lastFrameSeq=-1;
        Thread reader,writer;

        Session(Socket socket,DataInputStream input,DataOutputStream output,
                String bodyId,String playerName,String sessionEpoch) {
            this.socket=socket;this.input=input;this.output=output;
            this.bodyId=bodyId;this.playerName=playerName;this.sessionEpoch=sessionEpoch;
        }

        void start() {
            reader=new Thread(this::readLoop,"aibot-real-client-reader");
            writer=new Thread(this::writeLoop,"aibot-real-client-writer");
            reader.setDaemon(true);writer.setDaemon(true);
            reader.start();writer.start();
        }

        SessionSnapshot snapshot() {
            return new SessionSnapshot(
                    bodyId,playerName,sessionEpoch,connected.get(),lastHeartbeatMs,
                    gameSession,sensor.get());
        }

        void readLoop() {
            try {
                while(connected.get() && !closed.get()) {
                    JsonObject message=RealClientWire.read(input);
                    String type=RealClientWire.requiredString(message,"type",32);
                    String epoch=RealClientWire.requiredString(message,"session_epoch",160);
                    if(!sessionEpoch.equals(epoch))
                        throw new IOException("real_client_session_epoch_mismatch");
                    switch(type) {
                        case "heartbeat" -> receiveHeartbeat(message);
                        case "execution" -> receiveExecution(message);
                        default -> throw new IOException("real_client_message_type_unsupported:"+type);
                    }
                }
            } catch(EOFException ignored) {
                // Normal disconnect path.
            } catch(Exception failure) {
                if(!closed.get())
                    AIBotMod.LOGGER.warn("real-client session read ended: {}",failure.toString());
            } finally {
                close();
            }
        }

        /** 校验消息携带的游戏 incarnation:首条固定,前进重置,回退/漂移断连 fail closed。 */
        private void bindGameIncarnation(String messageGameSession,int messageGameSessionSeq)
                throws IOException {
            if(messageGameSessionSeq<0)
                throw new IOException("real_client_game_session_seq_invalid");
            if(gameSessionSeq<0) {
                gameSessionSeq=messageGameSessionSeq;
                gameSession=messageGameSession;
                return;
            }
            if(messageGameSessionSeq==gameSessionSeq) {
                if(!gameSession.equals(messageGameSession))
                    throw new IOException("real_client_game_session_epoch_mismatch");
                return;
            }
            if(messageGameSessionSeq<gameSessionSeq)
                throw new IOException("real_client_game_session_stale_incarnation");
            gameSessionSeq=messageGameSessionSeq;
            gameSession=messageGameSession;
            lastFrameSeq=-1;
            sensor.set(null);
            executions.clear();
        }

        void receiveHeartbeat(JsonObject message)throws IOException {
            if(!message.has("game_session_seq") || !message.has("frame_seq"))
                throw new IOException("real_client_heartbeat_missing_game_incarnation");
            long now=System.currentTimeMillis();
            String playerUuid=RealClientWire.requiredString(message,"player_uuid",64);
            String messageGameSession=RealClientWire.requiredString(message,"game_session",160);
            int messageGameSessionSeq=message.get("game_session_seq").getAsInt();
            long frameSeq=message.get("frame_seq").getAsLong();
            bindGameIncarnation(messageGameSession,messageGameSessionSeq);
            lastHeartbeatMs=now;
            if(frameSeq<0 || frameSeq<=lastFrameSeq)
                return; // duplicate/out-of-order frame: never refresh the sensor snapshot
            double x=message.get("x").getAsDouble();
            double y=message.get("y").getAsDouble();
            double z=message.get("z").getAsDouble();
            float yaw=message.get("yaw").getAsFloat();
            float pitch=message.get("pitch").getAsFloat();
            int selected=message.get("selected_slot").getAsInt();
            if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                    || selected<0 || selected>8)
                throw new IOException("real_client_heartbeat_numeric_invalid");
            boolean present=message.has("crosshair_present")
                    && message.get("crosshair_present").getAsBoolean();
            int cx=0,cy=0,cz=0;String block="",side="";
            if(present) {
                cx=message.get("crosshair_x").getAsInt();
                cy=message.get("crosshair_y").getAsInt();
                cz=message.get("crosshair_z").getAsInt();
                block=RealClientWire.requiredString(message,"crosshair_block",128);
                side=RealClientWire.requiredString(message,"crosshair_side",32);
            }
            lastFrameSeq=frameSeq;
            sensor.set(new SensorSnapshot(
                    playerUuid,x,y,z,yaw,pitch,selected,present,cx,cy,cz,block,side,
                    messageGameSession,frameSeq,now));
        }

        void receiveExecution(JsonObject message)throws IOException {
            if(!message.has("game_session_seq"))
                throw new IOException("real_client_execution_missing_game_incarnation");
            String messageGameSession=RealClientWire.requiredString(message,"game_session",160);
            int messageGameSessionSeq=message.get("game_session_seq").getAsInt();
            bindGameIncarnation(messageGameSession,messageGameSessionSeq);
            String executionId=RealClientWire.requiredString(message,"execution_id",160);
            String state=RealClientWire.requiredString(message,"state",32);
            if(!java.util.Set.of(
                    "running","paused","completed","failed","cancelled","outcome_unknown")
                    .contains(state))
                throw new IOException("real_client_execution_state_invalid");
            double progress=message.has("progress")?message.get("progress").getAsDouble():0D;
            if(!Double.isFinite(progress))
                throw new IOException("real_client_execution_progress_invalid");
            String reason=RealClientWire.optionalString(message,"reason","",2048);
            if(!executions.containsKey(executionId)
                    && executions.size()>=EXECUTION_CAPACITY)
                throw new IOException("real_client_execution_map_capacity_exhausted");
            executions.put(executionId,new RemoteExecution(
                    executionId,state,Math.max(0D,Math.min(1D,progress)),reason,
                    System.currentTimeMillis()));
        }

        void writeLoop() {
            try {
                while(connected.get() && !closed.get()) {
                    JsonObject message=outbound.poll(1,TimeUnit.SECONDS);
                    if(message!=null)RealClientWire.write(output,message);
                }
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch(Exception failure) {
                if(!closed.get())
                    AIBotMod.LOGGER.warn("real-client session write ended: {}",failure.toString());
            } finally {
                close();
            }
        }

        @Override public void close() {
            if(!connected.compareAndSet(true,false))return;
            try { socket.close(); } catch(IOException ignored) {}
            if(reader!=null && reader!=Thread.currentThread())reader.interrupt();
            if(writer!=null && writer!=Thread.currentThread())writer.interrupt();
            active.compareAndSet(this,null);
            AIBotMod.LOGGER.info(
                    "AIBot real client disconnected body_id={} player={} session={}",
                    bodyId,playerName,sessionEpoch);
        }
    }
}
