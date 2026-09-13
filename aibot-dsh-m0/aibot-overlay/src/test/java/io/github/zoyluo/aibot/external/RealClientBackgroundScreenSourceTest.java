package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RealClientBackgroundScreenSourceTest {
    private static final Path CLIENT=Path.of(
            "src/client/java/io/github/zoyluo/aibot/client/realclient");
    private static final Path MIXIN=Path.of(
            "src/client/java/io/github/zoyluo/aibot/mixin/client");
    private static final Path MAIN=Path.of(
            "src/main/java/io/github/zoyluo/aibot/external/realclient");

    @Test void backgroundModeAndInputIsolationRemainExplicit()
            throws Exception {
        String source=Files.readString(
                CLIENT.resolve("RealClientInputIsolation.java"));
        assertTrue(source.contains("\"background\""));
        assertTrue(source.contains("AIBOT_REAL_CLIENT_INTERACTIVE"));
        assertTrue(source.contains("GLFW_CURSOR_NORMAL"));
        assertTrue(source.contains("KeyBinding.unpressAll()"));
        assertTrue(Files.readString(
                MIXIN.resolve("RealClientMouseMixin.java"))
                .contains("method=\"lockCursor\""));
    }

    @Test void screenPublisherIsBoundedAndReadOnly()
            throws Exception {
        String screen=Files.readString(
                CLIENT.resolve("RealClientScreenController.java"));
        assertTrue(screen.contains("MAX_SLOTS=128"));
        assertTrue(screen.contains("\"storage_items\""));
        assertTrue(screen.contains("\"title_trust\""));
        assertFalse(screen.contains("clickSlot("));
        assertFalse(screen.contains("interactBlock("));
    }

    @Test void depositUsesNormalOwnedScreenQuickMove()
            throws Exception {
        String actions=Files.readString(
                CLIENT.resolve("RealClientActionController.java"));
        assertTrue(actions.contains("SlotActionType.QUICK_MOVE"));
        assertTrue(actions.contains("client_screen_ownership_lost"));
        assertTrue(actions.contains("targetKind"));
    }

    @Test void serverRequiresCommitAckAndExactTargetDelta()
            throws Exception {
        String driver=Files.readString(
                MAIN.resolve("RealClientExecutionDriver.java"));
        assertTrue(driver.contains("commitSent && mutationAckSeen"));
        assertTrue(driver.contains("intoTarget==fromPlayer"));
        assertTrue(driver.contains(
                "server_authoritative_owned_screen_"));
        assertTrue(driver.contains("RealClientStorageTarget.resolve"));
    }

    @Test void finalFacingRequiresFreshSameSessionCrosshair()
            throws Exception {
        String driver=Files.readString(
                MAIN.resolve("RealClientExecutionDriver.java"));
        assertTrue(driver.contains(
                "client_arrival_and_facing_reported"));
        assertTrue(driver.contains(
                "server_authoritative_arrival_and_facing_verified"));
        assertTrue(driver.contains("sensor.receivedAtMs()"));
        assertTrue(driver.contains("sensor.gameSession()"));
    }

    @Test void protocolV5CarriesOwnershipProvenanceAndStorage()
            throws Exception {
        String wire=Files.readString(
                MAIN.resolve("RealClientWire.java"));
        String server=Files.readString(
                MAIN.resolve("RealClientServerTransport.java"));
        assertTrue(wire.contains("PROTOCOL_VERSION=5"));
        assertTrue(server.contains("screen_epoch"));
        assertTrue(server.contains("adapter_id"));
        assertTrue(server.contains("title_trust"));
        assertTrue(server.contains("storage_items"));
        assertTrue(server.contains("SCREEN_STORAGE_ITEM_CAPACITY=128"));
    }
    @Test void remappedProductionNamespaceIsLogged()
            throws Exception {
        String runtime=Files.readString(
                CLIENT.resolve("RealClientBodyClientRuntime.java"));
        assertTrue(runtime.contains("getCurrentRuntimeNamespace()"));
        assertTrue(runtime.contains("runtime_namespace={}"));
    }

}
