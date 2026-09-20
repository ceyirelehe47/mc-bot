package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RealClientMvpSourceTest {
    private static final Path MAIN=Path.of(
            "src/main/java/io/github/zoyluo/aibot/external");
    private static final Path CLIENT=Path.of(
            "src/client/java/io/github/zoyluo/aibot/client/realclient");

    private static String main(String relative)throws Exception {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test void realClientBackendSupportsOnlyTheBoundedVerticalSlice()
            throws Exception {
        String driver=main("realclient/RealClientExecutionDriver.java");
        assertTrue(driver.contains(
                "Set.of(\"say\",\"goto\",\"mine_opportunity\",\"deposit\")"));
        assertFalse(driver.contains("ServerFakePlayerExecutionDriver"));
        assertFalse(driver.contains("TaskManager.INSTANCE.assign"));
    }

    @Test void realClientUsesNormalPlayerAndSingleAuthority()
            throws Exception {
        String backend=main("realclient/RealClientBodyBackend.java");
        assertTrue(backend.contains("candidate instanceof AIPlayerEntity"));
        assertTrue(backend.contains("offlineUuid(playerName)"));
        assertTrue(backend.contains("real_client_fake_player_authority_conflict"));
    }

    @Test void transportIsLoopbackAuthenticatedAndBounded()
            throws Exception {
        String server=main("realclient/RealClientServerTransport.java");
        String client=Files.readString(
                CLIENT.resolve("RealClientClientTransport.java"));
        assertTrue(server.contains("InetAddress.getLoopbackAddress()"));
        assertTrue(server.contains("MessageDigest.isEqual"));
        assertTrue(server.contains("EXECUTION_CAPACITY=1024"));
        assertTrue(server.contains("ArrayBlockingQueue<>(OUTBOUND_CAPACITY)"));
        assertTrue(client.contains("ArrayBlockingQueue<InboundFrame> inbound"));
        assertTrue(client.contains("ArrayBlockingQueue<JsonObject> outbound"));
        assertFalse(server.contains("ServerPlayerEntity"));
    }

    @Test void gameSessionIncarnationBindsBothDirections()
            throws Exception {
        String runtime=Files.readString(
                CLIENT.resolve("RealClientBodyClientRuntime.java"));
        String client=Files.readString(
                CLIENT.resolve("RealClientClientTransport.java"));
        String server=main("realclient/RealClientServerTransport.java");
        assertTrue(runtime.contains("gameSessionSeq++"));
        assertTrue(runtime.contains("bindGameSession("));
        assertTrue(runtime.contains("gameSessionEpoch,gameSessionSeq"));
        assertTrue(client.contains("record InboundFrame("));
        assertTrue(client.contains("frame.gameSession()"));
        assertTrue(client.contains("frame.commandSeq()"));
        assertTrue(server.contains("\"command_seq\""));
        assertTrue(server.contains("resetServerCommandIncarnation()"));
    }

    @Test void sensorValidationUsesOneCoherentFrame()
            throws Exception {
        String tracker=main("realclient/RealClientOpportunityTracker.java");
        assertFalse(tracker.contains("player.raycast("));
        assertTrue(tracker.contains(
                "Vec3d.fromPolar(sensor.pitch(),sensor.yaw())"));
        assertTrue(tracker.contains("FRAME_FRESH_MS"));
        assertTrue(tracker.contains("POSITION_TOLERANCE"));
        assertTrue(tracker.contains("lastProcessedFrameSeq"));
    }

    @Test void wireProtocolV5IsExplicitAndMixedBinariesFailClosed()
            throws Exception {
        String wire=main("realclient/RealClientWire.java");
        assertTrue(wire.contains("PROTOCOL_VERSION=5"));
        assertTrue(main("realclient/RealClientServerTransport.java")
                .contains("real_client_protocol_mismatch"));
        assertTrue(Files.readString(
                CLIENT.resolve("RealClientClientTransport.java"))
                .contains("real_client_welcome_protocol_mismatch"));
    }

    @Test void graphDispatchStillUsesBridgeSubmit()
            throws Exception {
        String kernel=main("BridgeKernel.java");
        int run=kernel.indexOf("Map<String,Object> graphRunNext");
        int cancel=kernel.indexOf("Map<String,Object> graphCancel",run);
        assertTrue(run>=0 && cancel>run);
        String block=kernel.substring(run,cancel);
        assertTrue(block.contains(
                "submit(supplied,d.requestId(),d.operation(),d.arguments())"));
        assertFalse(block.contains("real_client"));
    }

    @Test void mineCompletionStillRequiresCellGoneAndInventoryGain()
            throws Exception {
        String driver=main("realclient/RealClientExecutionDriver.java");
        assertTrue(driver.contains(
                "if(currentState.isAir()&&current>baseline)"));
        assertTrue(driver.contains(
                "target_cell_replaced_during_execution"));
    }

    @Test void clientParsingFailuresAreTypedNotTickCrashes()
            throws Exception {
        String actions=Files.readString(
                CLIENT.resolve("RealClientActionController.java"));
        assertTrue(actions.contains("real_client_command_invalid:"));
        assertTrue(actions.contains("real_client_control_invalid:"));
        assertTrue(actions.contains("real_client_stale_or_unowned_commit"));
    }
    @Test void backendSelectionIsSingleAndFailClosed()
            throws Exception {
        String access=main("ExternalBodyAccess.java");
        String runtime=main("ExternalBodyRuntime.java");
        assertTrue(access.contains(
                "Set.of(\"server_fake_player\",\"real_client\")"));
        assertTrue(runtime.contains(
                "if(\"real_client\".equals(backendKind))"));
    }

    @Test void realClientOpportunityBirthRemainsDurableBeforeExposure()
            throws Exception {
        String tracker=main("realclient/RealClientOpportunityTracker.java");
        int birth=tracker.indexOf("appendBirth(opportunity)");
        int put=tracker.indexOf(
                "active.put(key(dimension,id),opportunity)",birth);
        assertTrue(birth>=0 && put>birth);
        assertTrue(tracker.contains("real_client_opportunity_birth"));
        assertTrue(tracker.contains("resource_opportunity_consumed"));
    }

    @Test void clientRuntimeRemainsExplicitlyOptInAndClearsInputs()
            throws Exception {
        String runtime=Files.readString(
                CLIENT.resolve("RealClientBodyClientRuntime.java"));
        String actions=Files.readString(
                CLIENT.resolve("RealClientActionController.java"));
        assertTrue(runtime.contains("AIBOT_REAL_CLIENT"));
        assertTrue(runtime.contains("ClientTickEvents.END_CLIENT_TICK"));
        assertTrue(actions.contains("forwardKey.setPressed(false)"));
        assertTrue(actions.contains("cancelBlockBreaking()"));
    }

    @Test void storageAdaptersDoNotAddASecondGraphMutationPath()
            throws Exception {
        String driver=main("realclient/RealClientExecutionDriver.java");
        assertFalse(driver.contains("TaskGraphStore"));
        assertFalse(driver.contains("graphRunNext"));
        assertFalse(driver.contains("backend.start("));
    }

}
