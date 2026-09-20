package io.github.zoyluo.aibot.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MiningController;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.ExternalBodyAccess;
import io.github.zoyluo.aibot.external.NaturalTreeClassifier;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.perception.PerceptionCollector;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.Set;

/** R2 regression gates for physical break protection, farm observation, world scope and deferred ore. */
public final class MC1CAR2GameTests implements FabricGameTest {
    private static final String BOT = "Mc1caBot";

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR2", tickLimit = 200)
    public void r2PhysicalMiningControllerCannotBypassHome(TestContext context) {
        Fixture f = fixture(context, 6);
        SemanticWorldRegistry.registerHome(f.bot, "r2_gate", 2, 1, 3).persisted().join();
        BlockPos target = f.start.east(1);
        set(f.bot, target, Blocks.OAK_LOG);
        MiningController controller = new MiningController(target, Direction.UP);
        ActionResult result = controller.tick(f.bot.getActionPack());
        require(context, result.isFailed() && result.reason().contains("protected_structure:r2_gate"),
                "direct MiningController bypass must be rejected: " + result);
        require(context, f.bot.getServerWorld().getBlockState(target).isOf(Blocks.OAK_LOG), "protected block changed");
        finish(context, f);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR2", tickLimit = 200)
    public void r2RegisteredFarmThinCropCellIsObservable(TestContext context) {
        Fixture f = fixture(context, 6);
        BlockPos ground = f.start.east(2);
        set(f.bot, ground, Blocks.FARMLAND);
        f.bot.getServerWorld().setBlockState(ground.up(), Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, 7), Block.NOTIFY_ALL);
        SemanticWorldRegistry.registerFarm(f.bot, "thin_crop", 3, "wheat").persisted().join();
        var farm = SemanticWorldRegistry.farm(f.bot, "thin_crop").orElseThrow();
        require(context, SemanticWorldRegistry.farmSurveyCellObservable(f.bot, farm.center(), farm.radius(), Blocks.WHEAT, ground),
                "registered mature crop cell must be observable through cell-LOS even when thin collider face rays miss");
        finish(context, f);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR2", tickLimit = 300)
    public void r2SameSemanticIdSurvivesAcrossDimensions(TestContext context) {
        Fixture f = fixture(context, 6);
        BlockPos overworldCell = f.start.east(1);
        SemanticWorldRegistry.registerHome(f.bot, "same_home", 2, 1, 3).persisted().join();
        set(f.bot, overworldCell, Blocks.OAK_LOG);

        var nether = f.bot.getServer().getWorld(World.NETHER);
        require(context, nether != null, "nether world unavailable");
        BlockPos netherFeet = new BlockPos(8, 80, 8);
        nether.setBlockState(netherFeet.down(), Blocks.NETHERRACK.getDefaultState(), Block.NOTIFY_ALL);
        nether.setBlockState(netherFeet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        nether.setBlockState(netherFeet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        f.bot.teleport(nether, netherFeet.getX() + .5D, netherFeet.getY(), netherFeet.getZ() + .5D, Set.of(), 0, 0, true);
        SemanticWorldRegistry.registerHome(f.bot, "same_home", 2, 1, 3).persisted().join();
        BlockPos netherCell = netherFeet.east(1);
        set(f.bot, netherCell, Blocks.CRIMSON_STEM);
        require(context, SemanticWorldRegistry.protectionReason(f.bot, netherCell) != null, "nether same_home missing");

        var overworld = context.getWorld();
        f.bot.teleport(overworld, f.start.getX() + .5D, f.start.getY(), f.start.getZ() + .5D, Set.of(), 0, 0, true);
        require(context, SemanticWorldRegistry.protectionReason(f.bot, overworldCell) != null,
                "overworld same_home was overwritten by same id in nether");
        finish(context, f);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR2", tickLimit = 200)
    public void r2DiamondOpportunityTransitionsAfterIronPickaxe(TestContext context) {
        Fixture f = fixture(context, 6);
        BlockPos ore = f.start.east(2);
        set(f.bot, ore, Blocks.DIAMOND_ORE);
        require(context, io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(f.bot, ore), "diamond fixture not visible");
        PerceptionCollector.collect(f.bot); // production hook records only after strict observation admits the ore
        JsonArray before = SemanticWorldRegistry.observe(f.bot).getAsJsonArray("resource_opportunities");
        require(context, before.size() == 1 && "BLOCKED".equals(before.get(0).getAsJsonObject().get("status").getAsString()),
                "diamond without iron pick must be BLOCKED: " + before);
        String opportunityId = before.get(0).getAsJsonObject().get("id").getAsString();
        InventoryAction.giveItem(f.bot, new ItemStack(Items.IRON_PICKAXE));
        JsonObject after = SemanticWorldRegistry.observe(f.bot).getAsJsonArray("resource_opportunities").get(0).getAsJsonObject();
        require(context, opportunityId.equals(after.get("id").getAsString()) && "ACTIONABLE".equals(after.get("status").getAsString()),
                "same persisted opportunity must become ACTIONABLE after iron pick: " + after);
        require(context, "minecraft:iron_pickaxe".equals(after.get("required_tool").getAsString()), "wrong required tool");
        finish(context, f);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR2", tickLimit = 200)
    public void r2HomeCaptureProducesMissingOnlyRepairPlan(TestContext context) {
        Fixture f = fixture(context, 6);
        SemanticWorldRegistry.registerHome(f.bot, "repair_home", 2, 1, 3).persisted().join();
        BlockPos a = f.start.east(1), b = a.up();
        set(f.bot, a, Blocks.OAK_LOG); set(f.bot, b, Blocks.OAK_PLANKS);
        SemanticWorldRegistry.captureHome(f.bot, "repair_home").persisted().join();
        set(f.bot, b, Blocks.AIR);
        SemanticWorldRegistry.HomeRepairPlan plan = SemanticWorldRegistry.homeRepairPlan(f.bot, "repair_home");
        require(context, plan.missing() == 1, "expected one missing desired cell: " + plan);
        require(context, plan.wrong() == 0, "missing-only fixture unexpectedly has wrong cells: " + plan);
        require(context, plan.blueprint().placements().size() == 1, "repair blueprint must contain only missing cells");
        finish(context, f);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR2", tickLimit = 200)
    public void r2TreeLeaseSurvivesSelfRemovalOfRootOnlyForOwnerTask(TestContext context) {
        Fixture f = fixture(context, 6);
        BlockPos base = f.start.east(4);
        set(f.bot, base.down(), Blocks.DIRT); set(f.bot, base, Blocks.OAK_LOG); set(f.bot, base.up(), Blocks.OAK_LOG);
        set(f.bot, base.up().north(), Blocks.OAK_LEAVES); set(f.bot, base.up().south(), Blocks.OAK_LEAVES);
        set(f.bot, base.up().east(), Blocks.OAK_LEAVES); set(f.bot, base.up().west(), Blocks.OAK_LEAVES);
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 2);
        ExternalBodyAccess.dispatch(() -> { TaskManager.INSTANCE.assign(f.bot, task, TaskOrigin.of(TaskOrigin.Kind.LLM_TOOL, "r2_tree")); return null; });
        NaturalTreeClassifier.acquireHarvestCluster(f.bot, base);
        set(f.bot, base, Blocks.AIR);
        require(context, NaturalTreeClassifier.isHarvestCandidate(f.bot, base.up()),
                "remaining upper trunk must stay harvestable inside the same gather execution");
        TaskManager.INSTANCE.pauseFor(f.bot, "r2_safety_like_preemption");
        require(context, NaturalTreeClassifier.isHarvestCandidate(f.bot, base.up()),
                "temporary pause/safety preemption must not destroy the owning gather tree lease");
        TaskManager.INSTANCE.resetToIdle(f.bot);
        require(context, !NaturalTreeClassifier.isHarvestCandidate(f.bot, base.up()),
                "tree lease must disappear when the owning gather task is gone");
        finish(context, f);
    }

    private static void set(AIPlayerEntity bot, BlockPos pos, Block block) {
        bot.getServerWorld().setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static Fixture fixture(TestContext context, int east) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -2; dx <= east; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos feet = start.add(dx, 0, dz);
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(world.getServer(), BOT, world, Vec3d.ofBottomCenter(start),
                0.0F, 0.0F, GameMode.SURVIVAL).orElseThrow();
        bot.teleport(world, start.getX() + .5D, start.getY(), start.getZ() + .5D, Set.of(), 0, 0, true);
        ExternalBodyAccess.activateReservation();
        return new Fixture(bot, start);
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) context.throwGameTestException(message);
    }

    private static void finish(TestContext context, Fixture f) {
        TaskManager.INSTANCE.resetToIdle(f.bot);
        AIPlayerManager.INSTANCE.despawn(f.bot.getServer(), BOT);
        context.complete();
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start) {}
}
