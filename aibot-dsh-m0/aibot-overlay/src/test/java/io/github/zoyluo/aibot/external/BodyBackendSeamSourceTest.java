package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class BodyBackendSeamSourceTest {
    private static final Path ROOT=Path.of("src/main/java/io/github/zoyluo/aibot/external");
    private static String read(String name)throws IOException {
        return Files.readString(ROOT.resolve(name));
    }

    @Test void physicalExecutionPortHasNoMinecraftDependency()throws Exception {
        String source=read("PhysicalExecutionDriver.java");
        assertFalse(source.contains("net.minecraft"));
        assertFalse(source.contains("AIPlayerEntity"));
        assertTrue(source.contains("record Request(String executionId,String operation,String argumentsJson)"));
        assertTrue(source.contains("BodyBackend.Handle start(Request request)"));
    }

    @Test void minecraftBackendDelegatesMutationAndDoesNotConstructTasks()throws Exception {
        String source=read("MinecraftBodyBackend.java");
        assertTrue(source.contains("new ServerFakePlayerExecutionDriver"));
        assertTrue(source.contains("requireExecutionDriver().start("));
        assertTrue(source.contains("executionDriver.pause()"));
        assertTrue(source.contains("executionDriver.resume()"));
        assertTrue(source.contains("executionDriver.cancel(reason)"));
        for(String forbidden:new String[]{
                "new MoveTask(","new GatherQuotaTask(","new CraftTask(","new SmeltTask(",
                "new KnownResourceTask(","new FarmTask(","new BuildTask(","new StockpileTask(",
                "TaskManager.INSTANCE.assign(","IntentController.INSTANCE.pause(",
                "IntentController.INSTANCE.resume(","IntentController.INSTANCE.cancelAll("
        }) assertFalse(source.contains(forbidden),"backend still owns mechanics: "+forbidden);
    }

    @Test void serverFakePlayerDriverOwnsCurrentOperationMapping()throws Exception {
        String source=read("ServerFakePlayerExecutionDriver.java");
        for(String operation:new String[]{
                "\"goto\"","\"gather\"","\"craft\"","\"smelt\"","\"eat\"",
                "\"set_base\"","\"deposit\"","\"say\"","\"register_home\"",
                "\"capture_home\"","\"repair_home\"","\"register_farm\"",
                "\"tend_farm\"","\"mine_opportunity\""
        }) assertTrue(source.contains(operation),"missing operation "+operation);
        assertTrue(source.contains("TaskManager.INSTANCE.assign("));
        assertTrue(source.contains("currentBody.get()!=executionBody"));
    }

    @Test void logicalBodyIdentityIsSeparatedFromPhysicalSession()throws Exception {
        String access=read("ExternalBodyAccess.java");
        String backend=read("MinecraftBodyBackend.java");
        String contract=read("BodyBackend.java");
        assertTrue(access.contains("AIBOT_EXTERNAL_BODY_ID"));
        assertTrue(backend.contains("\"server_fake_player\""));
        assertTrue(backend.contains("UUID.randomUUID().toString()"));
        assertTrue(backend.contains("minecraft_profile_uuid"));
        assertTrue(contract.contains("record Binding(String bodyId,String backendKind,String instanceId,String sessionEpoch)"));
    }

    @Test void kernelPersistsAndFencesPhysicalSessionChangesWithoutTouchingGraphDispatch()throws Exception {
        String kernel=read("BridgeKernel.java");
        assertTrue(kernel.contains("body_session_changed"));
        assertTrue(kernel.contains("body_instance_id"));
        assertTrue(kernel.contains("body_session_epoch"));
        assertTrue(kernel.contains("backend_kind"));
        int run=kernel.indexOf("Map<String,Object> graphRunNext");
        int cancel=kernel.indexOf("Map<String,Object> graphCancel",run);
        assertTrue(run>=0 && cancel>run);
        String graphBlock=kernel.substring(run,cancel);
        assertTrue(graphBlock.contains("submit(supplied,d.requestId(),d.operation(),d.arguments())"));
        assertFalse(graphBlock.contains("backend.start("));
    }
}
