package io.github.zoyluo.aibot.external;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.external.realclient.RealClientServerTransport;
import io.github.zoyluo.aibot.external.realclient.RealClientWire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class RealClientCommandIncarnationWireTest {
    private static final String TOKEN=
            "unit-test-real-client-token-0123456789abcdef";
    private RealClientServerTransport transport;

    @AfterEach void close() {
        if(transport!=null)transport.close();
    }

    private int freePort()throws Exception {
        try(ServerSocket socket=new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Client client()throws Exception {
        transport=new RealClientServerTransport(
                "bob","Bob",TOKEN,freePort());
        transport.start();
        return new Client(transport.port());
    }

    private static final class Client implements AutoCloseable {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        final String epoch=UUID.randomUUID().toString();

        Client(int port)throws Exception {
            socket=new Socket("127.0.0.1",port);
            socket.setSoTimeout(5000);
            input=new DataInputStream(socket.getInputStream());
            output=new DataOutputStream(socket.getOutputStream());
            JsonObject hello=new JsonObject();
            hello.addProperty("type","hello");
            hello.addProperty("protocol",RealClientWire.PROTOCOL_VERSION);
            hello.addProperty("token",TOKEN);
            hello.addProperty("body_id","bob");
            hello.addProperty("player_name","Bob");
            hello.addProperty("session_epoch",epoch);
            hello.addProperty("window_mode","background");
            RealClientWire.write(output,hello);
            RealClientWire.read(input);
        }

        void heartbeat(String game,int seq,long frame)throws Exception {
            JsonObject message=new JsonObject();
            message.addProperty("type","heartbeat");
            message.addProperty("session_epoch",epoch);
            message.addProperty("game_session",game);
            message.addProperty("game_session_seq",seq);
            message.addProperty("frame_seq",frame);
            message.addProperty(
                    "player_uuid",
                    "00000000-0000-0000-0000-000000000001");
            message.addProperty("x",0D);message.addProperty("y",64D);
            message.addProperty("z",0D);message.addProperty("yaw",0F);
            message.addProperty("pitch",0F);
            message.addProperty("selected_slot",0);
            message.addProperty("crosshair_present",false);
            RealClientWire.write(output,message);
        }

        @Override public void close()throws Exception {
            socket.close();
        }
    }

    private static void awaitGame(
            RealClientServerTransport transport,String game)throws Exception {
        long deadline=System.currentTimeMillis()+5000L;
        while(System.currentTimeMillis()<deadline) {
            if(transport.session()
                    .map(RealClientServerTransport.SessionSnapshot::gameSession)
                    .filter(game::equals).isPresent())return;
            Thread.sleep(20L);
        }
        fail("game session was not bound");
    }

    @Test void commandsCarryCurrentGameIncarnationAndMonotonicSequence()
            throws Exception {
        try(Client client=client()) {
            assertFalse(transport.sendCommand(
                    "exec-pre","say","{}"),
                    "server must not queue commands before first game heartbeat");
            client.heartbeat("game-a",0,0L);
            awaitGame(transport,"game-a");

            assertTrue(transport.sendCommand(
                    "exec-a","say","{}"));
            JsonObject first=RealClientWire.read(client.input);
            assertEquals("game-a",first.get("game_session").getAsString());
            assertEquals(0,first.get("game_session_seq").getAsInt());
            assertEquals(0L,first.get("command_seq").getAsLong());

            assertTrue(transport.sendControl(
                    "exec-a","pause","test"));
            JsonObject second=RealClientWire.read(client.input);
            assertEquals(1L,second.get("command_seq").getAsLong());

            client.heartbeat("game-b",1,0L);
            awaitGame(transport,"game-b");
            assertTrue(transport.sendCommand(
                    "exec-b","say","{}"));
            JsonObject next=RealClientWire.read(client.input);
            assertEquals("game-b",next.get("game_session").getAsString());
            assertEquals(1,next.get("game_session_seq").getAsInt());
            assertEquals(0L,next.get("command_seq").getAsLong());
        }
    }
}
