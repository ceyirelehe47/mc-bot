package io.github.zoyluo.aibot.external;

import com.google.gson.JsonArray;
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

final class RealClientScreenWireTest {
    private static final String TOKEN="unit-test-real-client-token-0123456789abcdef";
    private RealClientServerTransport transport;

    @AfterEach void close() {
        if(transport!=null)transport.close();
    }

    private int freePort()throws Exception {
        try(ServerSocket socket=new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private Client client()throws Exception {
        transport=new RealClientServerTransport("bob","Bob",TOKEN,freePort());
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
            assertEquals("welcome",RealClientWire.read(input).get("type").getAsString());
        }
        void heartbeat()throws Exception {
            JsonObject h=new JsonObject();
            h.addProperty("type","heartbeat");
            h.addProperty("session_epoch",epoch);
            h.addProperty("game_session","game-a");
            h.addProperty("game_session_seq",0);
            h.addProperty("frame_seq",0L);
            h.addProperty("player_uuid","00000000-0000-0000-0000-000000000001");
            h.addProperty("x",0D);h.addProperty("y",64D);h.addProperty("z",0D);
            h.addProperty("yaw",0F);h.addProperty("pitch",0F);
            h.addProperty("selected_slot",0);h.addProperty("crosshair_present",false);
            RealClientWire.write(output,h);
        }
        void screen(long seq,String item,int count)throws Exception {
            JsonObject s=new JsonObject();
            s.addProperty("type","screen");
            s.addProperty("session_epoch",epoch);
            s.addProperty("game_session","game-a");
            s.addProperty("game_session_seq",0);
            s.addProperty("screen_seq",seq);
            s.addProperty("present",true);
            s.addProperty("screen_class","net.minecraft.client.gui.screen.ingame.GenericContainerScreen");
            s.addProperty("handler_class","net.minecraft.screen.GenericContainerScreenHandler");
            s.addProperty("title","Barrel");
            s.addProperty("sync_id",4);
            s.addProperty("slot_count",63);
            s.addProperty("truncated",false);
            JsonArray slots=new JsonArray();
            JsonObject slot=new JsonObject();
            slot.addProperty("slot_id",0);
            slot.addProperty("inventory_index",0);
            slot.addProperty("inventory_kind","container");
            slot.addProperty("item",item);
            slot.addProperty("count",count);
            slot.addProperty("can_take",true);
            slots.add(slot);
            s.add("slots",slots);
            RealClientWire.write(output,s);
        }
        @Override public void close()throws Exception { socket.close(); }
    }

    private static <T> T await(java.util.function.Supplier<T> probe)throws Exception {
        long deadline=System.currentTimeMillis()+5000L;
        T value;
        do {
            value=probe.get();
            if(value!=null)return value;
            Thread.sleep(20L);
        } while(System.currentTimeMillis()<deadline);
        return value;
    }

    @Test void screenSnapshotIsBoundToTheCurrentGameSession()throws Exception {
        try(Client client=client()) {
            client.heartbeat();
            client.screen(0L,"minecraft:cobblestone",8);
            var screen=await(()->transport.session()
                    .map(RealClientServerTransport.SessionSnapshot::screen).orElse(null));
            assertNotNull(screen);
            assertTrue(screen.present());
            assertEquals("game-a",screen.gameSession());
            assertEquals("background",transport.session().orElseThrow().windowMode());
            assertEquals(1,screen.slots().size());
            assertEquals("minecraft:cobblestone",screen.slots().getFirst().itemId());
        }
    }

    @Test void duplicateScreenSequenceNeverReplacesTheSnapshot()throws Exception {
        try(Client client=client()) {
            client.heartbeat();
            client.screen(2L,"minecraft:cobblestone",8);
            var first=await(()->transport.session()
                    .map(RealClientServerTransport.SessionSnapshot::screen).orElse(null));
            assertNotNull(first);
            client.screen(2L,"minecraft:dirt",9);
            Thread.sleep(200L);
            var after=transport.session().orElseThrow().screen();
            assertEquals("minecraft:cobblestone",after.slots().getFirst().itemId());
            assertEquals(8,after.slots().getFirst().count());
        }
    }
}
