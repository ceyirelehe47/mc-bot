package io.github.zoyluo.aibot.gametest;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.realclient.RealClientOmnidirectionalPerception;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;

/** MC-2A0.8 server-authoritative all-direction perception without through-wall current state. */
public final class MC2A08OmnidirectionalPerceptionGameTests
        implements FabricGameTest {
    private static final String BOT="Mc2a08Omni";

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,
            batchId="mc2a08_omnidirectional",tickLimit=200)
    public void mc2a08OmniSeesUnobstructedItemBehind(TestContext context) {
        scenario(context,(world,player,start,spawned)->{
            ItemEntity item=spawnItem(world,start.north(4),spawned);
            var perception=perception(
                    RealClientOmnidirectionalPerception.Mode.OMNI_SEMANTIC,20);
            perception.tickAt(player,"session-a",0L);
            var observation=perception.snapshot(player).entities().stream()
                    .filter(entity->entity.objectId().equals(item.getUuidAsString()))
                    .findFirst().orElseThrow();
            require(context,observation.lineOfSight(),
                    "behind item must be current-visible in omni mode");
            require(context,"BACK".equals(observation.sector()),
                    "behind item must be classified in BACK sector");
            require(context,"CURRENT_VISIBLE".equals(observation.knowledge()),
                    "omni observation must be current visible");
        });
    }

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,
            batchId="mc2a08_omnidirectional",tickLimit=200)
    public void mc2a08StrictFovExcludesUnobstructedItemBehind(TestContext context) {
        scenario(context,(world,player,start,spawned)->{
            ItemEntity item=spawnItem(world,start.north(4),spawned);
            var perception=perception(
                    RealClientOmnidirectionalPerception.Mode.STRICT_PLAYER_FOV,20);
            perception.tickAt(player,"session-a",0L);
            require(context,perception.snapshot(player).entities().stream()
                            .noneMatch(entity->entity.objectId()
                                    .equals(item.getUuidAsString())),
                    "strict FOV must not report a never-seen object behind the player");
        });
    }

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,
            batchId="mc2a08_omnidirectional",tickLimit=200)
    public void mc2a08WallPreventsCurrentEntityVisibility(TestContext context) {
        scenario(context,(world,player,start,spawned)->{
            ItemEntity item=spawnItem(world,start.east(4),spawned);
            wall(world,start.east(2),true);
            var perception=perception(
                    RealClientOmnidirectionalPerception.Mode.OMNI_SEMANTIC,20);
            perception.tickAt(player,"session-a",0L);
            require(context,perception.snapshot(player).entities().stream()
                            .noneMatch(entity->entity.objectId()
                                    .equals(item.getUuidAsString())),
                    "wall-hidden entity must not leak as current state");
        });
    }

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,
            batchId="mc2a08_omnidirectional",tickLimit=200)
    public void mc2a08OccludedEntityBecomesLastKnownThenExpires(TestContext context) {
        scenario(context,(world,player,start,spawned)->{
            ItemEntity item=spawnItem(world,start.east(4),spawned);
            var perception=perception(
                    RealClientOmnidirectionalPerception.Mode.OMNI_SEMANTIC,20);
            perception.tickAt(player,"session-a",0L);
            require(context,perception.snapshot(player).entities().stream()
                            .anyMatch(entity->entity.objectId()
                                    .equals(item.getUuidAsString())
                                    && entity.lineOfSight()),
                    "initial item must be visible");
            wall(world,start.east(2),true);
            perception.tickAt(player,"session-a",2L);
            var remembered=perception.snapshot(player).entities().stream()
                    .filter(entity->entity.objectId().equals(item.getUuidAsString()))
                    .findFirst().orElseThrow();
            require(context,!remembered.lineOfSight(),
                    "occluded entity must not remain line-of-sight");
            require(context,"LAST_KNOWN".equals(remembered.knowledge()),
                    "occluded entity must be explicit last-known memory");
            perception.tickAt(player,"session-a",25L);
            require(context,perception.snapshot(player).entities().stream()
                            .noneMatch(entity->entity.objectId()
                                    .equals(item.getUuidAsString())),
                    "last-known memory must expire at the configured bound");
        });
    }

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,
            batchId="mc2a08_omnidirectional",tickLimit=200)
    public void mc2a08VisibleBlocksClassifiedAndHiddenOreExcluded(TestContext context) {
        scenario(context,(world,player,start,spawned)->{
            BlockPos barrel=start.south(3);
            BlockPos iron=start.east(3);
            BlockPos magma=start.west(3);
            BlockPos hiddenDiamond=start.north(4);
            world.setBlockState(barrel,Blocks.BARREL.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(iron,Blocks.IRON_ORE.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(magma,Blocks.MAGMA_BLOCK.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(
                    hiddenDiamond,Blocks.DIAMOND_ORE.getDefaultState(),Block.NOTIFY_ALL);
            wall(world,start.north(2),false);

            var perception=perception(
                    RealClientOmnidirectionalPerception.Mode.OMNI_SEMANTIC,20);
            perception.tickAt(player,"session-a",0L);
            var blocks=perception.snapshot(player).blocks();
            require(context,blocks.stream().anyMatch(block->
                            "minecraft:barrel".equals(block.blockId())
                                    && "INTERACTABLE".equals(block.category())),
                    "visible barrel must be interactable awareness");
            require(context,blocks.stream().anyMatch(block->
                            "minecraft:iron_ore".equals(block.blockId())
                                    && "RESOURCE_SURFACE".equals(block.category())),
                    "visible ore surface must be awareness-only resource");
            require(context,blocks.stream().anyMatch(block->
                            "minecraft:magma_block".equals(block.blockId())
                                    && "HAZARD".equals(block.category())),
                    "visible magma must be hazard awareness");
            require(context,blocks.stream().noneMatch(block->
                            "minecraft:diamond_ore".equals(block.blockId())),
                    "wall-hidden ore must never leak through omni perception");
        });
    }

    @GameTest(templateName=FabricGameTest.EMPTY_STRUCTURE,
            batchId="mc2a08_omnidirectional",tickLimit=200)
    public void mc2a08GameSessionRotationClearsLastKnownMemory(TestContext context) {
        scenario(context,(world,player,start,spawned)->{
            ItemEntity item=spawnItem(world,start.south(4),spawned);
            var perception=perception(
                    RealClientOmnidirectionalPerception.Mode.OMNI_SEMANTIC,200);
            perception.tickAt(player,"session-a",0L);
            require(context,!perception.snapshot(player).entities().isEmpty(),
                    "session-a must contain visible item");
            item.discard();
            perception.tickAt(player,"session-b",1L);
            require(context,perception.snapshot(player).entities().isEmpty(),
                    "new game-session incarnation must clear old visual memory");
        });
    }

    private interface Scenario {
        void run(ServerWorld world,AIPlayerEntity player,BlockPos start,
                List<ItemEntity> spawned)throws Throwable;
    }

    private static void scenario(TestContext context,Scenario scenario) {
        ServerWorld world=context.getWorld();
        BlockPos start=context.getAbsolutePos(new BlockPos(2,2,2));
        prepare(world,start);
        List<ItemEntity> spawned=new ArrayList<>();
        try {
            AIPlayerManager.INSTANCE.despawn(world.getServer(),BOT);
            AIPlayerEntity player=AIPlayerManager.INSTANCE.spawn(
                    world.getServer(),BOT,world,Vec3d.ofBottomCenter(start),
                    0F,0F,GameMode.SURVIVAL).orElseThrow();
            player.setYaw(0F);
            player.setHeadYaw(0F);
            player.setBodyYaw(0F);
            player.setPitch(0F);
            scenario.run(world,player,start,spawned);
            context.complete();
        } catch(Throwable failure) {
            StringBuilder where=new StringBuilder();
            for(int i=0;i<Math.min(4,failure.getStackTrace().length);i++)
                where.append(" @ ").append(failure.getStackTrace()[i]);
            context.throwGameTestException(
                    "omnidirectional perception fixture failed: "+failure+where);
        } finally {
            for(ItemEntity entity:spawned)
                try { entity.discard(); } catch(Throwable ignored) {}
            try { AIPlayerManager.INSTANCE.despawn(world.getServer(),BOT); }
            catch(Throwable ignored) {}
        }
    }

    private static RealClientOmnidirectionalPerception perception(
            RealClientOmnidirectionalPerception.Mode mode,int memoryTicks) {
        return new RealClientOmnidirectionalPerception(
                new RealClientOmnidirectionalPerception.Config(
                        mode,12,8,memoryTicks,1,1));
    }

    private static ItemEntity spawnItem(
            ServerWorld world,BlockPos pos,List<ItemEntity> spawned) {
        ItemEntity item=new ItemEntity(
                world,pos.getX()+.5D,pos.getY()+1D,pos.getZ()+.5D,
                new ItemStack(Items.COBBLESTONE,3));
        item.setNoGravity(true);
        if(!world.spawnEntity(item))
            throw new IllegalStateException("item_spawn_rejected");
        spawned.add(item);
        return item;
    }

    private static void wall(ServerWorld world,BlockPos center,boolean eastWest) {
        for(int horizontal=-1;horizontal<=1;horizontal++) {
            for(int y=0;y<=3;y++) {
                BlockPos pos=eastWest
                        ?center.add(0,y,horizontal)
                        :center.add(horizontal,y,0);
                world.setBlockState(
                        pos,Blocks.STONE.getDefaultState(),Block.NOTIFY_ALL);
            }
        }
    }

    private static void prepare(ServerWorld world,BlockPos start) {
        for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++) {
            BlockPos feet=start.add(dx,0,dz);
            world.setBlockState(
                    feet.down(),Blocks.STONE.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(feet,Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(feet.up(),Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
            world.setBlockState(feet.up(2),Blocks.AIR.getDefaultState(),Block.NOTIFY_ALL);
        }
    }

    private static void require(
            TestContext context,boolean condition,String message) {
        if(!condition)context.throwGameTestException(message);
    }
}
