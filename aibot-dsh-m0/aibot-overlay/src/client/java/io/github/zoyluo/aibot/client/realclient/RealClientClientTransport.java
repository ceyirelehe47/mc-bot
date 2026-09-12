package io.github.zoyluo.aibot.client.realclient;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotMod;
import io.github.zoyluo.aibot.external.realclient.RealClientWire;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Reconnecting loopback client. Minecraft state is never touched from this networking thread. */
final class RealClientClientTransport implements AutoCloseable {
    private final InetAddress host;
    private final int port;
    private final String token,bodyId,playerName;
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicReference<Connection> connection=new AtomicReference<>();
    private final ArrayBlockingQueue<JsonObject> inbound=new ArrayBlockingQueue<>(64);
    private final Thread connector;

    RealClientClientTransport(
            String host,int port,String token,String bodyId,String playerName) {
        try {
            this.host=InetAddress.getByName(host);
        } catch(UnknownHostException failure) {
            throw new IllegalArgumentException("real_client_control_host_invalid",failure);
        }
        if(!this.host.isLoopbackAddress())
            throw new IllegalArgumentException("real_client_control_host_must_be_loopback");
        if(port<1024 || port>65535)
            throw new IllegalArgumentException("real_client_control_port_invalid");
        if(token==null || token.length()<32 || token.length()>256
                || !token.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("real_client_control_token_invalid");
        this.port=port;this.token=token;
        this.bodyId=bodyId;this.playerName=playerName;
        connector=new Thread(this::connectLoop,"aibot-real-client-connector");
        connector.setDaemon(true);
    }

    void start() {
        connector.start();
    }

    JsonObject poll() {
        return inbound.poll();
    }

    String sessionEpoch() {
        Connection current=connection.get();
        return current==null?"":current.sessionEpoch;
    }

    boolean connected() {
        Connection current=connection.get();
        return current!=null && current.connected.get();
    }

    boolean send(JsonObject message) {
        Connection current=connection.get();
        if(current==null || !current.connected.get())return false;
        message.addProperty("session_epoch",current.sessionEpoch);
        return current.outbound.offer(message);
    }

    private void connectLoop() {
        int failures=0;
        while(!closed.get()) {
            try {
                Socket socket=new Socket();
                socket.connect(new InetSocketAddress(host,port),3000);
                socket.setTcpNoDelay(true);
                DataInputStream input=new DataInputStream(
                        new BufferedInputStream(socket.getInputStream()));
                DataOutputStream output=new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream()));
                String epoch=UUID.randomUUID().toString();
                JsonObject hello=new JsonObject();
                hello.addProperty("type","hello");
                hello.addProperty("protocol",RealClientWire.PROTOCOL_VERSION);
                hello.addProperty("token",token);
                hello.addProperty("body_id",bodyId);
                hello.addProperty("player_name",playerName);
                hello.addProperty("session_epoch",epoch);
                RealClientWire.write(output,hello);
                JsonObject welcome=RealClientWire.read(input);
                if(!"welcome".equals(RealClientWire.requiredString(welcome,"type",32)))
                    throw new IOException("real_client_welcome_missing");
                if(!welcome.has("protocol")
                        || welcome.get("protocol").getAsInt()!=RealClientWire.PROTOCOL_VERSION)
                    throw new IOException("real_client_welcome_protocol_mismatch");
                if(!bodyId.equals(RealClientWire.requiredString(welcome,"body_id",160))
                        || !epoch.equals(RealClientWire.requiredString(
                                welcome,"session_epoch",160)))
                    throw new IOException("real_client_welcome_binding_mismatch");
                Connection connected=new Connection(socket,input,output,epoch);
                Connection old=connection.getAndSet(connected);
                if(old!=null)old.close();
                failures=0;
                AIBotMod.LOGGER.info(
                        "AIBot real-client control connected body_id={} session={}",
                        bodyId,epoch);
                connected.startWriter();
                connected.readLoop();
            } catch(Exception failure) {
                if(!closed.get())
                    AIBotMod.LOGGER.warn(
                            "real-client control connect/read failed: {}",failure.toString());
            } finally {
                Connection old=connection.getAndSet(null);
                if(old!=null)old.close();
            }
            if(closed.get())break;
            failures++;
            try {
                Thread.sleep(Math.min(10000L,500L*(1L<<Math.min(failures,4))));
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override public void close() {
        if(!closed.compareAndSet(false,true))return;
        Connection current=connection.getAndSet(null);
        if(current!=null)current.close();
        connector.interrupt();
        inbound.clear();
    }

    private final class Connection implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final String sessionEpoch;
        final AtomicBoolean connected=new AtomicBoolean(true);
        final ArrayBlockingQueue<JsonObject> outbound=new ArrayBlockingQueue<>(64);
        Thread writer;

        Connection(Socket socket,DataInputStream input,DataOutputStream output,String sessionEpoch) {
            this.socket=socket;this.input=input;this.output=output;this.sessionEpoch=sessionEpoch;
        }

        void startWriter() {
            writer=new Thread(this::writeLoop,"aibot-real-client-control-writer");
            writer.setDaemon(true);
            writer.start();
        }

        void readLoop()throws IOException {
            try {
                while(connected.get() && !closed.get()) {
                    JsonObject message=RealClientWire.read(input);
                    String epoch=RealClientWire.requiredString(
                            message,"session_epoch",160);
                    if(!sessionEpoch.equals(epoch))
                        throw new IOException("real_client_server_epoch_mismatch");
                    if(!inbound.offer(message))
                        throw new IOException("real_client_inbound_queue_overflow");
                }
            } catch(EOFException ignored) {
                // reconnect loop handles it
            }
        }

        void writeLoop() {
            try {
                while(connected.get() && !closed.get()) {
                    JsonObject message=outbound.poll(1,TimeUnit.SECONDS);
                    if(message!=null)RealClientWire.write(output,message);
                }
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch(IOException failure) {
                if(!closed.get())
                    AIBotMod.LOGGER.warn(
                            "real-client control write failed: {}",failure.toString());
            } finally {
                close();
            }
        }

        @Override public void close() {
            if(!connected.compareAndSet(true,false))return;
            try { socket.close(); } catch(IOException ignored) {}
            if(writer!=null && writer!=Thread.currentThread())writer.interrupt();
            outbound.clear();
        }
    }
}
