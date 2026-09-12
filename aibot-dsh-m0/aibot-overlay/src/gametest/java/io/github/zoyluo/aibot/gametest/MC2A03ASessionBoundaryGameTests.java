package io.github.zoyluo.aibot.gametest;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.BridgeJournal;
import io.github.zoyluo.aibot.external.BridgeKernel;
import io.github.zoyluo.aibot.external.JsonOutput;
import io.github.zoyluo.aibot.external.MinecraftBodyBackend;
import io.github.zoyluo.aibot.external.TaskGraphStore;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * MC-2A0.3A: replace the physical fake-player body inside one running Minecraft server process.
 * This exercises AIPlayerManager -> MinecraftBodyBackend.ready -> Binding -> BridgeKernel fencing.
 */
public final class MC2A03ASessionBoundaryGameTests implements FabricGameTest {
    private static final String BOT="Mc2a03aBot";
    private static final String LOGICAL_BODY="mc2a03a-body";

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,
            batchId="mc2a03a_session_boundary",tickLimit=200)
    public void mc2a03aInProcessBodyReplacementFencesExecutionAndReadPlane(
            TestContext context) {
        ServerWorld world=context.getWorld();
        BlockPos start=context.getAbsolutePos(new BlockPos(2,2,2));
        prepareFlat(world,start);

        Path journalPath=null;
        BridgeJournal journal=null;
        BridgeKernel kernel=null;
        try {
            AIPlayerManager.INSTANCE.despawn(world.getServer(),BOT);
            AIPlayerEntity first=AIPlayerManager.INSTANCE.spawn(
                    world.getServer(),BOT,world,Vec3d.ofBottomCenter(start),
                    0.0F,0.0F,GameMode.SURVIVAL).orElseThrow();
            first.teleport(
                    world,start.getX()+.5D,start.getY(),start.getZ()+.5D,
                    Set.of(),0,0,true);

            journalPath=Files.createTempFile(
                    "mc2a03a-session-boundary-",".journal");
            journal=new BridgeJournal(
                    journalPath,()->(long)world.getServer().getTicks());
            MinecraftBodyBackend backend=new MinecraftBodyBackend(
                    world.getServer(),BOT,LOGICAL_BODY);
            kernel=new BridgeKernel(
                    journal,backend,TaskGraphStore.memory());
            kernel.tick();

            Map<String,Object> initial=kernel.status();
            String firstSession=String.valueOf(
                    initial.get("body_session_epoch"));
            require(context,LOGICAL_BODY.equals(initial.get("body_id")),
                    "logical body id mismatch");
            require(context,"server_fake_player".equals(
                            initial.get("backend_kind")),
                    "wrong backend kind");
            require(context,!firstSession.isBlank(),
                    "first session epoch missing");

            kernel.observe();
            String token=String.valueOf(
                    kernel.claim("mc2a03a-owner").get("token"));
            BlockPos target=start.east(20);
            String arguments=JsonOutput.encode(Map.of(
                    "x",target.getX(),
                    "y",target.getY(),
                    "z",target.getZ(),
                    "allow_terrain_changes",true));
            Map<String,Object> accepted=kernel.submit(
                    token,"mc2a03a-running-goto","goto",arguments);
            String executionId=String.valueOf(
                    accepted.get("execution_id"));
            kernel.tick();
            require(context,"running".equals(
                            kernel.execution(executionId).get("state")),
                    "fixture execution did not enter running");

            CompletableFuture<String> oldSessionQuery=
                    kernel.submitLocalQuery(4,"summary");
            require(context,!oldSessionQuery.isDone(),
                    "queued read query executed before replacement");

            AIPlayerManager.INSTANCE.despawn(world.getServer(),BOT);
            AIPlayerEntity replacement=AIPlayerManager.INSTANCE.spawn(
                    world.getServer(),BOT,world,
                    Vec3d.ofBottomCenter(start.west(3)),
                    0.0F,0.0F,GameMode.SURVIVAL).orElseThrow();
            replacement.teleport(
                    world,start.getX()-2.5D,start.getY(),start.getZ()+.5D,
                    Set.of(),0,0,true);

            kernel.tick();

            Map<String,Object> after=kernel.status();
            String secondSession=String.valueOf(
                    after.get("body_session_epoch"));
            Map<String,Object> interrupted=kernel.execution(executionId);
            require(context,LOGICAL_BODY.equals(after.get("body_id")),
                    "logical body changed during physical replacement");
            require(context,!firstSession.equals(secondSession),
                    "session epoch did not rotate");
            require(context,"outcome_unknown".equals(
                            interrupted.get("state")),
                    "running execution was not fenced outcome_unknown");
            require(context,"body_session_changed".equals(
                            interrupted.get("reason")),
                    "wrong replacement terminal reason: "
                            +interrupted.get("reason"));
            require(context,Boolean.FALSE.equals(after.get("control_active")),
                    "old lease survived physical replacement");
            require(context,Boolean.TRUE.equals(after.get("needs_reconcile")),
                    "replacement did not require deliberate observation");
            require(context,oldSessionQuery.isCompletedExceptionally(),
                    "old-session local query was not cancelled");
            require(context,TaskManager.INSTANCE.getActive(replacement).isEmpty(),
                    "replacement body automatically replayed old mutation");

            long sessionEvents=journal.replay().stream()
                    .filter(frame->"body_session_changed".equals(
                            frame.fields().get("kind")))
                    .count();
            long bindings=journal.replay().stream()
                    .filter(frame->"body_binding".equals(
                            frame.fields().get("kind")))
                    .count();
            require(context,sessionEvents==1,
                    "expected one body_session_changed event, got "
                            +sessionEvents);
            require(context,bindings==2,
                    "expected initial+replacement binding frames, got "
                            +bindings);

            kernel.observe();
            require(context,Boolean.FALSE.equals(
                            kernel.status().get("needs_reconcile")),
                    "observe did not clear replacement reconcile fence");
            String replacementToken=String.valueOf(
                    kernel.claim("mc2a03a-owner-2").get("token"));
            Map<String,Object> deliberate=kernel.submit(
                    replacementToken,
                    "mc2a03a-deliberate-say",
                    "say",
                    JsonOutput.encode(Map.of(
                            "message","mc2a03a-session-reconciled")));
            String deliberateId=String.valueOf(
                    deliberate.get("execution_id"));
            kernel.tick();
            Map<String,Object> terminal=kernel.execution(deliberateId);
            require(context,"completed".equals(terminal.get("state")),
                    "deliberate new-session work did not complete");
            require(context,secondSession.equals(
                            terminal.get("body_session_epoch")),
                    "new execution did not capture replacement session");

            context.complete();
        } catch(Throwable failure) {
            context.throwGameTestException(
                    "session boundary fixture failed: "+failure);
        } finally {
            if(kernel!=null) {
                try { kernel.shutdown(); } catch(Throwable ignored) {}
            }
            try {
                AIPlayerManager.INSTANCE.despawn(world.getServer(),BOT);
            } catch(Throwable ignored) {}
            if(journal!=null) {
                try { journal.close(); } catch(Throwable ignored) {}
            }
            if(journalPath!=null) {
                try { Files.deleteIfExists(journalPath); }
                catch(Throwable ignored) {}
            }
        }
    }

    private static void prepareFlat(ServerWorld world,BlockPos start) {
        for(int dx=-6;dx<=28;dx++)for(int dz=-5;dz<=5;dz++) {
            BlockPos feet=start.add(dx,0,dz);
            world.setBlockState(
                    feet.down(),Blocks.STONE.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(
                    feet,Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(
                    feet.up(),Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
        }
    }

    private static void require(
            TestContext context,boolean condition,String message) {
        if(!condition)context.throwGameTestException(message);
    }
}
