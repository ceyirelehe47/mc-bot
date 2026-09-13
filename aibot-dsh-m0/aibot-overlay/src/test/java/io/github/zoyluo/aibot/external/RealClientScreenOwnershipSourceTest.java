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

    @Test void gameStateMessagesAreSuppressedBeforeJoin()
            throws Exception {
        String transport=Files.readString(
                CLIENT.resolve(
                        "RealClientClientTransport.java"));
        String runtime=Files.readString(
                CLIENT.resolve(
                        "RealClientBodyClientRuntime.java"));
        String screens=Files.readString(
                CLIENT.resolve(
                        "RealClientScreenController.java"));
        assertTrue(transport.contains(
                "GAME_BOUND_TYPES.contains(type) "
                        +"&& !gameSessionBound()"));
        assertTrue(transport.contains(
                "void clearGameSession()"));
        assertTrue(runtime.contains(
                "transport.clearGameSession()"));
        assertTrue(screens.contains(
                "!transport.gameSessionBound()"));
    }

    @Test void depositMutationRequiresServerCommit()
            throws Exception {
        String actions=Files.readString(
                CLIENT.resolve(
                        "RealClientActionController.java"));
        int wait=actions.indexOf("if(!authorized)");
        int click=actions.indexOf("clickSlot(");
        assertTrue(wait>=0 && click>wait);
        assertTrue(actions.contains(
                "\"commit\".equals("));
        assertTrue(actions.contains(
                "client_preexisting_screen_open"));
        assertTrue(actions.contains(
                "client_screen_ownership_lost"));
    }

    @Test void serverCommitIsBoundToTargetInventory()
            throws Exception {
        String driver=Files.readString(
                MAIN.resolve(
                        "RealClientExecutionDriver.java"));
        assertTrue(driver.contains(
                "handlerOwnsTargetInventory("));
        assertTrue(driver.contains(
                "slot.inventory==targetInventory"));
        assertTrue(driver.contains(
                "screen.screenSeq()>baselineScreenSeq"));
        assertTrue(driver.contains(
                "screen.screenEpoch()"));
        assertTrue(driver.contains(
                "screen.capabilities().contains("));
        assertTrue(driver.contains(
                "\"phase\",\"commit\""));
    }

    @Test void screenAdaptersAreExactAllowlists()
            throws Exception {
        String adapters=Files.readString(
                CLIENT.resolve(
                        "RealClientScreenAdapterRegistry.java"));
        assertTrue(adapters.contains(
                "mc2a_fixture_storage_v1"));
        assertTrue(adapters.contains(
                "vanilla_generic_storage_v1"));
        assertTrue(adapters.contains(
                "UNRECOGNIZED"));
        assertFalse(adapters.contains("mouseClicked("));
        assertFalse(adapters.contains("setMouse("));
        assertFalse(adapters.contains("x()+"));
    }

    @Test void readModelExportsWidgetsButCannotActivateThem()
            throws Exception {
        String controller=Files.readString(
                CLIENT.resolve(
                        "RealClientScreenController.java"));
        String cognitive=Files.readString(
                MAIN.resolve(
                        "RealClientCognitiveViewBuilder.java"));
        assertTrue(controller.contains(
                "MAX_SLOTS=128"));
        assertTrue(controller.contains(
                "\"widgets\""));
        assertTrue(controller.contains(
                "\"capabilities\""));
        assertFalse(controller.contains("clickSlot("));
        assertFalse(controller.contains("interactBlock("));
        assertTrue(cognitive.contains(
                "screen.widgets()"));
        assertTrue(cognitive.contains(
                "\"adapter_id\""));
        assertTrue(cognitive.contains(
                "\"screen_epoch\""));
    }

    @Test void movingGotoSettlesBeforeFacingTimeout()
            throws Exception {
        String actions=Files.readString(
                CLIENT.resolve(
                        "RealClientActionController.java"));
        String driver=Files.readString(
                MAIN.resolve(
                        "RealClientExecutionDriver.java"));
        assertTrue(actions.contains(
                "ARRIVAL_STABLE_TICKS"));
        assertTrue(actions.contains(
                "client_arrival_settling"));
        assertTrue(actions.contains(
                "horizontalLengthSquared()"));
        assertTrue(driver.contains(
                "sensor.receivedAtMs()"));
        assertTrue(driver.contains(
                "sensor.gameSession()"));
    }

    @Test void protocolV4IsExplicit()
            throws Exception {
        String wire=Files.readString(
                MAIN.resolve("RealClientWire.java"));
        assertTrue(wire.contains(
                "PROTOCOL_VERSION=4"));
    }

    @Test void noGenericWidgetOrCoordinateMutationApiExists()
            throws Exception {
        String combined=Files.readString(
                CLIENT.resolve(
                        "RealClientActionController.java"))
                +Files.readString(
                        CLIENT.resolve(
                                "RealClientScreenController.java"))
                +Files.readString(
                        CLIENT.resolve(
                                "RealClientScreenAdapterRegistry.java"));
        assertFalse(combined.contains(
                "clickAtCoordinate"));
        assertFalse(combined.contains(
                "activateWidget"));
        assertFalse(combined.contains(
                "mouseClicked("));
        assertEquals(
                1,count(combined,"clickSlot("));
    }

    private static int count(
            String text,String token) {
        int count=0,from=0;
        while((from=text.indexOf(token,from))>=0) {
            count++;
            from+=token.length();
        }
        return count;
    }
}
