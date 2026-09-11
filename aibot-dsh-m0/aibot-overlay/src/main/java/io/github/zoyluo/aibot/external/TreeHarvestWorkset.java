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
    private final LinkedHashSet<Long> rejectedAccessBases = new LinkedHashSet<>();

    private BlockPos cleanupPathTarget;
    private boolean cleanupPathStarted;
    private MiningController cleanupMiner;
    private BlockPos cleanupMiningPos;
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
        cleanupPathStarted = false;
        cleanupMiner = null;
        cleanupMiningPos = null;
    }

    public void abandon(String reason) {
        abandonReason = reason == null ? "abandoned" : reason;
        accessPathStarted = false;
        cleanupPathStarted = false;
        cleanupMiner = null;
        cleanupMiningPos = null;
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
        if (HarvestCore.canReach(bot, target) && ObservableWorldQuery.canObserveBlock(bot, target)) {
            resetAccessTransient();
            return StepResult.ready();
        }

        if (accessTarget == null || !accessTarget.equals(target)) {
            if (hasTemporarySupports()) {
                return debt("tree_access_target_changed_with_owned_supports");
            }
            resetAccessTransient();
            accessTarget = target.toImmutable();
        }
        if (++accessTicks > MAX_ACCESS_TICKS) return StepResult.blocked("tree_access_timeout");

        if (hasTemporarySupports()) {
            TemporarySupport last = latestPlacedSupport();
            if (last == null || !bot.getBlockPos().equals(last.pos().up())) {
                return debt("tree_access_pose_lost_with_owned_supports");
            }
            return placeOneSupport(bot, target);
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

    /** Reverse-clean exactly this execution's support receipts. */
    public StepResult tickCleanup(AIPlayerEntity bot) {
        if (hasCleanupDebt()) return StepResult.debt(cleanupDebt);
        if (!scopeMatches(bot)) return debt("tree_cleanup_scope_changed");
        if (++cleanupTicks > MAX_CLEANUP_TICKS) return debt("tree_cleanup_timeout");
        TemporarySupport support = latestPlacedSupport();
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
            if (cleanupPathStarted && cleanupPathTarget != null && cleanupPathTarget.equals(cleanupStand)) {
                if (!bot.getActionPack().isPathExecutorIdle()) return StepResult.progress();
                return debt("tree_cleanup_owned_support_unreachable:" + support.pos().toShortString());
            }
            ActionResult path = bot.getActionPack().startSurfacePathTo(cleanupStand);
            BlockPos resolved = bot.getActionPack().activePathGoal();
            if (path.isFailed() || resolved == null || !resolved.equals(cleanupStand)) {
                bot.getActionPack().stopAll();
                return debt("tree_cleanup_owned_support_unreachable:" + support.pos().toShortString());
            }
            cleanupPathStarted = true;
            cleanupPathTarget = cleanupStand.toImmutable();
            return StepResult.progress();
        }
        cleanupPathStarted = false;
        cleanupPathTarget = null;

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
        accessTarget = null;
        accessBase = null;
        accessPathStarted = false;
        accessTicks = 0;
        rejectedAccessBases.clear();
    }

    private void resetCleanupTransient() {
        cleanupPathTarget = null;
        cleanupPathStarted = false;
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
