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
            assertEquals(
                    "welcome",
                    RealClientWire.read(input)
                            .get("type").getAsString());
        }

        void heartbeat(
                String gameSession,int gameSeq,long frameSeq)
                throws Exception {
            JsonObject h=new JsonObject();
            h.addProperty("type","heartbeat");
            h.addProperty("session_epoch",epoch);
            h.addProperty("game_session",gameSession);
            h.addProperty("game_session_seq",gameSeq);
            h.addProperty("frame_seq",frameSeq);
            h.addProperty(
                    "player_uuid",
                    "00000000-0000-0000-0000-000000000001");
            h.addProperty("x",0D);h.addProperty("y",64D);h.addProperty("z",0D);
            h.addProperty("yaw",0F);h.addProperty("pitch",0F);
            h.addProperty("selected_slot",0);
            h.addProperty("crosshair_present",false);
            RealClientWire.write(output,h);
        }

        void screen(
                String gameSession,int gameSeq,long seq,
                String screenEpoch,String adapter,
                String item,int count)throws Exception {
            JsonObject s=new JsonObject();
            s.addProperty("type","screen");
            s.addProperty("session_epoch",epoch);
            s.addProperty("game_session",gameSession);
            s.addProperty("game_session_seq",gameSeq);
            s.addProperty("screen_seq",seq);
            s.addProperty("present",true);
            s.addProperty("screen_epoch",screenEpoch);
            s.addProperty("adapter_id",adapter);
            s.addProperty(
                    "screen_class",
                    "com.tom.storagemod.screen.StorageTerminalScreen");
            s.addProperty(
                    "handler_class",
                    "com.tom.storagemod.menu.StorageTerminalMenu");
            s.addProperty("title","Player supplied title");
            s.addProperty("title_origin","mod_ui");
            s.addProperty("title_trust","untrusted_data");
            s.addProperty("sync_id",4);
            s.addProperty("slot_count",63);
            s.addProperty("truncated",false);

            JsonArray capabilities=new JsonArray();
            capabilities.add("deposit_quick_move");
            capabilities.add("network_storage_read");
            capabilities.add("widget_introspection");
            s.add("capabilities",capabilities);

            JsonArray widgets=new JsonArray();
            JsonObject widget=new JsonObject();
            widget.addProperty("widget_index",0);
            widget.addProperty(
                    "widget_class",
                    "net.minecraft.class_339");
            widget.addProperty("message","Untrusted server text");
            widget.addProperty("message_origin","mod_ui");
            widget.addProperty("message_trust","untrusted_data");
            widget.addProperty("active",true);
            widget.addProperty("visible",true);
            widgets.add(widget);
            s.add("widgets",widgets);

            JsonArray storage=new JsonArray();
            JsonObject stored=new JsonObject();
            stored.addProperty("item",item);
            stored.addProperty("count",count);
            storage.add(stored);
            s.add("storage_items",storage);

            JsonArray slots=new JsonArray();
            JsonObject slot=new JsonObject();
            slot.addProperty("slot_id",0);
            slot.addProperty("inventory_index",0);
            slot.addProperty("inventory_kind","player");
            slot.addProperty("item",item);
            slot.addProperty("count",Math.min(count,999));
            slot.addProperty("can_take",true);
            slots.add(slot);
            s.add("slots",slots);
            RealClientWire.write(output,s);
        }

        @Override public void close()throws Exception {
            socket.close();
        }
    }

    private static <T> T await(
            java.util.function.Supplier<T> probe)throws Exception {
        long deadline=System.currentTimeMillis()+5000L;
        T value;
        do {
            value=probe.get();
            if(value!=null)return value;
            Thread.sleep(20L);
        } while(System.currentTimeMillis()<deadline);
        return value;
    }

    @Test void screenSnapshotCarriesProvenanceAndStorageItems()
            throws Exception {
        try(Client client=client()) {
            client.heartbeat("game-a",0,0L);
            client.screen(
                    "game-a",0,0L,
                    "screen-epoch-a",
                    "toms_storage_terminal_v1",
                    "minecraft:cobblestone",8000);
            var screen=await(()->transport.session()
                    .map(RealClientServerTransport
                            .SessionSnapshot::screen)
                    .orElse(null));
            assertNotNull(screen);
            assertEquals("mod_ui",screen.titleOrigin());
            assertEquals("untrusted_data",screen.titleTrust());
            assertEquals(1,screen.widgets().size());
            assertEquals(
                    "untrusted_data",
                    screen.widgets().getFirst().messageTrust());
            assertEquals(1,screen.storageItems().size());
            assertEquals(
                    8000L,
                    screen.storageItems().getFirst().count());
            assertEquals(
                    "toms_storage_terminal_v1",
                    screen.adapterId());
        }
    }

    @Test void duplicateScreenSequenceNeverReplacesSnapshot()
            throws Exception {
        try(Client client=client()) {
            client.heartbeat("game-a",0,0L);
            client.screen(
                    "game-a",0,2L,"epoch-a",
                    "toms_storage_terminal_v1",
                    "minecraft:cobblestone",8);
            assertNotNull(await(()->transport.session()
                    .map(RealClientServerTransport
                            .SessionSnapshot::screen)
                    .orElse(null)));
            client.screen(
                    "game-a",0,2L,"epoch-b",
                    "toms_storage_terminal_v1",
                    "minecraft:dirt",9);
            Thread.sleep(200L);
            var after=transport.session().orElseThrow().screen();
            assertEquals("epoch-a",after.screenEpoch());
            assertEquals(
                    "minecraft:cobblestone",
                    after.storageItems().getFirst().itemId());
        }
    }

    @Test void gameSessionAdvanceClearsOldScreen()
            throws Exception {
        try(Client client=client()) {
            client.heartbeat("game-a",0,0L);
            client.screen(
                    "game-a",0,0L,"epoch-a",
                    "toms_storage_terminal_v1",
                    "minecraft:cobblestone",8);
            assertNotNull(await(()->transport.session()
                    .map(RealClientServerTransport
                            .SessionSnapshot::screen)
                    .orElse(null)));
            client.heartbeat("game-b",1,0L);
            var advanced=await(()->{
                var session=transport.session().orElse(null);
                return session!=null
                        &&"game-b".equals(session.gameSession())
                        ?session:null;
            });
            assertNotNull(advanced);
            assertNull(advanced.screen());
        }
    }
    @Test void unrecognizedAdapterRemainsReadOnlyData()
            throws Exception {
        try(Client client=client()) {
            client.heartbeat("game-a",0,0L);
            client.screen(
                    "game-a",0,0L,"epoch-a",
                    "unrecognized","minecraft:air",0);
            var screen=await(()->transport.session()
                    .map(RealClientServerTransport
                            .SessionSnapshot::screen)
                    .orElse(null));
            assertNotNull(screen);
            assertEquals("unrecognized",screen.adapterId());
            assertTrue(screen.present());
            assertEquals("untrusted_data",screen.titleTrust());
        }
    }

}
