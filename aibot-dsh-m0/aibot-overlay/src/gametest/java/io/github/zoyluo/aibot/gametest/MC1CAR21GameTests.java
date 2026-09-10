package io.github.zoyluo.aibot.gametest;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.ExternalBodyAccess;
import io.github.zoyluo.aibot.external.KnownResourceTask;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.function.Supplier;

/**
 * R2.1 reliability repair gates: physical HOME placement, narrow natural-drift equivalence,
 * opportunity lifecycle states, and the reserved farm mutation mask.
 *
 * The HOME fixtures reproduce LIVE-R2-7: a doorless enclosed cabin whose missing cells sit on the
 * FAR facade while the bot waits OUTSIDE — from the starting eye position every candidate stand
 * (inside the cabin and behind the far wall alike) is occluded by the cabin walls, which used to
 * dead-lock BuildTask in a silent loop until target_timeout with zero placement.
 */
public final class MC1CAR21GameTests implements FabricGameTest {
    private static final String BOT = "Mc1caBot";

    /** 1: one missing far-facade cell is physically re-placed (real equip/place, material consumed). */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 400)
    public void r21HomeRepairPlacesOneMissingBlockPhysically(TestContext context) {
        Fixture f = cabin(context, "r21_one");
        BlockPos hole = f.farWallCenter();
        SemanticWorldRegistry.captureHome(f.bot, "r21_one").persisted().join();
        set(f.bot, hole, Blocks.AIR);
        givePlanks(f.bot, 16);
        int planksBefore = InventoryAction.countItem(f.bot, Items.OAK_PLANKS);

        driveRepair(context, f, startRepair(context, f, "r21_one"), () -> {
            boolean restored = f.bot.getServerWorld().getBlockState(hole).isOf(Blocks.OAK_PLANKS);
            int planksAfter = InventoryAction.countItem(f.bot, Items.OAK_PLANKS);
            return restored && planksAfter == planksBefore - 1
                    ? null
                    : "hole=" + f.bot.getServerWorld().getBlockState(hole).getBlock()
                            + " planks " + planksBefore + "->" + planksAfter;
        });
    }

    /** 2: three missing far-facade cells are all physically re-placed, no target_timeout skip. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 500)
    public void r21HomeRepairPlacesMultipleMissingBlocksPhysically(TestContext context) {
        Fixture f = cabin(context, "r21_three");
        BlockPos a = f.farWallCenter();
        BlockPos b = a.north();
        BlockPos c = a.south();
        SemanticWorldRegistry.captureHome(f.bot, "r21_three").persisted().join();
        set(f.bot, a, Blocks.AIR);
        set(f.bot, b, Blocks.AIR);
        set(f.bot, c, Blocks.AIR);
        givePlanks(f.bot, 16);

        driveRepair(context, f, startRepair(context, f, "r21_three"), () -> {
            boolean restored = f.bot.getServerWorld().getBlockState(a).isOf(Blocks.OAK_PLANKS)
                    && f.bot.getServerWorld().getBlockState(b).isOf(Blocks.OAK_PLANKS)
                    && f.bot.getServerWorld().getBlockState(c).isOf(Blocks.OAK_PLANKS);
            return restored ? null : "not all far-facade cells restored";
        });
    }

    /** 3: a wrong non-equivalent cell keeps the repair plan atomic-fail (wrong > 0, no placement). */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 200)
    public void r21HomeRepairStillRejectsWrongNonEquivalentCellAtomically(TestContext context) {
        Fixture f = cabin(context, "r21_wrong");
        BlockPos wrong = f.farWallCenter().north(); // still a wall cell of the far facade
        SemanticWorldRegistry.captureHome(f.bot, "r21_wrong").persisted().join();
        set(f.bot, wrong, Blocks.STONE); // occupied by a different, non-equivalent block
        SemanticWorldRegistry.HomeRepairPlan plan = SemanticWorldRegistry.homeRepairPlan(f.bot, "r21_wrong");
        require(context, plan.wrong() >= 1, "non-equivalent occupant must count as wrong, got " + plan);
        require(context, plan.blueprint().placements().isEmpty(),
                "conflicting cells must never enter the repair blueprint: " + plan);
        require(context, f.bot.getServerWorld().getBlockState(wrong).isOf(Blocks.STONE),
                "wrong cell must stay untouched (fail closed, no auto-removal)");
        finish(context, f);
    }

    /**
     * 4: natural dirt->grass drift on a baseline cell is equivalent (no wrong/conflict), a real
     * missing cell elsewhere still repairs, and the drifted grass cell is never force-reverted.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 300)
    public void r21HomeDirtGrassNaturalDriftIsEquivalentButMissingStillRepairable(TestContext context) {
        Fixture f = cabin(context, "r21_grass");
        BlockPos hole = f.farWallCenter();
        BlockPos dirtCell = f.cabinFloorCenter(); // captured as dirt inside the baseline
        set(f.bot, dirtCell, Blocks.DIRT);
        SemanticWorldRegistry.captureHome(f.bot, "r21_grass").persisted().join();
        set(f.bot, dirtCell, Blocks.GRASS_BLOCK); // natural grass spread
        set(f.bot, hole, Blocks.AIR); // and a genuinely missing structural cell
        givePlanks(f.bot, 16);

        SemanticWorldRegistry.HomeRepairPlan plan = SemanticWorldRegistry.homeRepairPlan(f.bot, "r21_grass");
        require(context, plan.wrong() == 0, "grass drift must not be a wrong/conflict cell: " + plan);
        require(context, plan.missing() == 1, "only the real hole may be repairable: " + plan);

        driveRepair(context, f, startRepair(context, f, "r21_grass"), () -> {
            boolean holeRestored = f.bot.getServerWorld().getBlockState(hole).isOf(Blocks.OAK_PLANKS);
            boolean grassPreserved = f.bot.getServerWorld().getBlockState(dirtCell).isOf(Blocks.GRASS_BLOCK);
            return holeRestored && grassPreserved ? null
                    : "hole restored=" + holeRestored + " grass preserved=" + grassPreserved;
        });
    }

    /** 5: an unrecorded extra block survives repair untouched. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 300)
    public void r21HomeRepairNeverDeletesExtraBlock(TestContext context) {
        Fixture f = cabin(context, "r21_extra");
        BlockPos hole = f.farWallCenter();
        BlockPos extra = f.cabinFloorCenter(); // inside the cabin, never part of the baseline
        SemanticWorldRegistry.captureHome(f.bot, "r21_extra").persisted().join();
        set(f.bot, hole, Blocks.AIR);
        set(f.bot, extra, Blocks.CHEST);
        givePlanks(f.bot, 16);
        driveRepair(context, f, startRepair(context, f, "r21_extra"), () -> {
            boolean restored = f.bot.getServerWorld().getBlockState(hole).isOf(Blocks.OAK_PLANKS);
            boolean extraIntact = f.bot.getServerWorld().getBlockState(extra).isOf(Blocks.CHEST);
            return restored && extraIntact ? null
                    : "hole restored=" + restored + " extra intact=" + extraIntact;
        });
    }

    /**
     * 1b (adversarial geometry, semantic): the missing cell sits at the end of an L-shaped corridor
     * buried in a solid stone shell. From the bot's corner the target cell is NOT observable and
     * every candidate stand (in the far corridor arm) is occluded from the current eye, so the old
     * "stand must be visible from the CURRENT eye" rule returned null forever — the silent
     * LIVE-R2-7 deadlock class. R2.1 selects work poses by what the bot can see AFTER arriving:
     * the corridor stand must qualify through canObserveCellFrom(standEye, hole) and the repair
     * task must actually start pathing there. (Fake players are not world-ticked in GameTest, so
     * the walk itself is proven on the live server by LIVE-R21-1.)
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 300)
    public void r21HomeRepairWorkPoseSurvivesOccludedCorner(TestContext context) {
        Fixture f = corridor(context);
        AIPlayerEntity bot = f.bot;
        BlockPos hole = f.farWallCenter();
        BlockPos corridorStand = hole.south().down(); // north-arm floor cell right below the hole
        require(context, !io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveCell(bot, hole),
                "fixture must reproduce the occluded-target precondition");
        require(context, !io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveCell(bot, corridorStand)
                        && !io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, corridorStand.down()),
                "fixture must reproduce the occluded-stand precondition (old rule dead-locked here)");
        Vec3d standEye = new Vec3d(corridorStand.getX() + 0.5D, corridorStand.getY() + 1.62D, corridorStand.getZ() + 0.5D);
        require(context, io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), corridorStand),
                "corridor stand must be standable");
        require(context, io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveCellFrom(bot, standEye, hole),
                "R2.1 work-pose rule must prove the hole observable from the candidate stand eye");

        SemanticWorldRegistry.captureHome(bot, "r21_corner").persisted().join();
        set(bot, hole, Blocks.AIR);
        givePlanks(bot, 16);
        BuildTask task = startRepair(context, f, "r21_corner");
        // The repaired task must NOT dead-lock silently: nearbyStand resolves and a path executor
        // is created toward the far arm (it may later stall only because fake players are not
        // world-ticked in GameTest, which is fine — creation itself proves work-pose selection).
        boolean[] pathed = {false};
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) task.tick(bot);
            if (!bot.getActionPack().isPathExecutorIdle()) pathed[0] = true;
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                String message = "repair task ended " + task.state() + " reason=" + task.failureReason();
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), BOT);
                throwGameTest(context, message);
            }
            if (task.state() == TaskState.COMPLETED || task.elapsedTicks() > 200) {
                require(context, pathed[0], "work pose was never resolved: no path executor ever created");
                finish(context, f);
            }
        });
    }

    /**
     * 6: a proven no-work-pose failure demotes the opportunity to UNREACHABLE (typed reason), a
     * repeated unchanged observation never re-arms ACTIONABLE, and the bounded revalidation gate
     * only opens after meaningful movement or a game-time cooldown.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 200)
    public void r21OpportunityBecomesUnreachableAfterNoWorkPose(TestContext context) {
        Fixture f = cabin(context, "r21_unreach");
        AIPlayerEntity bot = f.bot;
        BlockPos ore = f.farWallCenter().up(5); // free-standing ABOVE the HOME box (bounds top y+5): visible, never BreakPolicy-protected
        set(bot, ore, Blocks.IRON_ORE);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        io.github.zoyluo.aibot.perception.PerceptionCollector.collect(bot);
        String id = opportunityAt(context, bot, ore, "ACTIONABLE");

        SemanticWorldRegistry.markOpportunityUnreachable(bot, id, "no_reachable_work_pose");
        for (int i = 0; i < 3; i++) {
            io.github.zoyluo.aibot.perception.PerceptionCollector.collect(bot); // unchanged observation
        }
        var entry = opportunityEntry(bot, id);
        require(context, entry != null && "UNREACHABLE".equals(entry.get("status").getAsString())
                        && entry.get("blocked_reason").getAsString().contains("no_reachable_work_pose"),
                "unchanged observation must keep UNREACHABLE with typed reason: " + entry);

        var spec = SemanticWorldRegistry.opportunity(bot, id).orElseThrow();
        require(context, !SemanticWorldRegistry.opportunityRevalidationReady(bot, spec),
                "standing still right after the verdict must not revalidate");

        // Meaningful movement re-opens the revalidation gate (bounded deterministic revalidation).
        BlockPos displaced = f.start.add(8, 0, 4); // > REVALIDATE_MOVE_DISTANCE from the verdict position
        bot.teleport(bot.getServerWorld(), displaced.getX() + .5D, displaced.getY(), displaced.getZ() + .5D,
                Set.of(), 0, 0, true);
        var moved = SemanticWorldRegistry.opportunity(bot, id).orElseThrow();
        require(context, SemanticWorldRegistry.opportunityRevalidationReady(bot, moved),
                "moving away from the verdict position must re-open revalidation");
        require(context, SemanticWorldRegistry.reactivateOpportunity(bot, id)
                        .filter(reactivated -> "ACTIONABLE".equals(reactivated.status())).isPresent(),
                "reactivation after movement must restore ACTIONABLE");
        finish(context, f);
    }

    /**
     * 7: MINED_PENDING_PICKUP is a persisted recovery obligation: a different block appearing on
     * the ore cell must not delete it, and repeated observation must not overwrite the state.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 200)
    public void r21MinedWithoutPickupPersistsPendingRecovery(TestContext context) {
        Fixture f = cabin(context, "r21_pend");
        AIPlayerEntity bot = f.bot;
        BlockPos ore = f.farWallCenter().up(5); // above the HOME bounds: visible, unprotected
        set(bot, ore, Blocks.IRON_ORE);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        io.github.zoyluo.aibot.perception.PerceptionCollector.collect(bot);
        String id = opportunityAt(context, bot, ore, "ACTIONABLE");

        SemanticWorldRegistry.markOpportunityPendingPickup(bot, id); // ore proven broken, drop not collected
        set(bot, ore, Blocks.STONE); // something else now occupies the cell
        io.github.zoyluo.aibot.perception.PerceptionCollector.collect(bot);
        io.github.zoyluo.aibot.perception.PerceptionCollector.collect(bot);
        var entry = opportunityEntry(bot, id);
        require(context, entry != null && "MINED_PENDING_PICKUP".equals(entry.get("status").getAsString()),
                "pending pickup must survive a foreign occupant and re-observation: " + entry);
        finish(context, f);
    }

    /**
     * 8: pickup recovery never re-mines. With the ore already gone and its drop present, a
     * recovery KnownResourceTask demands the inventory-delta postcondition and only then consumes
     * the opportunity. (STRICT_SURVIVAL denies FORCED_PICKUP and GameTest fake players are never
     * world-ticked, so vanilla collision pickup cannot fire; the drop is delivered directly at
     * t=30 — what is under test is the recovery state machine's reaction to the inventory delta.
     * The physical pickup chain runs on the live server in LIVE-R21-4.)
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 300)
    public void r21PendingPickupCanRecoverWithoutRemining(TestContext context) {
        Fixture f = cabin(context, "r21_recov");
        AIPlayerEntity bot = f.bot;
        BlockPos ore = f.farWallCenter().up(5); // above the HOME bounds: visible, unprotected
        set(bot, ore, Blocks.IRON_ORE);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        io.github.zoyluo.aibot.perception.PerceptionCollector.collect(bot);
        String id = opportunityAt(context, bot, ore, "ACTIONABLE");
        var spec = SemanticWorldRegistry.opportunity(bot, id).orElseThrow();

        SemanticWorldRegistry.markOpportunityPendingPickup(bot, id);
        set(bot, ore, Blocks.AIR); // the ore is gone (break already proven)
        var dropItem = io.github.zoyluo.aibot.action.HarvestCore.expectedDropsFor(Blocks.IRON_ORE).iterator().next();
        var world = bot.getServerWorld();
        BlockPos dropPos = bot.getBlockPos(); // at the bot's own column: unobstructed LOS
        var drop = new net.minecraft.entity.ItemEntity(world,
                dropPos.getX() + 0.5D, dropPos.getY() + 0.3D, dropPos.getZ() + 0.5D,
                new ItemStack(dropItem, 1));
        drop.setVelocity(0.0D, 0.0D, 0.0D); // neutralize the random spawn bounce
        drop.setPickupDelay(0);
        drop.setNoGravity(true); // pin at the bot's feet cell: the fake player cannot chase drops
        world.spawnEntity(drop);

        KnownResourceTask task = new KnownResourceTask(spec, true);
        task.start(bot);
        int[] dt = {0};
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
                if (++dt[0] == 30) {
                    InventoryAction.giveItem(bot, new ItemStack(dropItem, 1));
                }
            }
            TaskState state = task.state();
            if (state == TaskState.FAILED || state == TaskState.CANCELLED) {
                String message = "recovery ended " + state + " reason=" + task.failureReason();
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), BOT);
                throwGameTest(context, message);
            } else if (state == TaskState.COMPLETED) {
                boolean notRemined = world.getBlockState(ore).isAir();
                boolean collected = InventoryAction.countItem(bot, dropItem) >= 1;
                boolean consumed = SemanticWorldRegistry.opportunity(bot, id).isEmpty();
                if (!notRemined || !collected || !consumed) {
                    throwGameTest(context, "recovery postcondition failed: notRemined=" + notRemined
                            + " collected=" + collected + " consumed=" + consumed);
                }
                finish(context, f);
            }
        });
    }

    /** 9: an externally consumed ore terminalizes as stale and never claims inventory success. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 200)
    public void r21StaleOpportunityDoesNotClaimInventorySuccess(TestContext context) {
        Fixture f = cabin(context, "r21_stale");
        AIPlayerEntity bot = f.bot;
        BlockPos ore = f.farWallCenter().up(5); // above the HOME bounds: visible, unprotected
        set(bot, ore, Blocks.IRON_ORE);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        io.github.zoyluo.aibot.perception.PerceptionCollector.collect(bot);
        String id = opportunityAt(context, bot, ore, "ACTIONABLE");
        var spec = SemanticWorldRegistry.opportunity(bot, id).orElseThrow();
        var expectedDrops = io.github.zoyluo.aibot.action.HarvestCore.expectedDropsFor(Blocks.IRON_ORE);
        int beforeCount = io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(bot, expectedDrops);

        set(bot, ore, Blocks.STONE); // someone else took the ore
        KnownResourceTask task = new KnownResourceTask(spec, false);
        task.start(bot);
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) task.tick(bot);
            TaskState state = task.state();
            if (state == TaskState.FAILED || state == TaskState.CANCELLED) {
                boolean typed = task.failureReason() != null
                        && task.failureReason().contains("known_resource_stale_externally_consumed");
                boolean gone = SemanticWorldRegistry.opportunity(bot, id).isEmpty();
                int afterCount = io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(bot, expectedDrops);
                String message = "stale handling failed: typed=" + typed + " opportunityGone=" + gone
                        + " inventoryDelta=" + (afterCount - beforeCount) + " reason=" + task.failureReason();
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), BOT);
                if (!typed || !gone || afterCount != beforeCount) throwGameTest(context, message);
                finish(context, f);
            } else if (state == TaskState.COMPLETED) {
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), BOT);
                throwGameTest(context, "stale ore must never complete as success");
            }
        });
    }

    /** 10: reserved-body farm mutations outside the registered exact mask are denied, world unchanged. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 200)
    public void r21ReservedFarmMutationOutsideMaskIsRejected(TestContext context) {
        Fixture f = farmFixture(context);
        AIPlayerEntity bot = f.bot;
        BlockPos outside = f.start.east(6); // outside the registered cross mask
        set(bot, outside, Blocks.FARMLAND);
        bot.getServerWorld().setBlockState(outside.up(),
                Blocks.WHEAT.getDefaultState().with(net.minecraft.block.CropBlock.AGE, 7), Block.NOTIFY_ALL);

        var result = io.github.zoyluo.aibot.action.FarmAction.harvest(bot, outside.up());
        require(context, result.isFailed() && result.reason().contains("farm_mutation_outside_registered_mask"),
                "outside-mask harvest must be denied typed, got " + result);
        require(context, bot.getServerWorld().getBlockState(outside.up()).isOf(Blocks.WHEAT),
                "outside-mask crop must remain untouched");
        finish(context, f);
    }

    /** 11: inside-mask mutations keep working (gate must not break the registered farm itself). */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 200)
    public void r21RegisteredFarmMutationInsideMaskStillWorks(TestContext context) {
        Fixture f = farmFixture(context);
        AIPlayerEntity bot = f.bot;
        BlockPos inside = f.start.east(1); // part of the registered cross mask
        bot.getServerWorld().setBlockState(inside.up(),
                Blocks.WHEAT.getDefaultState().with(net.minecraft.block.CropBlock.AGE, 7), Block.NOTIFY_ALL);

        var result = io.github.zoyluo.aibot.action.FarmAction.harvest(bot, inside.up());
        require(context, result.isSuccess(), "inside-mask harvest must succeed, got " + result);
        require(context, bot.getServerWorld().getBlockState(inside.up()).isAir(),
                "inside-mask mature crop must be broken");
        finish(context, f);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The reserved bot name is shared across every test in this batch (and with older MC1CA
     * batches, which may overlap in wall-clock time). A despawned fake player can linger in the
     * server player list for a tick, so each fixture first clears the name and retries.
     */
    private static AIPlayerEntity spawnReservedBot(TestContext context, net.minecraft.server.world.ServerWorld world,
                                                   BlockPos feet) {
        for (int attempt = 0; attempt < 3; attempt++) {
            AIPlayerManager.INSTANCE.despawn(world.getServer(), BOT); // idempotent stale-name cleanup
            var spawned = AIPlayerManager.INSTANCE.spawn(world.getServer(), BOT, world,
                    Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL);
            if (spawned.isPresent()) return spawned.get();
        }
        context.throwGameTestException("failed to spawn reserved bot " + BOT + " (name still lingering)");
        return null;
    }

    /**
     * Builds the R2-7 geometry: a doorless 5x5 oak-planks cabin (walls 3 high). The bot stands
     * OUTSIDE the far wall, within interaction reach of the missing cells.
     *
     * GameTest fake players are never world-ticked (entity age stays 0, verified by probe), so
     * walk-based work-pose selection cannot be exercised here; placing the bot directly beside the
     * work pose exercises the real resolve->materialSlot->equip->placeBlockAt chain plus HOME
     * semantics end to end. Work-pose selection under occlusion is covered semantically by
     * r21HomeRepairWorkPoseSurvivesOccludedCorner and physically on the live server (LIVE-R21-1).
     */
    private static Fixture cabin(TestContext context, String homeId) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -3; dx <= 12; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                for (int dy = -1; dy <= 5; dy++) {
                    world.setBlockState(feet.up(dy),
                            dy == -1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState(),
                            Block.NOTIFY_ALL);
                }
            }
        }
        // Cabin footprint: x in [start+3, start+7], z in [start-2, start+2]; walls 3 high, no door.
        for (int dx = 3; dx <= 7; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean wall = dx == 3 || dx == 7 || dz == -2 || dz == 2;
                if (!wall) continue;
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz), Blocks.OAK_PLANKS.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        BlockPos botStart = start.add(9, 0, 0);
        AIPlayerEntity bot = spawnReservedBot(context, world, botStart);
        bot.teleport(world, botStart.getX() + .5D, botStart.getY(), botStart.getZ() + .5D, Set.of(), 0, 0, true);
        ExternalBodyAccess.activateReservation();
        SemanticWorldRegistry.registerHome(bot, homeId, 6, 2, 5).persisted().join();
        return new Fixture(bot, botStart, start.add(7, 1, 0));
    }

    /**
     * Adversarial geometry: an L-corridor carved into a solid stone shell. The corridor floor is
     * y0 (stone at y-1..y-2), the missing cell is an oak-planks block in the north wall of the
     * far arm, and the shell extends far enough that every exterior stand column is either solid
     * stone or outside interaction reach of the hole. The bot starts at the far end of the south
     * arm, around the corner from the hole.
     */
    private static Fixture corridor(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -6; dx <= 11; dx++) {
            for (int dz = -1; dz <= 13; dz++) {
                for (int dy = -2; dy <= 6; dy++) {
                    world.setBlockState(start.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
                world.setBlockState(start.add(dx, -3, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        // Solid shell x0..x11, z0..z11, y-2..y3 plus a y4..y5 cap.
        for (int dx = 0; dx <= 11; dx++) {
            for (int dz = 0; dz <= 11; dz++) {
                for (int dy = -2; dy <= 5; dy++) {
                    world.setBlockState(start.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        // Corridor air: south arm x1..x6 at z6..z7, corner + north arm x5..x6 at z1..z5, y0..y2.
        for (int dx = 1; dx <= 6; dx++) {
            for (int dz = 6; dz <= 7; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        for (int dz = 1; dz <= 5; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                world.setBlockState(start.add(5, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(6, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        BlockPos hole = start.add(5, 1, 0); // north wall of the far arm, eye height
        world.setBlockState(hole, Blocks.OAK_PLANKS.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos botStart = start.add(1, 0, 6);
        AIPlayerEntity bot = spawnReservedBot(context, world, botStart);
        bot.teleport(world, botStart.getX() + .5D, botStart.getY(), botStart.getZ() + .5D, Set.of(), 0, 0, true);
        ExternalBodyAccess.activateReservation();
        SemanticWorldRegistry.registerHome(bot, "r21_corner", 6, 2, 5).persisted().join();
        return new Fixture(bot, botStart, hole.toImmutable());
    }


    /**
     * Builds and starts the repair BuildTask for homeId. The task is driven manually tick-by-tick
     * (the repo's established GatherPickup pattern) instead of via TaskManager, which keeps the
     * test independent of external-body assignment gates while strict observation and the
     * BreakPolicy stay fully live.
     */
    private static BuildTask startRepair(TestContext context, Fixture f, String homeId) {
        SemanticWorldRegistry.HomeRepairPlan plan = SemanticWorldRegistry.homeRepairPlan(f.bot, homeId);
        if (plan.wrong() > 0) throwGameTest(context, "plan unexpectedly conflicts: " + plan);
        if (plan.missing() == 0) throwGameTest(context, "plan unexpectedly satisfied: " + plan);
        BuildTask task = new BuildTask(plan.blueprint(), plan.anchor(), false, false);
        task.start(f.bot);
        return task;
    }

    /** Every server tick advances the repair once and finishes the test on its terminal state. */
    private static void driveRepair(TestContext context, Fixture f, BuildTask task,
                                    java.util.function.Supplier<String> postCheck) {
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(f.bot);
            }
            TaskState state = task.state();
            if (state == TaskState.FAILED || state == TaskState.CANCELLED) {
                String message = "repair task ended " + state + " reason=" + task.failureReason();
                AIPlayerManager.INSTANCE.despawn(f.bot.getServer(), BOT); // free the name before throwing
                throwGameTest(context, message);
            } else if (state == TaskState.COMPLETED) {
                String problem = postCheck.get();
                if (problem != null) throwGameTest(context, problem);
                finish(context, f);
            }
        });
    }

    /** Connected cross-shaped farmland registered as "r21_farm": center + 4 arms, all observed. */
    private static Fixture farmFixture(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -2; dx <= 8; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                for (int dy = -1; dy <= 3; dy++) {
                    world.setBlockState(feet.up(dy),
                            dy == -1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState(),
                            Block.NOTIFY_ALL);
                }
            }
        }
        setFarmCross(world, start.east(1)); // registered mask: center at start+1
        BlockPos botStart = start.south(2);
        AIPlayerEntity bot = spawnReservedBot(context, world, botStart);
        bot.teleport(world, botStart.getX() + .5D, botStart.getY(), botStart.getZ() + .5D, Set.of(), 0, 0, true);
        ExternalBodyAccess.activateReservation();
        SemanticWorldRegistry.registerFarm(bot, "r21_farm", 4, "wheat").persisted().join();
        setFarmCross(world, start.east(6)); // OUTSIDE the mask (never registered)
        return new Fixture(bot, start, start.east(1));
    }

    private static void setFarmCross(net.minecraft.server.world.ServerWorld world, BlockPos center) {
        world.setBlockState(center, Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
        for (net.minecraft.util.math.Direction direction : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            world.setBlockState(center.offset(direction), Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
        }
    }

    /** Finds this fixture's ore opportunity by position in the shared observe listing. */
    private static String opportunityAt(TestContext context, AIPlayerEntity bot, BlockPos ore, String expectedStatus) {
        for (var element : SemanticWorldRegistry.observe(bot).getAsJsonArray("resource_opportunities")) {
            var o = element.getAsJsonObject();
            var position = o.getAsJsonObject("position");
            if (position.get("x").getAsInt() == ore.getX() && position.get("y").getAsInt() == ore.getY()
                    && position.get("z").getAsInt() == ore.getZ()) {
                require(context, expectedStatus.equals(o.get("status").getAsString()),
                        "fixture ore must be " + expectedStatus + ": " + o);
                return o.get("id").getAsString();
            }
        }
        throwGameTest(context, "fixture ore opportunity not found at " + ore);
        return null;
    }

    private static com.google.gson.JsonObject opportunityEntry(AIPlayerEntity bot, String id) {
        for (var element : SemanticWorldRegistry.observe(bot).getAsJsonArray("resource_opportunities")) {
            var o = element.getAsJsonObject();
            if (id.equals(o.get("id").getAsString())) return o;
        }
        return null;
    }

    private static void throwGameTest(TestContext context, String message) {
        context.throwGameTestException(message);
    }

    private static void givePlanks(AIPlayerEntity bot, int count) {
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, count));
    }

    private static void set(AIPlayerEntity bot, BlockPos pos, Block block) {
        bot.getServerWorld().setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) context.throwGameTestException(message);
    }

    private static void finish(TestContext context, Fixture f) {
        TaskManager.INSTANCE.resetToIdle(f.bot);
        AIPlayerManager.INSTANCE.despawn(f.bot.getServer(), BOT);
        context.complete();
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start, BlockPos farWallCenter) {
        BlockPos cabinFloorCenter() { return farWallCenter.add(-2, -1, 0); }
    }
}
