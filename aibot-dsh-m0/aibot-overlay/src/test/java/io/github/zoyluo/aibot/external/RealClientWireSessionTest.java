package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.external.realclient.RealClientServerTransport;
import io.github.zoyluo.aibot.external.realclient.RealClientWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire-level session contract of the loopback control transport: protocol v4 fail-closed
 * behaviour, game-session incarnation monotonicity and sensor frame deduplication.
 * Pure sockets, no Minecraft classes involved.
 */
final class RealClientWireSessionTest {
    private static final String TOKEN="unit-test-real-client-token-0123456789abcdef";
    private static final String BODY="bob";
    private static final String PLAYER="Bob";

    private RealClientServerTransport transport;

    @AfterEach void closeTransport() {
        if(transport!=null)transport.close();
    }

    private int freePort()throws Exception {
        try(ServerSocket probe=new ServerSocket(0)) { return probe.getLocalPort(); }
    }

    private RealClientServerTransport startTransport()throws Exception {
        transport=new RealClientServerTransport(BODY,PLAYER,TOKEN,freePort());
        transport.start();
        return transport;
    }

    private static final class Client implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final String epoch=UUID.randomUUID().toString();
        Client(int port)throws Exception {
            socket=new Socket("127.0.0.1",port);
            socket.setTcpNoDelay(true);
            input=new DataInputStream(socket.getInputStream());
            output=new DataOutputStream(socket.getOutputStream());
        }
        void hello(int protocol)throws Exception {
            com.google.gson.JsonObject message=new com.google.gson.JsonObject();
            message.addProperty("type","hello");
            message.addProperty("protocol",protocol);
            message.addProperty("token",TOKEN);
            message.addProperty("body_id",BODY);
            message.addProperty("player_name",PLAYER);
            message.addProperty("session_epoch",epoch);
            message.addProperty("window_mode","background");
            RealClientWire.write(output,message);
        }
        com.google.gson.JsonObject welcome()throws Exception {
            return RealClientWire.read(input);
        }
        void heartbeat(String gameSession,int gameSessionSeq,long frameSeq,
                double x)throws Exception {
            com.google.gson.JsonObject message=new com.google.gson.JsonObject();
            message.addProperty("type","heartbeat");
            message.addProperty("session_epoch",epoch);
            message.addProperty("game_session",gameSession);
            message.addProperty("game_session_seq",gameSessionSeq);
            message.addProperty("frame_seq",frameSeq);
            message.addProperty("player_uuid","00000000-0000-0000-0000-000000000001");
            message.addProperty("x",x);message.addProperty("y",64D);message.addProperty("z",0D);
            message.addProperty("yaw",0F);message.addProperty("pitch",0F);
            message.addProperty("selected_slot",0);
            message.addProperty("crosshair_present",false);
            RealClientWire.write(output,message);
        }
        void execution(String gameSession,int gameSessionSeq,
                String executionId,String state)throws Exception {
            com.google.gson.JsonObject message=new com.google.gson.JsonObject();
            message.addProperty("type","execution");
            message.addProperty("session_epoch",epoch);
            message.addProperty("game_session",gameSession);
            message.addProperty("game_session_seq",gameSessionSeq);
            message.addProperty("execution_id",executionId);
            message.addProperty("state",state);
            message.addProperty("progress",0D);
            RealClientWire.write(output,message);
        }
        @Override public void close()throws Exception { socket.close(); }
    }

    private static void awaitClosed(Client client)throws Exception {
        long deadline=System.currentTimeMillis()+5000L;
        while(System.currentTimeMillis()<deadline) {
            try {
                if(client.input.read()==-1)return;
            } catch(EOFException closed) { return; }
            Thread.sleep(20L);
        }
        fail("connection was expected to be closed by the server");
    }

    private static <T> T await(long timeoutMs,java.util.function.Supplier<T> probe)
            throws Exception {
        long deadline=System.currentTimeMillis()+timeoutMs;
        T last;
        do {
            last=probe.get();
            if(last!=null)return last;
            Thread.sleep(20L);
        } while(System.currentTimeMillis()<deadline);
        return last;
    }

    @Test void protocolV1HelloIsRejectedFailClosed()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(1);
            awaitClosed(client);
        }
    }

    @Test void protocolV2HelloIsRejectedFailClosed()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(2);
            awaitClosed(client);
        }
    }

    @Test void protocolV3HelloIsRejectedFailClosed()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(3);
            awaitClosed(client);
        }
    }

    @Test void screenWithoutGameSessionIsRejectedFailClosed()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(RealClientWire.PROTOCOL_VERSION);
            client.welcome();
            com.google.gson.JsonObject screen=new com.google.gson.JsonObject();
            screen.addProperty("type","screen");
            screen.addProperty("session_epoch",client.epoch);
            screen.addProperty("screen_seq",0L);
            screen.addProperty("present",false);
            RealClientWire.write(client.output,screen);
            awaitClosed(client);
        }
    }

    @Test void heartbeatWithoutGameSessionIsRejectedFailClosed()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(RealClientWire.PROTOCOL_VERSION);
            com.google.gson.JsonObject welcome=client.welcome();
            assertEquals("welcome",welcome.get("type").getAsString());
            com.google.gson.JsonObject heartbeat=new com.google.gson.JsonObject();
            heartbeat.addProperty("type","heartbeat");
            heartbeat.addProperty("session_epoch",client.epoch);
            heartbeat.addProperty("frame_seq",0L);
            heartbeat.addProperty("player_uuid","00000000-0000-0000-0000-000000000001");
            heartbeat.addProperty("x",0D);heartbeat.addProperty("y",64D);heartbeat.addProperty("z",0D);
            heartbeat.addProperty("yaw",0F);heartbeat.addProperty("pitch",0F);
            heartbeat.addProperty("selected_slot",0);
            RealClientWire.write(client.output,heartbeat);
            awaitClosed(client);
        }
    }

    @Test void validHeartbeatBindsGameSessionAndSensor()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(RealClientWire.PROTOCOL_VERSION);
            client.welcome();
            client.heartbeat("game-a",0,0L,10D);
            var sensor=await(5000L,()->transport.session()
                    .map(RealClientServerTransport.SessionSnapshot::sensor).orElse(null));
            assertNotNull(sensor,"heartbeat never produced a sensor snapshot");
            assertEquals("game-a",sensor.gameSession());
            assertEquals(0L,sensor.frameSeq());
            assertEquals(10D,sensor.x());
            assertEquals("game-a",transport.session().orElseThrow().gameSession());
        }
    }

    @Test void duplicateAndOutOfOrderFramesNeverRefreshTheSensor()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(RealClientWire.PROTOCOL_VERSION);
            client.welcome();
            client.heartbeat("game-a",0,0L,10D);
            var first=await(5000L,()->transport.session()
                    .map(RealClientServerTransport.SessionSnapshot::sensor).orElse(null));
            assertNotNull(first);
            client.heartbeat("game-a",0,0L,20D); // duplicate frame_seq
            client.heartbeat("game-a",0,0L,30D);
            Thread.sleep(300L);
            var after=transport.session().orElseThrow().sensor();
            assertNotNull(after);
            assertEquals(0L,after.frameSeq(),"duplicate frame refreshed the snapshot");
            assertEquals(10D,after.x(),"duplicate frame changed the snapshot position");
            client.heartbeat("game-a",0,5L,40D); // fresh monotonic frame is accepted
            var advanced=await(5000L,()->{
                var s=transport.session().map(
                        RealClientServerTransport.SessionSnapshot::sensor).orElse(null);
                return s!=null && s.frameSeq()==5L?s:null;
            });
            assertNotNull(advanced,"monotonic frame was dropped");
            assertEquals(40D,advanced.x());
        }
    }

    @Test void gameSessionAdvanceResetsSensorAndExecutions()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(RealClientWire.PROTOCOL_VERSION);
            client.welcome();
            client.heartbeat("game-a",0,0L,10D);
            client.execution("game-a",0,"exec-1","running");
            var sensor=await(5000L,()->transport.session()
                    .map(RealClientServerTransport.SessionSnapshot::sensor).orElse(null));
            assertNotNull(sensor);
            await(5000L,()->transport.execution("exec-1").orElse(null));
            client.heartbeat("game-b",1,0L,11D);
            var next=await(5000L,()->{
                var s=transport.session().orElse(null);
                return s!=null && "game-b".equals(s.gameSession())?s:null;
            });
            assertNotNull(next,"game-session advance was not observed");
            var reset=await(5000L,()->{
                var s=transport.session().map(
                        RealClientServerTransport.SessionSnapshot::sensor).orElse(null);
                return s!=null && s.frameSeq()==0L?s:null;
            });
            assertNotNull(reset,"advanced incarnation frame was dropped");
            assertEquals("game-b",reset.gameSession());
            assertTrue(transport.execution("exec-1").isEmpty(),
                    "old incarnation execution statuses survived the game-session advance");
        }
    }

    @Test void staleGameSessionIncarnationIsRejectedFailClosed()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(RealClientWire.PROTOCOL_VERSION);
            client.welcome();
            client.heartbeat("game-a",0,0L,10D);
            client.heartbeat("game-b",1,0L,10D);
            client.heartbeat("game-a",0,1L,10D); // late frame from a fenced incarnation
            awaitClosed(client);
        }
    }

    @Test void gameSessionEpochDriftAtSameSeqIsRejectedFailClosed()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(RealClientWire.PROTOCOL_VERSION);
            client.welcome();
            client.heartbeat("game-a",0,0L,10D);
            client.heartbeat("game-c",0,1L,10D); // same seq, different epoch
            awaitClosed(client);
        }
    }

    @Test void executionStatusMustCarryTheCurrentIncarnation()throws Exception {
        startTransport();
        try(Client client=new Client(transportLocalPort())) {
            client.hello(RealClientWire.PROTOCOL_VERSION);
            client.welcome();
            client.heartbeat("game-a",0,0L,10D);
            client.heartbeat("game-b",1,0L,11D);
            await(5000L,()->{
                var s=transport.session().orElse(null);
                return s!=null && "game-b".equals(s.gameSession())?s:null;
            });
            client.execution("game-a",0,"exec-stale","failed");
            awaitClosed(client);
        }
    }

    @Test void secondAuthorityWhileConnectedIsRejected()throws Exception {
        startTransport();
        try(Client first=new Client(transportLocalPort())) {
            first.hello(RealClientWire.PROTOCOL_VERSION);
            first.welcome();
            try(Client second=new Client(transportLocalPort())) {
                second.hello(RealClientWire.PROTOCOL_VERSION);
                awaitClosed(second);
            }
            first.heartbeat("game-a",0,0L,10D);
            var sensor=await(5000L,()->transport.session()
                    .map(RealClientServerTransport.SessionSnapshot::sensor).orElse(null));
            assertNotNull(sensor,"first authority lost its session to a duplicate");
        }
    }

    private int transportLocalPort() {
        return transport.port();
    }
}
