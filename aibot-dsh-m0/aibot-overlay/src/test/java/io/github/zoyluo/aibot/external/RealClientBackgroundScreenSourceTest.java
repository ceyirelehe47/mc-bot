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

    @Test void defaultRealClientModeIsBackgroundAndInteractiveIsExplicit()throws Exception {
        String source=Files.readString(CLIENT.resolve("RealClientInputIsolation.java"));
        assertTrue(source.contains("\"background\""));
        assertTrue(source.contains("AIBOT_REAL_CLIENT_INTERACTIVE"));
        assertTrue(source.contains("GLFW_CURSOR_NORMAL"));
        assertTrue(source.contains("GLFW_FOCUS_ON_SHOW"));
        assertTrue(source.contains("KeyBinding.unpressAll()"));
    }

    @Test void mixinsDenyVanillaMouseCaptureAndHumanCallbacks()throws Exception {
        String mouse=Files.readString(MIXIN.resolve("RealClientMouseMixin.java"));
        String keyboard=Files.readString(MIXIN.resolve("RealClientKeyboardMixin.java"));
        assertTrue(mouse.contains("method=\"lockCursor\""));
        assertTrue(mouse.contains("method=\"onCursorPos\""));
        assertTrue(mouse.contains("method=\"onMouseButton\""));
        assertTrue(keyboard.contains("method=\"onKey\""));
        assertTrue(keyboard.contains("method=\"onChar\""));
    }

    @Test void screenPublisherIsBoundedAndReadOnly()throws Exception {
        String screen=Files.readString(CLIENT.resolve("RealClientScreenController.java"));
        assertTrue(screen.contains("MAX_SLOTS=128"));
        assertTrue(screen.contains("\"type\",\"screen\""));
        assertFalse(screen.contains("clickSlot("));
        assertFalse(screen.contains("interactBlock("));
    }

    @Test void depositUsesNormalHandledScreenQuickMove()throws Exception {
        String actions=Files.readString(CLIENT.resolve("RealClientActionController.java"));
        assertTrue(actions.contains("case \"deposit\""));
        assertTrue(actions.contains("interactBlock("));
        assertTrue(actions.contains("SlotActionType.QUICK_MOVE"));
        assertTrue(actions.contains("closeHandledScreen()"));
    }

    @Test void serverRequiresContainerAndInventoryDeltaProof()throws Exception {
        String driver=Files.readString(MAIN.resolve("RealClientExecutionDriver.java"));
        assertTrue(driver.contains("Set.of(\"say\",\"goto\",\"mine_opportunity\",\"deposit\")"));
        assertTrue(driver.contains("real_client_deposit_mvp_requires_barrel_crosshair"));
        assertTrue(driver.contains("fromPlayer>0&&fromPlayer==intoContainer"));
        assertTrue(driver.contains("server_authoritative_container_transfer_verified"));
    }

    @Test void finalFacingRequiresClientAckAndFreshCrosshair()throws Exception {
        String driver=Files.readString(MAIN.resolve("RealClientExecutionDriver.java"));
        assertTrue(driver.contains("client_arrival_and_facing_reported"));
        assertTrue(driver.contains("server_authoritative_arrival_and_facing_verified"));
        assertTrue(driver.contains("sensor.crosshairX()==faceTarget.getX()"));
    }

    @Test void protocolV3CarriesWindowAndScreenState()throws Exception {
        String wire=Files.readString(MAIN.resolve("RealClientWire.java"));
        String server=Files.readString(MAIN.resolve("RealClientServerTransport.java"));
        assertTrue(wire.contains("PROTOCOL_VERSION=3"));
        assertTrue(server.contains("window_mode"));
        assertTrue(server.contains("case \"screen\""));
        assertTrue(server.contains("SCREEN_SLOT_CAPACITY=128"));
    }
}
