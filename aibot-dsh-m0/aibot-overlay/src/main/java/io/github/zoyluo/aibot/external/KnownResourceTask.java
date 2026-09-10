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
    private enum Phase { APPROACH, MINING, PICKUP }
    private final SemanticWorldRegistry.OpportunitySpec opportunity;
    private final Set<Item> targetDrops;
    private final BlockMiner miner = new BlockMiner();
    private Phase phase = Phase.APPROACH;
    private int pickupTicks;
    private int inventoryBefore;
    private boolean pathStarted;

    public KnownResourceTask(SemanticWorldRegistry.OpportunitySpec opportunity) {
        this.opportunity = opportunity;
        this.targetDrops = HarvestCore.expectedDropsFor(opportunity.block());
    }

    @Override public String name() { return "mine_known_resource"; }

    @Override public String describe() {
        return "Mine known " + Registries.BLOCK.getId(opportunity.block()) + " at "
                + opportunity.pos().toShortString() + " phase=" + phase;
    }

    @Override public double progress() {
        return switch (phase) { case APPROACH -> 0.2D; case MINING -> 0.6D; case PICKUP -> 0.9D; };
    }

    @Override protected void onStart(AIPlayerEntity bot) { phase = Phase.APPROACH; }

    @Override protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) { fail("known_resource_timeout"); return; }
        if (!dimension(bot).equals(opportunity.dimension())) { fail("known_resource_wrong_dimension"); return; }
        switch (phase) {
            case APPROACH -> approach(bot);
            case MINING -> mine(bot);
            case PICKUP -> pickup(bot);
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
            SemanticWorldRegistry.markOpportunityConsumed(bot, opportunity.id());
            fail("known_resource_stale_or_consumed");
            return;
        }

        HarvestCore.TargetChoice choice = HarvestCore.nearestReachableBlock(
                bot, Set.of(opportunity.block()), 16, 16, 16, candidate -> candidate.equals(pos));
        if (choice == null) {
            fail("known_resource_visible_but_not_reachable");
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
                SemanticWorldRegistry.markOpportunityConsumed(bot, opportunity.id());
            }
            fail("known_resource_pickup_timeout");
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
