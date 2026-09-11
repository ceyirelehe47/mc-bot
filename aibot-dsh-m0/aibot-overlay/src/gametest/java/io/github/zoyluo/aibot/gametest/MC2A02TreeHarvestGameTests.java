package io.github.zoyluo.aibot.gametest;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.ExternalBodyAccess;
import io.github.zoyluo.aibot.external.TreeHarvestWorkset;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.List;
import java.util.Set;

/** MC-2A0.2 required regressions for complete-tree semantics and support provenance. */
public final class MC2A02TreeHarvestGameTests implements FabricGameTest {
    private static final String BOT = "Mc1caBot"; // configured reserved body; distinct batch ids serialize reuse

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a02_quota", tickLimit = 1200)
    public void mc2a02QuotaOneDoesNotTruncateCommittedTree(TestContext context) {
        Fixture f = fixture(context, 14);
        BlockPos base = naturalTree(f, 2, 4);
        InventoryAction.giveItem(f.bot, new ItemStack(Items.IRON_AXE));
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1, "gt-tree-quota-one");
        boolean[] assigned = {false};
        int[] fenceSettle = {0};
        boolean[] sawWorkset = {false};
        boolean[] sawQuotaWhileRunning = {false};
        context.runAtEveryTick(() -> {
            if (!assigned[0]) {
                // The external-body bridge fences every fresh body instance with
                // cancelAll(external_mode_enter) on the first kernel tick after respawn.
                // Assigning gather in the spawn tick lets that fence kill the task, so
                // let it fire first; gather itself must then survive to completion.
                if (++fenceSettle[0] <= 5) return;
                assign(f.bot, task, "gt-tree-quota-one");
                assigned[0] = true;
                return;
            }
            if (task.treeWorksetSnapshot() != null) sawWorkset[0] = true;
            if (GatherQuotaTask.acceptedInventoryCount(f.bot, Items.OAK_LOG) >= 1
                    && task.state() == TaskState.RUNNING) sawQuotaWhileRunning[0] = true;
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                failAndDespawn(context, f, "gather ended " + task.state() + ":" + task.failureReason());
                return;
            }
            if (task.state() != TaskState.COMPLETED) return;
            require(context, sawWorkset[0], "natural tree never acquired a TreeHarvestWorkset");
            require(context, sawQuotaWhileRunning[0], "quota=1 must become true before current tree transaction completes");
            for (int i = 0; i < 4; i++) {
                require(context, !f.world.getBlockState(base.up(i)).isOf(Blocks.OAK_LOG),
                        "committed log left floating at " + base.up(i));
            }
            require(context, GatherQuotaTask.acceptedInventoryCount(f.bot, Items.OAK_LOG) >= 1,
                    "bounded gather postcondition missing");
            finish(context, f);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a02_frozen", tickLimit = 120)
    public void mc2a02WorksetNeverAbsorbsNewOrNeighbourTreeLogs(TestContext context) {
        Fixture f = fixture(context, 18);
        BlockPos a = naturalTree(f, 2, 4);
        BlockPos b = naturalTree(f, 10, 4);
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1, "gt-frozen-tree");
        assign(f.bot, task, "gt-frozen-tree");
        TreeHarvestWorkset workset = TreeHarvestWorkset.acquire(f.bot, a, "gt-frozen-tree").orElseThrow();
        Set<BlockPos> before = workset.snapshot().candidates();
        BlockPos addedAfterProof = a.up(3).east();
        set(f.world, addedAfterProof, Blocks.OAK_LOG);
        workset.reconcile(f.bot);
        Set<BlockPos> after = workset.snapshot().candidates();
        require(context, before.equals(after), "candidate set changed after acquisition");
        require(context, !after.contains(addedAfterProof), "newly attached log was silently absorbed");
        require(context, after.stream().noneMatch(p -> p.getSquaredDistance(b) < 4),
                "nearby second tree leaked into frozen workset");
        finish(context, f);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a02_pause", tickLimit = 120)
    public void mc2a02SafetyPauseResumeKeepsSameTreeWorkset(TestContext context) {
        Fixture f = fixture(context, 14);
        naturalTree(f, 2, 4);
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1, "gt-pause-owner");
        assign(f.bot, task, "gt-pause-owner");
        // One synchronous task step acquires the direct-reach root before any server-tick race.
        task.tick(f.bot);
        TreeHarvestWorkset.Snapshot before = task.treeWorksetSnapshot();
        require(context, before != null, "tree workset not acquired on first direct harvest");
        TaskManager.INSTANCE.pauseFor(f.bot, "gt_safety_like_preemption");
        require(context, task.state() == TaskState.PAUSED, "gather did not pause");
        TreeHarvestWorkset.Snapshot paused = task.treeWorksetSnapshot();
        require(context, paused != null && paused.treeId().equals(before.treeId())
                        && paused.ownerExecution().equals(before.ownerExecution())
                        && paused.candidates().equals(before.candidates()),
                "pause lost or rebuilt workset identity");
        TaskManager.INSTANCE.resumeFromPause(f.bot);
        require(context, task.state() == TaskState.RUNNING, "gather did not resume");
        TreeHarvestWorkset.Snapshot resumed = task.treeWorksetSnapshot();
        require(context, resumed != null && resumed.treeId().equals(before.treeId())
                        && resumed.ownerExecution().equals("gt-pause-owner")
                        && resumed.candidates().equals(before.candidates()),
                "resume did not preserve exact workset/owner");
        finish(context, f);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a02_support", tickLimit = 600)
    public void mc2a02TemporaryTreeSupportsAreOwnedAndReverseCleaned(TestContext context) {
        Fixture f = fixture(context, 14);
        BlockPos base = naturalTree(f, 1, 8); // bot starts immediately west of the access column
        InventoryAction.giveItem(f.bot, new ItemStack(Items.DIRT, 16));
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1, "gt-support-owner");
        assign(f.bot, task, "gt-support-owner");
        TreeHarvestWorkset workset = TreeHarvestWorkset.acquire(f.bot, base, "gt-support-owner").orElseThrow();
        TaskManager.INSTANCE.pauseFor(f.bot, "hold_parent_while_driving_workset");
        BlockPos top = base.up(7);
        for (int i = 0; i < 7; i++) set(f.world, base.up(i), Blocks.AIR);
        workset.reconcile(f.bot);
        f.bot.teleport(f.world, base.getX() + .5D, base.getY(), base.getZ() + .5D, Set.of(), 0, 0, true);
        f.bot.setOnGround(true);
        BlockPos foreign = f.start.south(3);
        set(f.world, foreign, Blocks.COBBLESTONE);
        boolean[] accessReady = {false};
        context.runAtEveryTick(() -> {
            if (!accessReady[0]) {
                TreeHarvestWorkset.StepResult access = workset.tickAccess(f.bot, top);
                if (access.state() == TreeHarvestWorkset.StepState.BLOCKED
                        || access.state() == TreeHarvestWorkset.StepState.DEBT) {
                    failAndDespawn(context, f, "temporary access failed: " + access.reason());
                    return;
                }
                if (access.state() != TreeHarvestWorkset.StepState.READY) return;
                accessReady[0] = true;
                List<TreeHarvestWorkset.TemporarySupport> placed = workset.snapshot().supports().stream()
                        .filter(s -> s.state() == TreeHarvestWorkset.SupportState.PLACED).toList();
                require(context, !placed.isEmpty(), "tall-tree access reached target without exercising temporary support");
                require(context, placed.stream().allMatch(s -> s.ownerExecution().equals("gt-support-owner")
                                && s.purpose().equals("TREE_ACCESS")),
                        "support provenance/owner missing: " + placed);
                set(f.world, top, Blocks.AIR);
                workset.noteHarvested(f.bot, top);
                return;
            }
            TreeHarvestWorkset.StepResult cleanup = workset.tickCleanup(f.bot);
            if (cleanup.state() == TreeHarvestWorkset.StepState.DEBT) {
                failAndDespawn(context, f, "reverse cleanup failed: " + cleanup.reason());
                return;
            }
            if (cleanup.state() != TreeHarvestWorkset.StepState.COMPLETE) return;
            workset.resolvePickupLoss(top, "fixture_manual_break_no_drop");
            require(context, workset.treeComplete(), "TREE_COMPLETE must wait for supports + pickup reconciliation: " + workset.snapshot());
            require(context, f.world.getBlockState(foreign).isOf(Blocks.COBBLESTONE),
                    "foreign/persistent support was wrongly removed");
            require(context, workset.snapshot().supports().stream().noneMatch(s -> s.state() == TreeHarvestWorkset.SupportState.PLACED),
                    "owned support ledger still has unresolved PLACED entries");
            finish(context, f);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a02_debt", tickLimit = 500)
    public void mc2a02ModifiedOwnedSupportBecomesCleanupDebtAndIsNotRemoved(TestContext context) {
        Fixture f = fixture(context, 14);
        BlockPos base = naturalTree(f, 1, 8);
        InventoryAction.giveItem(f.bot, new ItemStack(Items.DIRT, 16));
        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1, "gt-debt-owner");
        assign(f.bot, task, "gt-debt-owner");
        TreeHarvestWorkset workset = TreeHarvestWorkset.acquire(f.bot, base, "gt-debt-owner").orElseThrow();
        TaskManager.INSTANCE.pauseFor(f.bot, "hold_parent_while_driving_workset");
        BlockPos top = base.up(7);
        for (int i = 0; i < 7; i++) set(f.world, base.up(i), Blocks.AIR);
        workset.reconcile(f.bot);
        f.bot.teleport(f.world, base.getX() + .5D, base.getY(), base.getZ() + .5D, Set.of(), 0, 0, true);
        f.bot.setOnGround(true);
        context.runAtEveryTick(() -> {
            if (!workset.hasTemporarySupports()) {
                TreeHarvestWorkset.StepResult access = workset.tickAccess(f.bot, top);
                if (access.state() == TreeHarvestWorkset.StepState.BLOCKED
                        || access.state() == TreeHarvestWorkset.StepState.DEBT) {
                    failAndDespawn(context, f, "access failed before debt fixture: " + access.reason());
                }
                return;
            }
            TreeHarvestWorkset.TemporarySupport own = workset.snapshot().supports().stream()
                    .filter(s -> s.state() == TreeHarvestWorkset.SupportState.PLACED)
                    .reduce((a, b) -> b).orElseThrow();
            set(f.world, own.pos(), Blocks.GOLD_BLOCK); // external mutation destroys exact provenance match
            TreeHarvestWorkset.StepResult cleanup = workset.tickCleanup(f.bot);
            require(context, cleanup.state() == TreeHarvestWorkset.StepState.DEBT,
                    "modified support must yield explicit debt: " + cleanup);
            require(context, cleanup.reason().contains("support_conflict"),
                    "typed conflict reason missing: " + cleanup.reason());
            require(context, f.world.getBlockState(own.pos()).isOf(Blocks.GOLD_BLOCK),
                    "foreign replacement must never be auto-removed");
            require(context, !workset.treeComplete(), "cleanup debt must forbid TREE_COMPLETE");
            finish(context, f);
        });
    }

    private record Fixture(ServerWorld world, AIPlayerEntity bot, BlockPos start) {}

    private static Fixture fixture(TestContext context, int east) {
        ServerWorld world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -5; dx <= east; dx++) for (int dz = -6; dz <= 6; dz++) {
            BlockPos feet = start.add(dx, 0, dz);
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            for (int dy = 0; dy <= 14; dy++) world.setBlockState(feet.up(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        AIPlayerManager.INSTANCE.despawn(world.getServer(), BOT);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(world.getServer(), BOT, world,
                Vec3d.ofBottomCenter(start), 0.0F, 0.0F, GameMode.SURVIVAL).orElseThrow();
        bot.teleport(world, start.getX() + .5D, start.getY(), start.getZ() + .5D, Set.of(), 0, 0, true);
        ExternalBodyAccess.activateReservation();
        return new Fixture(world, bot, start);
    }

    private static BlockPos naturalTree(Fixture f, int east, int height) {
        BlockPos base = f.start.east(east);
        set(f.world, base.down(), Blocks.DIRT);
        for (int i = 0; i < height; i++) set(f.world, base.up(i), Blocks.OAK_LOG);
        BlockPos crown = base.up(height - 1);
        set(f.world, crown.north(), Blocks.OAK_LEAVES);
        set(f.world, crown.south(), Blocks.OAK_LEAVES);
        set(f.world, crown.east(), Blocks.OAK_LEAVES);
        set(f.world, crown.west(), Blocks.OAK_LEAVES);
        return base;
    }

    private static void assign(AIPlayerEntity bot, GatherQuotaTask task, String reason) {
        ExternalBodyAccess.dispatch(() -> {
            TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.LLM_TOOL, reason));
            return null;
        });
    }

    private static void set(ServerWorld world, BlockPos pos, Block block) {
        world.setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) context.throwGameTestException(message);
    }

    private static void failAndDespawn(TestContext context, Fixture f, String message) {
        TaskManager.INSTANCE.resetToIdle(f.bot);
        AIPlayerManager.INSTANCE.despawn(f.bot.getServer(), BOT);
        context.throwGameTestException(message);
    }

    private static void finish(TestContext context, Fixture f) {
        TaskManager.INSTANCE.resetToIdle(f.bot);
        AIPlayerManager.INSTANCE.despawn(f.bot.getServer(), BOT);
        context.complete();
    }
}
