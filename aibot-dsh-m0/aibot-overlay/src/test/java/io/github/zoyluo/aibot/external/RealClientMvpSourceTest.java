package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RealClientMvpSourceTest {
    private static final Path MAIN=Path.of("src/main/java/io/github/zoyluo/aibot/external");
    private static final Path CLIENT=Path.of("src/client/java/io/github/zoyluo/aibot/client/realclient");

    private static String main(String relative)throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test void realClientBackendSupportsOnlyTheMvpVerticalSlice()throws Exception {
        String driver=main("realclient/RealClientExecutionDriver.java");
        assertTrue(driver.contains("Set.of(\"say\",\"goto\",\"mine_opportunity\",\"deposit\")"));
        assertFalse(driver.contains("ServerFakePlayerExecutionDriver"));
        assertFalse(driver.contains("TaskManager.INSTANCE.assign"));
    }

    @Test void realClientUsesNormalPlayerAndNeverAnAiPlayerEntity()throws Exception {
        String backend=main("realclient/RealClientBodyBackend.java");
        assertTrue(backend.contains("candidate instanceof AIPlayerEntity"));
        assertTrue(backend.contains("offlineUuid(playerName)"));
        assertTrue(backend.contains("\"real_client\""));
    }

    @Test void transportIsLoopbackAuthenticatedAndMinecraftThreadAgnostic()throws Exception {
        String transport=main("realclient/RealClientServerTransport.java");
        assertTrue(transport.contains("InetAddress.getLoopbackAddress()"));
        assertTrue(transport.contains("MessageDigest.isEqual"));
        assertFalse(transport.contains("ServerPlayerEntity"));
        assertFalse(transport.contains("ServerWorld"));
    }

    @Test void clientRuntimeIsExplicitlyOptInAndClearsInputs()throws Exception {
        String runtime=Files.readString(CLIENT.resolve("RealClientBodyClientRuntime.java"));
        String actions=Files.readString(CLIENT.resolve("RealClientActionController.java"));
        assertTrue(runtime.contains("AIBOT_REAL_CLIENT"));
        assertTrue(runtime.contains("ClientTickEvents.END_CLIENT_TICK"));
        assertTrue(actions.contains("forwardKey.setPressed(false)"));
        assertTrue(actions.contains("cancelBlockBreaking()"));
        assertTrue(actions.contains("updateBlockBreakingProgress"));
        assertTrue(actions.contains("void controlSessionLost"));
        assertTrue(runtime.contains("actions.controlSessionLost(client)"));
    }

    @Test void gameSessionIncarnationIsGeneratedPerJoinAndBindsEveryMessage()throws Exception {
        String runtime=Files.readString(CLIENT.resolve("RealClientBodyClientRuntime.java"));
        String client=Files.readString(CLIENT.resolve("RealClientClientTransport.java"));
        String server=main("realclient/RealClientServerTransport.java");
        String backend=main("realclient/RealClientBodyBackend.java");
        assertTrue(runtime.contains("ClientPlayConnectionEvents.JOIN"));
        assertTrue(runtime.contains("gameSessionSeq++"));
        assertTrue(runtime.contains("bindGameSession(gameSessionEpoch,gameSessionSeq)"));
        assertTrue(runtime.contains("heartbeat.addProperty(\"frame_seq\",++frameSeq)"));
        assertTrue(client.contains("void bindGameSession(String epoch,int seq)"));
        assertTrue(client.contains("message.addProperty(\"game_session\",gameSessionEpoch)"));
        assertTrue(server.contains("real_client_game_session_stale_incarnation"));
        assertTrue(server.contains("real_client_game_session_epoch_mismatch"));
        // frame 单调性:重复/倒序帧永不刷新 sensor 快照。
        assertTrue(server.contains("frameSeq<=lastFrameSeq"));
        // binding 跟随 Minecraft 游戏 incarnation,而非控制 TCP epoch。
        assertTrue(backend.contains("preparedSession.equals(sensor.gameSession())"));
        assertFalse(backend.contains("preparedSession.equals(session.sessionEpoch())"));
    }

    @Test void sensorValidationUsesSameFrameLookNotALaterServerPose()throws Exception {
        String tracker=main("realclient/RealClientOpportunityTracker.java");
        // 同帧重建:服务器权威眼睛位置 + 该 frame 的 look direction,禁止再用
        // observe 时刻的 player.raycast() 与旧 crosshair 拼接。
        assertFalse(tracker.contains("player.raycast("));
        assertTrue(tracker.contains("Vec3d.fromPolar(sensor.pitch(),sensor.yaw())"));
        assertTrue(tracker.contains("player.getEyePos()"));
        assertTrue(tracker.contains("RaycastContext.ShapeType.OUTLINE"));
        assertTrue(tracker.contains("FRAME_FRESH_MS"));
        assertTrue(tracker.contains("POSITION_TOLERANCE"));
        assertTrue(tracker.contains("\"frame_stale\""));
        assertTrue(tracker.contains("\"position_drift_exceeded\""));
        assertTrue(tracker.contains("\"game_session_missing\""));
    }

    @Test void wireProtocolV4IsExplicitAndMixedBinariesFailClosed()throws Exception {
        String wire=main("realclient/RealClientWire.java");
        assertTrue(wire.contains("PROTOCOL_VERSION=4"));
        String server=main("realclient/RealClientServerTransport.java");
        String client=Files.readString(CLIENT.resolve("RealClientClientTransport.java"));
        assertTrue(server.contains("real_client_protocol_mismatch"));
        assertTrue(client.contains("real_client_welcome_protocol_mismatch"));
    }

    @Test void graphDispatchStillUsesBridgeSubmitAndNoBackendBranch()throws Exception {
        String kernel=main("BridgeKernel.java");
        int run=kernel.indexOf("Map<String,Object> graphRunNext");
        int cancel=kernel.indexOf("Map<String,Object> graphCancel",run);
        assertTrue(run>=0 && cancel>run);
        String block=kernel.substring(run,cancel);
        assertTrue(block.contains("submit(supplied,d.requestId(),d.operation(),d.arguments())"));
        assertFalse(block.contains("real_client"));
        assertFalse(block.contains("server_fake_player"));
    }

    @Test void backendSelectionIsSingleAndFailClosed()throws Exception {
        String access=main("ExternalBodyAccess.java");
        String runtime=main("ExternalBodyRuntime.java");
        assertTrue(access.contains("Set.of(\"server_fake_player\",\"real_client\")"));
        assertTrue(runtime.contains("if(\"real_client\".equals(backendKind))"));
        assertFalse(runtime.contains("new MinecraftBodyBackend")
                && runtime.contains("new RealClientBodyBackend")
                && runtime.contains("backend = new MinecraftBodyBackend")
                && runtime.contains("backend = new RealClientBodyBackend")
                && runtime.contains("backend.start("));
    }



    @Test void mineCompletionRequiresExactBlockGoneAndInventoryGain()throws Exception {
        String driver=main("realclient/RealClientExecutionDriver.java");
        assertTrue(driver.contains("if(currentState.isAir()&&current>baseline)"));
        int stale=driver.indexOf("target_cell_replaced_during_execution");
        int success=driver.indexOf("if(currentState.isAir()&&current>baseline)");
        assertTrue(stale>=0 && success>stale,
                "a replaced target must terminalize stale before any inventory delta can count");
    }

    @Test void transportAuthorityAndQueuesAreAtomicallyBounded()throws Exception {
        String server=main("realclient/RealClientServerTransport.java");
        String client=Files.readString(CLIENT.resolve("RealClientClientTransport.java"));
        assertTrue(server.contains("active.compareAndSet(prior,replacement)"));
        assertTrue(server.contains("EXECUTION_CAPACITY=1024"));
        assertTrue(server.contains("ArrayBlockingQueue<>(OUTBOUND_CAPACITY)"));
        assertTrue(client.contains("ArrayBlockingQueue<JsonObject> inbound"));
        assertTrue(client.contains("ArrayBlockingQueue<JsonObject> outbound"));
        assertTrue(client.contains("aibot-real-client-control-writer"));
        assertFalse(client.contains("RealClientWire.write(current.output"));
        assertTrue(client.contains("real_client_control_host_must_be_loopback"));
    }

    @Test void realClientBackendFailsClosedOnSameNamedFakePlayer()throws Exception {
        String backend=main("realclient/RealClientBodyBackend.java");
        String runtime=main("ExternalBodyRuntime.java");
        assertTrue(backend.contains("real_client_fake_player_authority_conflict"));
        assertTrue(runtime.contains("real_client_fake_player_authority_conflict"));
        assertTrue(backend.contains("AIPlayerManager.INSTANCE.all()"));
    }

    @Test void realClientOpportunityBirthIsDurableBeforeExposure()throws Exception {
        String tracker=main("realclient/RealClientOpportunityTracker.java");
        int birth=tracker.indexOf("appendBirth(opportunity)");
        int put=tracker.indexOf("active.put(key(dimension,id),opportunity)",birth);
        assertTrue(birth>=0 && put>birth);
        assertTrue(tracker.contains("real_client_opportunity_birth"));
        assertTrue(tracker.contains("resource_opportunity_consumed"));
        assertTrue(tracker.contains("resource_opportunity_stale"));
    }
}
