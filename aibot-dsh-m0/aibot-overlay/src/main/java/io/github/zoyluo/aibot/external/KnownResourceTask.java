package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.task.AbstractTask;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/** Mine one exact previously-observed ore opportunity; never silently substitutes another vein. */
public final class KnownResourceTask extends AbstractTask {
    private enum Phase { APPROACH, MINING, PICKUP, PICKUP_RECOVERY }
    private static final int PICKUP_RECOVERY_BUDGET_TICKS = 200;
    private static final int DROP_ABSENCE_CONFIRM_TICKS = 60;
    private final SemanticWorldRegistry.OpportunitySpec opportunity;
    private final Set<Item> targetDrops;
    private final BlockMiner miner = new BlockMiner();
    private final boolean pickupRecoveryOnly;
    private Phase phase = Phase.APPROACH;
    private int pickupTicks;
    private int recoveryTicks;
    private int dropAbsentTicks;
    private int inventoryBefore;
    private boolean pathStarted;

    public KnownResourceTask(SemanticWorldRegistry.OpportunitySpec opportunity) {
        this(opportunity, false);
    }

    /**
     * R2.1: with pickupRecoveryOnly the task NEVER mines again — the ore break was already proven
     * (the opportunity entered MINED_PENDING_PICKUP). It only walks the drop, waits for the
     * inventory-delta postcondition, and either consumes the opportunity on proof or fails typed.
     */
    public KnownResourceTask(SemanticWorldRegistry.OpportunitySpec opportunity, boolean pickupRecoveryOnly) {
        this.opportunity = opportunity;
        this.pickupRecoveryOnly = pickupRecoveryOnly;
        this.targetDrops = HarvestCore.expectedDropsFor(opportunity.block());
    }

    @Override public String name() { return "mine_known_resource"; }

    @Override public String describe() {
        return "Mine known " + Registries.BLOCK.getId(opportunity.block()) + " at "
                + opportunity.pos().toShortString() + " phase=" + phase;
    }

    @Override public double progress() {
        return switch (phase) {
            case APPROACH -> 0.2D;
            case MINING -> 0.6D;
            case PICKUP -> 0.9D;
            case PICKUP_RECOVERY -> 0.95D;
        };
    }

    @Override protected void onStart(AIPlayerEntity bot) {
        if (pickupRecoveryOnly) {
            // The mining postcondition is historical fact here; re-baseline the inventory so the
            // recovery completion proof is a delta measured from recovery start.
            inventoryBefore = HarvestCore.countInventoryItems(bot, targetDrops);
            recoveryTicks = PICKUP_RECOVERY_BUDGET_TICKS;
            dropAbsentTicks = 0;
            phase = Phase.PICKUP_RECOVERY;
        } else {
            phase = Phase.APPROACH;
        }
    }

    @Override protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) { fail("known_resource_timeout"); return; }
        if (!dimension(bot).equals(opportunity.dimension())) { fail("known_resource_wrong_dimension"); return; }
        switch (phase) {
            case APPROACH -> approach(bot);
            case MINING -> mine(bot);
            case PICKUP -> pickup(bot);
            case PICKUP_RECOVERY -> recoverPickup(bot);
        }
    }

    private void approach(AIPlayerEntity bot) {
        BlockPos pos = opportunity.pos();
        if (!ToolTier.canHarvestWithInventory(bot, opportunity.block().getDefaultState())) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(opportunity.block()));
            return;
        }
        BreakPolicy.Decision decision = BreakPolicy.decide(bot, pos);
        if (!decision.allowed()) { fail(decision.reason()); return; }

        // A remembered opportunity is last-known spatial state, not remote omniscience. Never read
        // the target BlockState until the exact cell is once again observable from the live body.
        boolean visibleBlock = ObservableWorldQuery.canObserveBlock(bot, pos);
        boolean visibleCell = ObservableWorldQuery.canObserveCell(bot, pos);
        if (!visibleBlock && !visibleCell) {
            BlockPos seenFrom = opportunity.seenFrom();
            if (bot.getBlockPos().getSquaredDistance(seenFrom) > 128 * 128) {
                fail("known_resource_far_use_mc_goto_seen_from:" + seenFrom.toShortString());
                return;
            }
            if (bot.getActionPack().isPathExecutorIdle()) {
                var result = bot.getActionPack().startPathTo(seenFrom);
                if (result.isFailed() && pathStarted) fail("known_resource_seen_from_unreachable:" + result.reason());
                pathStarted = true;
            }
            return;
        }
        var state = bot.getServerWorld().getBlockState(pos);
        if (!state.isOf(opportunity.block())) {
            // R2.1: externally consumed — terminal tombstone event, and NEVER a claim that the
            // resource reached this bot's inventory.
            SemanticWorldRegistry.markOpportunityStale(bot, opportunity.id(), "externally_consumed_or_stale");
            fail("known_resource_stale_externally_consumed");
            return;
        }

        HarvestCore.TargetChoice choice = HarvestCore.nearestReachableBlock(
                bot, Set.of(opportunity.block()), 16, 16, 16, candidate -> candidate.equals(pos));
        if (choice == null) {
            // R2.1: proven absence of a legal work pose demotes the opportunity to UNREACHABLE
            // (typed, persisted, revalidatable) instead of leaving a zombie ACTIONABLE entry.
            SemanticWorldRegistry.markOpportunityUnreachable(bot, opportunity.id(), "no_reachable_work_pose");
            fail("known_resource_unreachable:no_reachable_work_pose");
            return;
        }
        if (!choice.direct()) {
            if (bot.getActionPack().isPathExecutorIdle()) {
                var result = bot.getActionPack().startPathTo(choice.stand());
                if (result.isFailed()) fail("known_resource_approach_failed:" + result.reason());
            }
            return;
        }
        startMining(bot);
    }

    private void startMining(AIPlayerEntity bot) {
        BlockPos pos = opportunity.pos();
        if (lavaAdjacent(bot, pos)) { fail("known_resource_hazard_lava"); return; }
        inventoryBefore = HarvestCore.countInventoryItems(bot, targetDrops);
        miner.begin(bot, pos);
        phase = Phase.MINING;
    }

    private void mine(AIPlayerEntity bot) {
        if (!bot.getServerWorld().getBlockState(opportunity.pos()).isOf(opportunity.block())) {
            miner.cancel(bot); pickupTicks = 120; phase = Phase.PICKUP; return;
        }
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.FAILED) { fail("known_resource_mining_failed"); return; }
        if (status == BlockMiner.Status.DONE) { pickupTicks = 120; phase = Phase.PICKUP; }
    }

    private void pickup(AIPlayerEntity bot) {
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops);
        int collected = HarvestCore.countInventoryItems(bot, targetDrops) - inventoryBefore;
        if (collected > 0) {
            SemanticWorldRegistry.markOpportunityConsumed(bot, opportunity.id());
            BotLog.action(bot, "known_resource_collected", "opportunity", opportunity.id(), "count", collected);
            complete();
            return;
        }
        HarvestCore.chaseDropAnyOf(bot, targetDrops, 8.0D);
        if (--pickupTicks <= 0) {
            if (!bot.getServerWorld().getBlockState(opportunity.pos()).isOf(opportunity.block())) {
                // R2.1: the ore break is proven but the drop is not in inventory yet — park the
                // opportunity as MINED_PENDING_PICKUP (persisted, restart-safe) and fail typed.
                // The old behavior consumed the opportunity here, silently losing the resource.
                SemanticWorldRegistry.markOpportunityPendingPickup(bot, opportunity.id());
                fail("known_resource_pickup_pending_recovery");
            } else {
                // The block is back/still there: nothing was mined, the opportunity stays as-is.
                fail("known_resource_pickup_timeout");
            }
        }
    }

    /**
     * R2.1 pickup recovery: the ore was already broken (MINED_PENDING_PICKUP). Never re-mine the
     * cell — collect the drop and demand the inventory-delta postcondition. If the drop has
     * verifiably vanished (no matching ItemEntity nearby, no inventory delta, sustained), end as
     * a typed stale loss; a plain timeout leaves the pending state intact for a later retry.
     */
    private void recoverPickup(AIPlayerEntity bot) {
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops);
        int collected = HarvestCore.countInventoryItems(bot, targetDrops) - inventoryBefore;
        if (collected > 0) {
            SemanticWorldRegistry.markOpportunityConsumed(bot, opportunity.id());
            BotLog.action(bot, "known_resource_collected",
                    "opportunity", opportunity.id(), "count", collected, "mode", "pickup_recovery");
            complete();
            return;
        }
        HarvestCore.chaseDropAnyOf(bot, targetDrops, 8.0D);
        boolean dropNearby = HarvestCore.nearestDropAnyOf(bot, targetDrops, 8.0D).isPresent();
        dropAbsentTicks = dropNearby ? 0 : dropAbsentTicks + 1;
        if (dropAbsentTicks > DROP_ABSENCE_CONFIRM_TICKS) {
            // Conservative loss: no drop entity, no inventory delta — the resource provably did
            // NOT reach this bot. Terminal tombstone; never fakes collection.
            SemanticWorldRegistry.markOpportunityStale(bot, opportunity.id(), "pickup_lost_drop_despawned_or_taken");
            fail("known_resource_pickup_lost_drop_despawned_or_taken");
            return;
        }
        if (--recoveryTicks <= 0) {
            // Retryable outcome: pending state survives for a later mc_mine_opportunity(id).
            fail("known_resource_pickup_recovery_timeout");
        }
    }

    @Override protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    private static boolean lavaAdjacent(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (bot.getServerWorld().getFluidState(pos.offset(direction)).isIn(FluidTags.LAVA)) return true;
        }
        return false;
    }

    private static String dimension(AIPlayerEntity bot) {
        return bot.getServerWorld().getRegistryKey().getValue().toString();
    }
}
