package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.action.MiningController;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * MC-2A0.2 finite transaction for one already-proven natural tree.
 *
 * <p>This class deliberately does not discover a bigger tree after acquisition. The initial
 * {@link NaturalTreeClassifier.HarvestClusterProof} freezes the exact committed log cells. The
 * workset only reconciles those cells, owns only supports that this exact bounded execution placed,
 * and never scans nearby dirt/cobble to infer ownership.</p>
 *
 * <p>Temporary tree access is intentionally separate from generic {@code PathExecutor.PILLAR_UP}.
 * Ordinary path pillars remain persistent infrastructure. TREE_ACCESS uses a dedicated vertical
 * support transaction with exact placement receipts and reverse cleanup.</p>
 */
public final class TreeHarvestWorkset {
    private static final int MAX_ACCESS_BASE_DROP = 16;
    private static final int MAX_ACCESS_TICKS = 600;
    private static final int MAX_TEMP_SUPPORTS = 12;
    private static final int MAX_CLEANUP_TICKS = 1200;

    public enum StepState {
        IN_PROGRESS,
        READY,
        COMPLETE,
        BLOCKED,
        DEBT
    }

    public record StepResult(StepState state, String reason) {
        public StepResult {
            reason = reason == null ? "" : reason;
        }
        public static StepResult progress() { return new StepResult(StepState.IN_PROGRESS, ""); }
        public static StepResult ready() { return new StepResult(StepState.READY, ""); }
        public static StepResult complete() { return new StepResult(StepState.COMPLETE, ""); }
        public static StepResult blocked(String reason) { return new StepResult(StepState.BLOCKED, reason); }
        public static StepResult debt(String reason) { return new StepResult(StepState.DEBT, reason); }
    }

    public enum SupportState {
        PLACED,
        REMOVED,
        REMOVED_EXTERNALLY,
        CONFLICT
    }

    public enum PickupState {
        PENDING,
        COLLECTED,
        LOST
    }

    public record TemporarySupport(BlockPos pos,
                                   String blockId,
                                   String ownerExecution,
                                   String purpose,
                                   String originalState,
                                   long placedAt,
                                   SupportState state) {
        public TemporarySupport {
            pos = pos.toImmutable();
            blockId = blockId == null ? "" : blockId;
            ownerExecution = ownerExecution == null ? "" : ownerExecution;
            purpose = purpose == null ? "TREE_ACCESS" : purpose;
            originalState = originalState == null ? "" : originalState;
            state = state == null ? SupportState.PLACED : state;
        }
        TemporarySupport withState(SupportState next) {
            return new TemporarySupport(pos, blockId, ownerExecution, purpose, originalState, placedAt, next);
        }
    }

    public record PickupObligation(BlockPos origin, PickupState state, String resolution) {
        public PickupObligation {
            origin = origin.toImmutable();
            state = state == null ? PickupState.PENDING : state;
            resolution = resolution == null ? "" : resolution;
        }
        PickupObligation resolve(PickupState next, String why) {
            return new PickupObligation(origin, next, why);
        }
    }

    public record Snapshot(String treeId,
                           String worldId,
                           String dimension,
                           String ownerExecution,
                           Set<BlockPos> candidates,
                           Set<BlockPos> remaining,
                           Set<BlockPos> completed,
                           Map<BlockPos, String> blocked,
                           List<TemporarySupport> supports,
                           List<PickupObligation> pickups,
                           String cleanupDebt,
                           String abandonReason) {}

    private final String treeId;
    private final String worldId;
    private final String dimension;
    private final String ownerExecution;
    private final LinkedHashSet<Long> candidates;
    private final LinkedHashSet<Long> remaining;
    private final LinkedHashSet<Long> completed = new LinkedHashSet<>();
    private final LinkedHashMap<Long, String> blocked = new LinkedHashMap<>();
    private final ArrayList<TemporarySupport> supports = new ArrayList<>();
    private final LinkedHashMap<Long, PickupObligation> pickups = new LinkedHashMap<>();

    private BlockPos accessTarget;
    private BlockPos accessBase;
    private boolean accessPathStarted;
    private int accessTicks;
    /** Frozen identity of the displaced support stack; null while no re-entry is in progress. */
    private List<TemporarySupport> reentryChain;
    private int reentrySettleTicks;
    private int reentryRegroundSteps;
    private static final int MAX_REENTRY_SETTLE_TICKS = 40;
    private static final int MAX_REENTRY_REGROUND_STEPS = 4;
    private final LinkedHashSet<Long> rejectedAccessBases = new LinkedHashSet<>();

    private MiningController cleanupMiner;    private BlockPos cleanupMiningPos;
    private int cleanupTicks;

    private String cleanupDebt = "";
    private String abandonReason = "";

    private TreeHarvestWorkset(String treeId,
                               String worldId,
                               String dimension,
                               String ownerExecution,
                               Set<Long> candidateLogs) {
        this.treeId = treeId;
        this.worldId = worldId;
        this.dimension = dimension;
        this.ownerExecution = ownerExecution;
        List<BlockPos> sorted = candidateLogs.stream()
                .map(BlockPos::fromLong)
                .sorted(Comparator.comparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .toList();
        this.candidates = new LinkedHashSet<>();
        for (BlockPos pos : sorted) this.candidates.add(pos.asLong());
        this.remaining = new LinkedHashSet<>(this.candidates);
    }

    public static Optional<TreeHarvestWorkset> acquire(AIPlayerEntity bot,
                                                       BlockPos seed,
                                                       String ownerExecution) {
        if (bot == null || seed == null || ownerExecution == null || ownerExecution.isBlank()) {
            return Optional.empty();
        }
        Optional<NaturalTreeClassifier.HarvestClusterProof> proof =
                NaturalTreeClassifier.acquireHarvestCluster(bot, seed);
        if (proof.isEmpty() || proof.get().logs().isEmpty()) return Optional.empty();
        String world = SemanticWorldRegistry.worldId();
        String dimension = dimension(bot);
        if (!dimension.equals(proof.get().dimension())) return Optional.empty();
        String treeId = world + ":" + dimension + ":" + seed.asLong();
        TreeHarvestWorkset workset = new TreeHarvestWorkset(
                treeId, world, dimension, ownerExecution, proof.get().logs());
        workset.reconcile(bot);
        BotLog.action(bot, "tree_workset_acquired",
                "tree", treeId,
                "execution", ownerExecution,
                "logs", workset.candidates.size());
        return Optional.of(workset);
    }

    public String treeId() { return treeId; }
    public String ownerExecution() { return ownerExecution; }
    public boolean hasCleanupDebt() { return !cleanupDebt.isBlank(); }
    public String cleanupDebt() { return cleanupDebt; }
    public String unresolvedSupportSummary() {
        return supports.stream()
                .filter(s -> s.state() == SupportState.PLACED || s.state() == SupportState.CONFLICT)
                .limit(MAX_TEMP_SUPPORTS)
                .map(s -> s.pos().toShortString() + "=" + s.blockId() + "@" + s.state())
                .reduce((a, b) -> a + ";" + b).orElse("");
    }
    public boolean hasTemporarySupports() {
        return supports.stream().anyMatch(s -> s.state() == SupportState.PLACED);
    }
    public boolean hasPendingPickup() {
        return pickups.values().stream().anyMatch(p -> p.state() == PickupState.PENDING);
    }
    public boolean hasBlockedLogs() { return !blocked.isEmpty(); }
    public String blockedSummary() {
        return blocked.entrySet().stream()
                .map(e -> BlockPos.fromLong(e.getKey()).toShortString() + "=" + e.getValue())
                .limit(4)
                .reduce((a, b) -> a + "," + b).orElse("");
    }
    public boolean logsResolved() { return remaining.isEmpty() && blocked.isEmpty(); }
    public boolean pickupsReconciled() {
        return pickups.values().stream().noneMatch(p -> p.state() == PickupState.PENDING);
    }
    public boolean treeComplete() {
        return logsResolved() && !hasTemporarySupports() && pickupsReconciled()
                && cleanupDebt.isBlank() && abandonReason.isBlank();
    }

    public Snapshot snapshot() {
        LinkedHashMap<BlockPos, String> blockedPositions = new LinkedHashMap<>();
        for (var entry : blocked.entrySet()) {
            blockedPositions.put(BlockPos.fromLong(entry.getKey()), entry.getValue());
        }
        return new Snapshot(treeId, worldId, dimension, ownerExecution,
                positions(candidates), positions(remaining), positions(completed),
                Map.copyOf(blockedPositions),
                List.copyOf(supports), List.copyOf(pickups.values()), cleanupDebt, abandonReason);
    }

    public void onResume() {
        accessPathStarted = false;
        reentryChain = null;
        reentrySettleTicks = 0;
        reentryRegroundSteps = 0;
        cleanupMiner = null;
        cleanupMiningPos = null;
    }

    public void abandon(String reason) {
        abandonReason = reason == null ? "abandoned" : reason;
        accessPathStarted = false;
        reentryChain = null;
        reentrySettleTicks = 0;
        reentryRegroundSteps = 0;
        cleanupMiner = null;
        cleanupMiningPos = null;
        if (!hasTemporarySupports()) accessTarget = null;
    }

    /** Reconcile only the frozen candidate cells. Never expands the tree after acquisition. */
    public void reconcile(AIPlayerEntity bot) {
        if (!scopeMatches(bot)) {
            debt("tree_scope_changed");
            return;
        }
        ServerWorld world = bot.getServerWorld();
        for (long packed : new ArrayList<>(remaining)) {
            BlockPos pos = BlockPos.fromLong(packed);
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(BlockTags.LOGS)) {
                remaining.remove(packed);
                blocked.remove(packed);
                completed.add(packed); // externally resolved exact committed cell; no pickup claim
                continue;
            }
            if (!BreakPolicy.mayBreak(bot, pos)) {
                blocked.put(packed, "break_policy:" + BreakPolicy.decide(bot, pos).reason());
                remaining.remove(packed);
            }
        }
    }

    public BlockPos nextRemaining(AIPlayerEntity bot) {
        reconcile(bot);
        // A live TREE_ACCESS stack belongs to one exact committed log.  Safety may move the bot
        // away, but it must not make the gather loop silently choose a different log while owned
        // supports are still standing.  Re-enter the same work face first.
        if (hasTemporarySupports()) {
            if (accessTarget != null && remaining.contains(accessTarget.asLong())) return accessTarget;
            return null; // target was externally resolved: cleanup before choosing another log
        }
        return remaining.stream()
                .map(BlockPos::fromLong)
                .min(Comparator.comparingInt(BlockPos::getY)
                        .thenComparingDouble(pos -> pos.getSquaredDistance(bot.getBlockPos()))
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .orElse(null);
    }

    public void markBlocked(BlockPos pos, String reason) {
        if (pos == null || !candidates.contains(pos.asLong())) return;
        remaining.remove(pos.asLong());
        blocked.put(pos.asLong(), reason == null ? "blocked" : reason);
    }

    /** Called only after the gather task has already entered HARVEST for this exact committed cell. */
    public void noteHarvested(AIPlayerEntity bot, BlockPos origin) {
        if (origin == null || !candidates.contains(origin.asLong())) return;
        remaining.remove(origin.asLong());
        blocked.remove(origin.asLong());
        completed.add(origin.asLong());
        pickups.put(origin.asLong(), new PickupObligation(origin, PickupState.PENDING, ""));
        BotLog.action(bot, "tree_log_resolved",
                "tree", treeId, "execution", ownerExecution, "pos", origin.toShortString());
    }

    public void resolvePickup(BlockPos origin, String proof) {
        if (origin == null) return;
        PickupObligation pending = pickups.get(origin.asLong());
        if (pending == null || pending.state() != PickupState.PENDING) return;
        pickups.put(origin.asLong(), pending.resolve(PickupState.COLLECTED,
                proof == null ? "pickup_verified" : proof));
    }

    /** A bounded pickup deadline may resolve as loss; it is explicit, never silently forgotten. */
    public void resolvePickupLoss(BlockPos origin, String reason) {
        if (origin == null) return;
        PickupObligation pending = pickups.get(origin.asLong());
        if (pending == null || pending.state() != PickupState.PENDING) return;
        pickups.put(origin.asLong(), pending.resolve(PickupState.LOST,
                reason == null ? "pickup_not_recovered" : reason));
    }

    /**
     * Obtain a read/interaction pose for one frozen log. Uses no generic PILLAR_UP: surface walking
     * gets to a real ground base, then TREE_ACCESS supports are placed one level at a time and
     * recorded only after a successful vanilla block placement.
     */
    public StepResult tickAccess(AIPlayerEntity bot, BlockPos target) {
        if (hasCleanupDebt()) return StepResult.debt(cleanupDebt);
        if (!scopeMatches(bot)) return debt("tree_scope_changed");
        if (target == null || !remaining.contains(target.asLong())) return StepResult.blocked("target_not_in_remaining_workset");
        BlockState targetState = bot.getServerWorld().getBlockState(target);
        if (!targetState.isIn(BlockTags.LOGS)) {
            reconcile(bot);
            return StepResult.blocked("committed_log_already_resolved");
        }
        var breakDecision = BreakPolicy.decide(bot, target);
        if (!breakDecision.allowed()) return StepResult.blocked("committed_log_protected:" + breakDecision.reason());

        if (accessTarget == null || !accessTarget.equals(target)) {
            if (hasTemporarySupports()) {
                // MC-2A0.2F may have cleared the transient target immediately before HARVEST while
                // the owned support receipts intentionally survived.  Rebind only when this is
                // still the same frozen remaining cell; nextRemaining() guarantees no retarget.
                if (accessTarget == null && remaining.contains(target.asLong())) {
                    accessTarget = target.toImmutable();
                } else {
                    return debt("tree_access_target_changed_with_owned_supports");
                }
            } else {
                resetAccessTransient();
                accessTarget = target.toImmutable();
            }
        }
        if (++accessTicks > MAX_ACCESS_TICKS) return StepResult.blocked("tree_access_timeout");

        if (hasTemporarySupports()) {
            TemporarySupport last = latestPlacedSupport();
            if (last == null) return debt("tree_access_support_ledger_empty");
            if (!bot.getBlockPos().equals(last.pos().up())) {
                return tickSupportReentry(bot, target);
            }
            if (HarvestCore.canReach(bot, target) && ObservableWorldQuery.canObserveBlock(bot, target)) {
                resetAccessTransient();
                return StepResult.ready();
            }
            return placeOneSupport(bot, target);
        }

        if (HarvestCore.canReach(bot, target) && ObservableWorldQuery.canObserveBlock(bot, target)) {
            resetAccessTransient();
            return StepResult.ready();
        }

        if (accessBase == null) {
            accessBase = chooseAccessBase(bot, target);
            if (accessBase == null) return StepResult.blocked("tree_access_no_safe_base");
        }
        if (!bot.getBlockPos().equals(accessBase)) {
            if (accessPathStarted) {
                if (!bot.getActionPack().isPathExecutorIdle()) return StepResult.progress();
                rejectedAccessBases.add(accessBase.asLong());
                accessBase = null;
                accessPathStarted = false;
                return StepResult.progress();
            }
            ActionResult path = bot.getActionPack().startSurfacePathTo(accessBase);
            BlockPos resolved = bot.getActionPack().activePathGoal();
            if (path.isFailed() || resolved == null || !resolved.equals(accessBase)) {
                bot.getActionPack().stopAll();
                rejectedAccessBases.add(accessBase.asLong());
                accessBase = null;
                return StepResult.progress();
            }
            accessPathStarted = true;
            return StepResult.progress();
        }
        accessPathStarted = false;
        return placeOneSupport(bot, target);
    }

    /**
     * Re-enter the owned TREE_ACCESS transaction after a SAFETY displacement.
     *
     * <p>This never scans nearby blocks and never infers ownership from material/shape.  Every
     * live receipt is re-verified each tick: exact owner_execution, purpose TREE_ACCESS, exact
     * position, exact current block id; a foreign or modified receipt is typed cleanup debt,
     * never a guessed repair.  Because TREE_ACCESS supports are placed under the bot's own feet,
     * a stack of two or more carries the next receipt on the previous receipt's top, so the
     * standing face cannot be re-entered from the ground.  Re-entry therefore walks back to a
     * ground cell directly beside the frozen stack and resumes the same owned placement primitive
     * one column over; the ordinary access branch then continues that owned climb to harvest.
     * All newly placed cells are ordinary owned TREE_ACCESS receipts and are reverse-cleaned
     * together with the displaced stack.</p>
     */
    private StepResult tickSupportReentry(AIPlayerEntity bot, BlockPos target) {
        List<TemporarySupport> chain = supports.stream()
                .filter(s -> s.state() == SupportState.PLACED)
                .toList();
        if (chain.isEmpty()) {
            reentryChain = null;
            return StepResult.complete();
        }
        ServerWorld world = bot.getServerWorld();
        for (TemporarySupport support : chain) {
            if (!ownerExecution.equals(support.ownerExecution()) || !"TREE_ACCESS".equals(support.purpose())) {
                return debt("tree_access_reentry_foreign_receipt");
            }
            String actual = Registries.BLOCK.getId(world.getBlockState(support.pos()).getBlock()).toString();
            if (!actual.equals(support.blockId())) {
                replaceSupportState(support.pos(), SupportState.CONFLICT);
                return debt("tree_access_reentry_support_conflict:" + support.pos().toShortString());
            }
        }
        // Freeze the displaced stack identity once; later re-entry receipts join the owned
        // ledger without moving the column the re-entry walks back to.  Access-phase walk
        // rejections belong to the abandoned approach and must not starve the re-entry base
        // choice — the displaced bot gets a fresh rejection budget for its own column walk.
        if (reentryChain == null) {
            reentryChain = List.copyOf(chain);
            rejectedAccessBases.clear();
        }
        BlockPos stackBase = reentryChain.getFirst().pos();


        boolean onOwnedTop = chain.stream().anyMatch(s -> bot.getBlockPos().equals(s.pos().up()));
        int besideStack = Math.abs(bot.getBlockPos().getX() - stackBase.getX())
                + Math.abs(bot.getBlockPos().getZ() - stackBase.getZ());
        if (!onOwnedTop && (besideStack > 1 || bot.getBlockPos().getY() > stackBase.getY() + 1)) {
            StepResult walking = walkBesideColumn(bot, stackBase, "tree_access_reentry_no_adjacent_base");
            if (walking != null) return walking;
        }

        // Beside the verified stack: a knockback can leave the clientless body with a stale
        // onGround=false even on a standable cell, which rejects every pillar jump.  Same-cell
        // re-anchoring is not expressible with the reviewed motion adapters, so a strictly
        // bounded number of validated neighbour steps re-publishes the grounded bit through
        // FakePlayerMotion; a genuinely airborne body instead gets a bounded settle window.
        if (!bot.isOnGround()) {
            Standability.clearCache();
            BlockPos feet = bot.getBlockPos().toImmutable();
            boolean standableNow = Standability.isStandable(world, feet);
            if (standableNow && reentryRegroundSteps < MAX_REENTRY_REGROUND_STEPS) {
                for (Direction dir : new Direction[]{Direction.EAST, Direction.WEST,
                        Direction.NORTH, Direction.SOUTH}) {
                    if (FakePlayerMotion.stepToStandable(bot, feet.offset(dir),
                            "tree_access_reentry_reground")) {
                        reentryRegroundSteps++;
                        return StepResult.progress();
                    }
                }
            } else if (!standableNow) {
                if (++reentrySettleTicks > MAX_REENTRY_SETTLE_TICKS) {
                    return debt("tree_access_reentry_pose_unsettled:" + feet.toShortString());
                }
                return StepResult.progress();
            }
        }

        // Beside the verified stack: resume ordinary owned placement.  If the pinned target is
        // already reachable from here the caller harvests it without any new receipt.
        BotLog.action(bot, "tree_support_reentered",
                "tree", treeId, "execution", ownerExecution, "supports", chain.size());
        reentryChain = null;
        return placeOneSupport(bot, target);
    }

    /**
     * Surface walk to a standable ground cell directly beside {@code columnBottom}'s column.
     * Returns null once the bot stands on that cell; otherwise a progress/blocked/debt step.
     */
    private StepResult walkBesideColumn(AIPlayerEntity bot, BlockPos columnBottom, String noBaseDebt) {
        if (accessBase != null
                && Math.abs(accessBase.getX() - columnBottom.getX())
                        + Math.abs(accessBase.getZ() - columnBottom.getZ()) > 1) {
            accessBase = null; // chosen beside a different column
        }
        if (accessBase == null) {
            accessBase = chooseReentryBase(bot, columnBottom);
            if (accessBase == null) return debt(noBaseDebt);
        }
        if (!bot.getBlockPos().equals(accessBase)) {
            if (accessPathStarted) {
                if (!bot.getActionPack().isPathExecutorIdle()) return StepResult.progress();
                rejectedAccessBases.add(accessBase.asLong());
                accessBase = null;
                accessPathStarted = false;
                return StepResult.progress();
            }
            ActionResult path = bot.getActionPack().startSurfacePathTo(accessBase);
            BlockPos resolved = bot.getActionPack().activePathGoal();
            if (path.isFailed() || resolved == null || !resolved.equals(accessBase)) {
                bot.getActionPack().stopAll();
                rejectedAccessBases.add(accessBase.asLong());
                accessBase = null;
                return StepResult.progress();
            }
            accessPathStarted = true;
            return StepResult.progress();
        }
        accessPathStarted = false;
        return null;
    }

    /** Standable ground cell directly beside the support column, excluding rejected bases. */
    private BlockPos chooseReentryBase(AIPlayerEntity bot, BlockPos columnBottom) {
        ServerWorld world = bot.getServerWorld();
        int[][] adjacent = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        BlockPos best = null;
        for (int[] offset : adjacent) {
            for (int y = columnBottom.getY(); y >= columnBottom.getY() - 2 && y >= world.getBottomY(); y--) {
                BlockPos feet = new BlockPos(columnBottom.getX() + offset[0], y, columnBottom.getZ() + offset[1]);
                if (rejectedAccessBases.contains(feet.asLong())) break;
                Standability.clearCache();
                if (!Standability.isStandable(world, feet)) continue;
                BlockState ground = world.getBlockState(feet.down());
                if (ground.isIn(BlockTags.LOGS) || ground.isIn(BlockTags.LEAVES)
                        || Standability.isDangerous(ground)) break;
                if (!clearAccessColumn(world, feet, columnBottom.up(4))) continue;
                if (best == null || feet.getSquaredDistance(bot.getBlockPos())
                        < best.getSquaredDistance(bot.getBlockPos())) {
                    best = feet.toImmutable();
                }
                break;
            }
        }
        return best;
    }

    /** Lowest still-placed owned support of {@code supportPos}'s column. */
    private BlockPos columnBottomOf(BlockPos supportPos) {
        return supports.stream()
                .filter(s -> s.state() == SupportState.PLACED)
                .map(TemporarySupport::pos)
                .filter(p -> p.getX() == supportPos.getX() && p.getZ() == supportPos.getZ()
                        && p.getY() <= supportPos.getY())
                .min(Comparator.comparingInt(BlockPos::getY))
                .orElse(supportPos);
    }

    /** Reverse-clean exactly this execution's support receipts. */
    public StepResult tickCleanup(AIPlayerEntity bot) {
        if (hasCleanupDebt()) return StepResult.debt(cleanupDebt);
        if (!scopeMatches(bot)) return debt("tree_cleanup_scope_changed");
        if (++cleanupTicks > MAX_CLEANUP_TICKS) return debt("tree_cleanup_timeout");
        // Topmost first, earliest on ties.  Latest-first would immediately re-target a
        // climb-assist receipt placed by the approach below, oscillating place/remove until the
        // material runs out; with a single column topmost == latest, so the classic reverse
        // pillar descent is unchanged.
        TemporarySupport support = topmostPlacedSupport();
        if (support == null) {
            resetCleanupTransient();
            return StepResult.complete();
        }
        ServerWorld world = bot.getServerWorld();
        BlockState actual = world.getBlockState(support.pos());
        if (actual.isAir()) {
            replaceSupportState(support.pos(), SupportState.REMOVED_EXTERNALLY);
            resetCleanupTransient();
            return StepResult.progress();
        }
        String actualId = Registries.BLOCK.getId(actual.getBlock()).toString();
        if (!actualId.equals(support.blockId())) {
            replaceSupportState(support.pos(), SupportState.CONFLICT);
            return debt("tree_cleanup_support_conflict:" + support.pos().toShortString());
        }
        var breakDecision = BreakPolicy.decide(bot, support.pos());
        if (!breakDecision.allowed()) return debt("tree_cleanup_protected:" + breakDecision.reason());

        BlockPos cleanupStand = support.pos().up();
        if (!bot.getBlockPos().equals(cleanupStand)) {
            if (!Standability.isStandable(world, cleanupStand)) {
                return debt("tree_cleanup_stand_not_available:" + cleanupStand.toShortString());
            }
            // An owned support stand is normally one bounded step/jump away; an elevated one
            // (mixed columns after re-entry) is approached by resuming owned TREE_ACCESS
            // placement beside its column — the same primitive as SAFETY re-entry.  A plain
            // surface route cannot resolve elevated owned stands at all.
            int bdx = Math.abs(cleanupStand.getX() - bot.getBlockPos().getX());
            int bdy = cleanupStand.getY() - bot.getBlockPos().getY();
            int bdz = Math.abs(cleanupStand.getZ() - bot.getBlockPos().getZ());
            if (bdy == 1 && bdx + bdz <= 1) {
                return FakePlayerMotion.jumpTo(bot, cleanupStand, "tree_access_support_reentry")
                        ? StepResult.progress()
                        : debt("tree_cleanup_owned_support_unreachable:" + support.pos().toShortString());
            }
            if (bdy >= -1 && bdy <= 0 && bdx <= 1 && bdz <= 1 && bdx + bdz > 0) {
                return FakePlayerMotion.stepToStandable(bot, cleanupStand, "tree_access_support_reentry")
                        ? StepResult.progress()
                        : debt("tree_cleanup_owned_support_unreachable:" + support.pos().toShortString());
            }
            BlockPos columnBottom = columnBottomOf(support.pos());
            boolean onOwnedTop = supports.stream()
                    .filter(s -> s.state() == SupportState.PLACED)
                    .anyMatch(s -> bot.getBlockPos().equals(s.pos().up()));
            int besideColumn = Math.abs(bot.getBlockPos().getX() - columnBottom.getX())
                    + Math.abs(bot.getBlockPos().getZ() - columnBottom.getZ());
            if (!onOwnedTop
                    && (besideColumn > 1 || bot.getBlockPos().getY() > columnBottom.getY() + 1)) {
                StepResult walking = walkBesideColumn(bot, columnBottom,
                        "tree_cleanup_owned_support_unreachable:" + support.pos().toShortString());
                if (walking != null) return walking;
            }
            return placeOneSupportCell(bot);
        }

        String descentProblem = validateSafeDescentAfterRemoval(bot, support.pos());
        if (descentProblem != null) return debt(descentProblem);

        if (cleanupMiner == null || cleanupMiningPos == null || !cleanupMiningPos.equals(support.pos())) {
            cleanupMiner = new MiningController(support.pos(), Direction.UP);
            cleanupMiningPos = support.pos().toImmutable();
        }
        Block removedBlock = actual.getBlock();
        ActionResult mining = cleanupMiner.tick(bot.getActionPack());
        if (mining.isFailed()) {
            cleanupMiner.abort(bot);
            return debt("tree_cleanup_mining_failed:" + mining.reason());
        }
        if (mining.isInProgress()) return StepResult.progress();
        cleanupMiner = null;
        cleanupMiningPos = null;
        if (!world.getBlockState(support.pos()).isAir()) {
            return debt("tree_cleanup_block_still_present:" + support.pos().toShortString());
        }
        replaceSupportState(support.pos(), SupportState.REMOVED);
        if (!FakePlayerMotion.stepToStandable(bot, support.pos(), "tree_access_reverse_cleanup")) {
            return debt("tree_cleanup_descent_failed:" + support.pos().toShortString());
        }
        Item recovered = removedBlock.asItem();
        if (recovered != Items.AIR) HarvestCore.forcePickupNearby(bot, recovered);
        BotLog.action(bot, "tree_support_removed",
                "tree", treeId,
                "execution", ownerExecution,
                "pos", support.pos().toShortString(),
                "block", support.blockId());
        return StepResult.progress();
    }

    private StepResult placeOneSupport(AIPlayerEntity bot, BlockPos target) {
        if (HarvestCore.canReach(bot, target) && ObservableWorldQuery.canObserveBlock(bot, target)) {
            resetAccessTransient();
            return StepResult.ready();
        }
        return placeOneSupportCell(bot);
    }

    /** Place one owned TREE_ACCESS support under the bot's feet (validated vanilla placement). */
    private StepResult placeOneSupportCell(AIPlayerEntity bot) {
        if (supports.stream().filter(s -> s.state() == SupportState.PLACED).count() >= MAX_TEMP_SUPPORTS) {
            return StepResult.blocked("tree_access_support_limit");
        }
        BlockPos place = bot.getBlockPos().toImmutable();
        ServerWorld world = bot.getServerWorld();
        if (!world.getBlockState(place).isAir() || !world.getFluidState(place).isEmpty()) {
            return StepResult.blocked("tree_access_place_cell_not_air");
        }
        var placementPolicy = BreakPolicy.decide(bot, place);
        if (!placementPolicy.allowed()) return StepResult.blocked("tree_access_protected:" + placementPolicy.reason());
        BlockPos up = place.up();
        if (!world.getBlockState(up).getCollisionShape(world, up).isEmpty()
                || !world.getBlockState(up.up()).getCollisionShape(world, up.up()).isEmpty()
                || !world.getFluidState(up).isEmpty()) {
            return StepResult.blocked("tree_access_headroom_blocked");
        }
        OptionalInt slot = MaterialPalette.pickPathSupportBlockSlot(bot);
        if (slot.isEmpty()) return StepResult.blocked("tree_access_no_support_material");
        Item supportItem = bot.getInventory().getStack(slot.getAsInt()).getItem();
        if (!(supportItem instanceof BlockItem)) return StepResult.blocked("tree_access_support_not_block_item");
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        String original = Registries.BLOCK.getId(world.getBlockState(place).getBlock()).toString();
        if (!FakePlayerMotion.jumpTo(bot, up, "tree_access_pillar_jump")) {
            return StepResult.blocked("tree_access_jump_blocked");
        }
        ActionResult placed = BuildAction.placeBlockAt(bot, place);
        if (placed.isFailed()) {
            FakePlayerMotion.stepToStandable(bot, place, "tree_access_place_rollback");
            return StepResult.blocked("tree_access_place_failed:" + placed.reason());
        }
        BlockState after = world.getBlockState(place);
        if (after.isAir()) {
            FakePlayerMotion.stepToStandable(bot, place, "tree_access_missing_support_rollback");
            return debt("tree_access_place_success_without_block");
        }
        String blockId = Registries.BLOCK.getId(after.getBlock()).toString();
        supports.add(new TemporarySupport(place, blockId, ownerExecution, "TREE_ACCESS",
                original, bot.getServer().getTicks(), SupportState.PLACED));
        BotLog.action(bot, "tree_support_placed",
                "tree", treeId,
                "execution", ownerExecution,
                "pos", place.toShortString(),
                "block", blockId);
        return StepResult.progress();
    }

    private BlockPos chooseAccessBase(AIPlayerEntity bot, BlockPos target) {
        ServerWorld world = bot.getServerWorld();
        ArrayList<BlockPos> candidates = new ArrayList<>();
        int floor = Math.max(world.getBottomY() + 1, target.getY() - MAX_ACCESS_BASE_DROP);
        int[][] offsets = {
                // After lower committed trunk cells are resolved, the old trunk column itself is
                // the safest access shaft: it avoids guessing through the leaf canopy.
                {0, 0},
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2},
                {3, 0}, {-3, 0}, {0, 3}, {0, -3}
        };
        for (int[] offset : offsets) {
            for (int y = target.getY(); y >= floor; y--) {
                BlockPos feet = new BlockPos(target.getX() + offset[0], y, target.getZ() + offset[1]);
                if (rejectedAccessBases.contains(feet.asLong())) continue;
                Standability.clearCache();
                if (!Standability.isStandable(world, feet)) continue;
                BlockState ground = world.getBlockState(feet.down());
                if (ground.isIn(BlockTags.LOGS) || ground.isIn(BlockTags.LEAVES) || Standability.isDangerous(ground)) continue;
                if (!clearAccessColumn(world, feet, target)) continue;
                candidates.add(feet.toImmutable());
                break;
            }
        }
        return candidates.stream()
                .min(Comparator.comparingDouble((BlockPos p) -> p.getSquaredDistance(bot.getBlockPos()))
                        .thenComparingInt(BlockPos::getY)
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ))
                .orElse(null);
    }

    private static boolean clearAccessColumn(ServerWorld world, BlockPos base, BlockPos target) {
        int topFeet = Math.max(base.getY(), target.getY() - 3);
        for (int y = base.getY() + 1; y <= topFeet + 1; y++) {
            BlockPos cell = new BlockPos(base.getX(), y, base.getZ());
            if (!world.getFluidState(cell).isEmpty()
                    || !world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) return false;
        }
        return true;
    }

    private String validateSafeDescentAfterRemoval(AIPlayerEntity bot, BlockPos support) {
        ServerWorld world = bot.getServerWorld();
        if (!bot.getBlockPos().equals(support.up())) return "tree_cleanup_not_above_support";
        BlockState below = world.getBlockState(support.down());
        if (!world.getFluidState(support).isEmpty() || !world.getFluidState(support.down()).isEmpty()) {
            return "tree_cleanup_fluid_below_support";
        }
        if (below.getCollisionShape(world, support.down()).isEmpty() || Standability.isDangerous(below)) {
            return "tree_cleanup_no_safe_landing_support";
        }
        if (!world.getBlockState(support.up()).getCollisionShape(world, support.up()).isEmpty()
                || !world.getBlockState(support.up(2)).getCollisionShape(world, support.up(2)).isEmpty()) {
            return "tree_cleanup_landing_column_blocked";
        }
        return null;
    }

    private boolean scopeMatches(AIPlayerEntity bot) {
        if (bot == null || !dimension.equals(dimension(bot))) return false;
        try { return worldId.equals(SemanticWorldRegistry.worldId()); }
        catch (RuntimeException missingRegistry) { return false; }
    }

    private StepResult debt(String reason) {
        if (cleanupDebt.isBlank()) cleanupDebt = reason == null ? "tree_cleanup_debt" : reason;
        return StepResult.debt(cleanupDebt);
    }

    private TemporarySupport latestPlacedSupport() {
        for (int i = supports.size() - 1; i >= 0; i--) {
            TemporarySupport support = supports.get(i);
            if (support.state() == SupportState.PLACED) return support;
        }
        return null;
    }

    /** Highest still-placed receipt; earliest in ledger order on ties. */
    private TemporarySupport topmostPlacedSupport() {
        TemporarySupport best = null;
        for (TemporarySupport support : supports) {
            if (support.state() != SupportState.PLACED) continue;
            if (best == null || support.pos().getY() > best.pos().getY()) best = support;
        }
        return best;
    }

    private void replaceSupportState(BlockPos pos, SupportState state) {
        for (int i = supports.size() - 1; i >= 0; i--) {
            TemporarySupport support = supports.get(i);
            if (support.pos().equals(pos) && support.state() == SupportState.PLACED) {
                supports.set(i, support.withState(state));
                return;
            }
        }
    }

    private void resetAccessTransient() {
        // The exact working target is part of the support transaction identity.  Do not erase it
        // while owned supports remain; SAFETY resume needs it to prove same-target re-entry.
        if (!hasTemporarySupports()) accessTarget = null;
        accessBase = null;
        accessPathStarted = false;
        reentryChain = null;
        reentrySettleTicks = 0;
        reentryRegroundSteps = 0;
        accessTicks = 0;
        rejectedAccessBases.clear();
    }

    private void resetCleanupTransient() {
        cleanupMiner = null;
        cleanupMiningPos = null;
        cleanupTicks = 0;
    }

    private static Set<BlockPos> positions(Set<Long> packed) {
        LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
        for (long value : packed) result.add(BlockPos.fromLong(value));
        return Set.copyOf(result);
    }

    private static String dimension(AIPlayerEntity bot) {
        return bot.getServerWorld().getRegistryKey().getValue().toString();
    }
}
