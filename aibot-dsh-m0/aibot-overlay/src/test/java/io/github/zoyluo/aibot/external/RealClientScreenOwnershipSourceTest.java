package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RealClientScreenOwnershipSourceTest {
    private static final Path CLIENT=Path.of(
            "src/client/java/io/github/zoyluo/aibot/client/realclient");
    private static final Path MAIN=Path.of(
            "src/main/java/io/github/zoyluo/aibot/external/realclient");

    @Test void gameStateAndInboundCommandsAreClearedOnDisconnect()
            throws Exception {
        String transport=Files.readString(
                CLIENT.resolve("RealClientClientTransport.java"));
        String runtime=Files.readString(
                CLIENT.resolve("RealClientBodyClientRuntime.java"));
        assertTrue(transport.contains("void clearGameSession()"));
        assertTrue(transport.contains("inbound.clear()"));
        assertTrue(transport.contains("lastInboundCommandSeq=-1L"));
        assertTrue(runtime.indexOf("transport.clearGameSession()")
                <runtime.indexOf("actions.disconnected(client)"));
    }

    @Test void clientMutationRequiresServerCommit()
            throws Exception {
        String actions=Files.readString(
                CLIENT.resolve("RealClientActionController.java"));
        int wait=actions.indexOf("if(!authorized)");
        int click=actions.indexOf("clickSlot(");
        assertTrue(wait>=0 && click>wait);
        assertTrue(actions.contains("\"commit\".equals("));
        assertTrue(actions.contains("client_preexisting_screen_open"));
        assertTrue(actions.contains("client_screen_ownership_lost"));
    }

    @Test void serverCommitIsBoundToStorageTarget()
            throws Exception {
        String driver=Files.readString(
                MAIN.resolve("RealClientExecutionDriver.java"));
        String target=Files.readString(
                MAIN.resolve("RealClientStorageTarget.java"));
        assertTrue(driver.contains("target.handlerOwns(player,screen)"));
        assertTrue(driver.contains("target.adapterAllowed(screen.adapterId())"));
        assertTrue(driver.contains("screen.screenSeq()>baselineScreenSeq"));
        assertTrue(target.contains("slot.inventory==barrel"));
        assertTrue(target.contains(
                "RealClientTomsStorageServerCompat.handlerOwns"));
    }

    @Test void adapterRegistryIsNamespaceSafeAndExact()
            throws Exception {
        String adapters=Files.readString(
                CLIENT.resolve("RealClientScreenAdapterRegistry.java"));
        assertTrue(adapters.contains(
                "screen.getClass()==GenericContainerScreen.class"));
        assertTrue(adapters.contains(
                "instanceof GenericContainerScreenHandler"));
        assertTrue(adapters.contains("toms_storage_terminal_v1"));
        assertTrue(adapters.contains("UNRECOGNIZED"));
        assertFalse(adapters.contains(
                "\"net.minecraft.client.gui.screen.ingame.GenericContainerScreen\""));
    }

    @Test void readModelMarksModTextUntrusted()
            throws Exception {
        String controller=Files.readString(
                CLIENT.resolve("RealClientScreenController.java"));
        String cognitive=Files.readString(
                MAIN.resolve("RealClientCognitiveViewBuilder.java"));
        assertTrue(controller.contains("\"message_origin\""));
        assertTrue(controller.contains("\"message_trust\""));
        assertTrue(controller.contains("\"storage_items\""));
        assertTrue(controller.contains("\"untrusted_data\""));
        assertTrue(cognitive.contains("\"structured_data\""));
    }

    @Test void noGenericUiMutationApiExists()
            throws Exception {
        String combined=Files.readString(
                CLIENT.resolve("RealClientActionController.java"))
                +Files.readString(
                        CLIENT.resolve("RealClientScreenController.java"))
                +Files.readString(
                        CLIENT.resolve("RealClientScreenAdapterRegistry.java"));
        assertFalse(combined.contains("clickAtCoordinate"));
        assertFalse(combined.contains("activateWidget"));
        assertFalse(combined.contains("mouseClicked("));
        assertEquals(1,count(combined,"clickSlot("));
    }

    @Test void movingGotoSettlesBeforeFacingTimeout()
            throws Exception {
        String actions=Files.readString(
                CLIENT.resolve("RealClientActionController.java"));
        assertTrue(actions.contains("ARRIVAL_STABLE_TICKS"));
        assertTrue(actions.contains("client_arrival_settling"));
        assertTrue(actions.contains("horizontalLengthSquared()"));
    }

    @Test void protocolV5IsExplicit()
            throws Exception {
        assertTrue(Files.readString(
                MAIN.resolve("RealClientWire.java"))
                .contains("PROTOCOL_VERSION=5"));
    }

    private static int count(String text,String token) {
        int count=0,from=0;
        while((from=text.indexOf(token,from))>=0) {
            count++;
            from+=token.length();
        }
        return count;
    }
}
