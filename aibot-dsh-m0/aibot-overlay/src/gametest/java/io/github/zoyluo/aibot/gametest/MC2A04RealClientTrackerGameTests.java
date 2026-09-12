package io.github.zoyluo.aibot.gametest;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.BridgeJournal;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.external.realclient.RealClientOpportunityTracker;
import io.github.zoyluo.aibot.external.realclient.RealClientServerTransport;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Durable real-client crosshair opportunity identity without visual/world omniscience. */
public final class MC2A04RealClientTrackerGameTests implements FabricGameTest {
    private static final String BOT="Mc2a04Tracker";

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,
            batchId="mc2a04_real_client_tracker",tickLimit=200)
    public void mc2a04BirthReplayAndTerminalReappearanceUseCorrectIncarnations(
            TestContext context) {
        ServerWorld world=context.getWorld();
        BlockPos start=context.getAbsolutePos(new BlockPos(2,2,2));
        prepare(world,start);
        BlockPos ore=start.east(2);
        world.setBlockState(ore,Blocks.IRON_ORE.getDefaultState(),Block.NOTIFY_ALL);
        Path path=null;
        BridgeJournal journal=null;
        try {
            AIPlayerManager.INSTANCE.despawn(world.getServer(),BOT);
            AIPlayerEntity player=AIPlayerManager.INSTANCE.spawn(
                    world.getServer(),BOT,world,Vec3d.ofBottomCenter(start),
                    0F,0F,GameMode.SURVIVAL).orElseThrow();
            path=Files.createTempFile("mc2a04-real-client-tracker-",".journal");
            journal=new BridgeJournal(path,()->1L);
            lookAt(player,ore.toCenterPos());
            RealClientOpportunityTracker firstTracker=
                    new RealClientOpportunityTracker(journal);
            var sensor=sensor(player,ore,"minecraft:iron_ore");
            var first=firstTracker.observe(player,sensor).orElseThrow();
            require(context,first.expectedItem().equals("minecraft:raw_iron"),
                    "iron expected drop not normalized");
            require(context,journal.replay().stream().anyMatch(frame->
                            "real_client_opportunity_birth".equals(frame.fields().get("kind"))
                                    && first.id().equals(frame.fields().get("opportunity_id"))),
                    "durable real-client birth receipt missing");

            RealClientOpportunityTracker replayed=
                    new RealClientOpportunityTracker(journal);
            require(context,replayed.opportunity(player,first.id()).isPresent(),
                    "birth replay did not restore exact incarnation");
            replayed.markStale("gt-real-client-stale",first,"fixture_terminal");
            RealClientOpportunityTracker afterTerminal=
                    new RealClientOpportunityTracker(journal);
            require(context,afterTerminal.opportunity(player,first.id()).isEmpty(),
                    "terminal receipt failed to close incarnation");

            world.setBlockState(ore,Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(ore,Blocks.IRON_ORE.getDefaultState(),Block.NOTIFY_ALL);
            var second=afterTerminal.observe(player,sensor).orElseThrow();
            require(context,!first.id().equals(second.id()),
                    "terminal same-cell reappearance reused old incarnation id");
            afterTerminal.markStale("gt-real-client-cleanup",second,"fixture_cleanup");
            world.setBlockState(ore,Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
            context.complete();
        } catch(Throwable failure) {
            StringBuilder where=new StringBuilder();
            for(int i=0;i<Math.min(4,failure.getStackTrace().length);i++)
                where.append(" @ ").append(failure.getStackTrace()[i]);
            context.throwGameTestException("real-client tracker fixture failed: "+failure+where);
        } finally {
            try { AIPlayerManager.INSTANCE.despawn(world.getServer(),BOT); }
            catch(Throwable ignored) {}
            if(journal!=null)try { journal.close(); } catch(Throwable ignored) {}
            if(path!=null)try { Files.deleteIfExists(path); } catch(Throwable ignored) {}
        }
    }


    private static void lookAt(AIPlayerEntity player,Vec3d target) {
        Vec3d eye=player.getEyePos();
        double dx=target.x-eye.x,dy=target.y-eye.y,dz=target.z-eye.z;
        double horizontal=Math.sqrt(dx*dx+dz*dz);
        float yaw=(float)(MathHelper.atan2(dz,dx)*180D/Math.PI)-90F;
        float pitch=(float)(-(MathHelper.atan2(dy,horizontal)*180D/Math.PI));
        // fake player 不被 tick:raycast 走 LivingEntity.getYaw(1F)=headYaw,必须与 body yaw 手动同步
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch(pitch);
    }

    private static RealClientServerTransport.SensorSnapshot sensor(
            AIPlayerEntity player,BlockPos ore,String block) {
        return new RealClientServerTransport.SensorSnapshot(
                player.getUuidAsString(),player.getX(),player.getY(),player.getZ(),
                player.getYaw(),player.getPitch(),0,true,
                ore.getX(),ore.getY(),ore.getZ(),block,"WEST",System.currentTimeMillis());
    }

    private static void prepare(ServerWorld world,BlockPos start) {
        for(int dx=-4;dx<=8;dx++)for(int dz=-4;dz<=4;dz++) {
            BlockPos feet=start.add(dx,0,dz);
            world.setBlockState(feet.down(),Blocks.STONE.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(feet,Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(feet.up(),Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
        }
    }

    private static void require(TestContext context,boolean condition,String message) {
        if(!condition)context.throwGameTestException(message);
    }
}
