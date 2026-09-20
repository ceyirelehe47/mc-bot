package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Conservative natural-tree proof plus one active-gather scoped cluster lease. */
public final class NaturalTreeClassifier {
    private static final int MAX_LOGS = 32;
    private static final int MAX_HORIZONTAL_FROM_SEED = 5;
    private static final int MAX_VERTICAL_FROM_SEED = 10;
    private static final int MIN_LOGS = 2;
    private static final int MIN_NEARBY_LEAVES = 4;
    private static final Map<UUID, HarvestLease> LEASES = new HashMap<>();

    private NaturalTreeClassifier() {}

    private record Cluster(Set<Long> logs, boolean rooted, int leaves) {}
    private record HarvestLease(Task owner, String dimension, Set<Long> logs) {}

    /** Immutable proof frozen at initial natural-tree admission. It never expands after root removal. */
    public record HarvestClusterProof(String dimension, Set<Long> logs) {
        public HarvestClusterProof {
            dimension = dimension == null ? "" : dimension;
            logs = logs == null ? Set.of() : Set.copyOf(logs);
        }
    }

    public static boolean isHarvestCandidate(AIPlayerEntity bot, BlockPos pos) {
        if (!BreakPolicy.mayBreak(bot, pos)) return false;
        BlockState state = bot.getServerWorld().getBlockState(pos);
        if (!state.isIn(BlockTags.LOGS)) return true;
        return leasedToActiveGather(bot, pos) || isNaturalTreeLog(bot, pos);
    }

    /**
     * Freeze the initially-proven connected log set to the current active gather task. Without this,
     * chopping the dirt-rooted trunk destroys the evidence used to classify the still-natural upper
     * trunk, leaving floating trees. The lease never authorizes protected cells and never survives
     * task replacement/restart.
     */
    public static Optional<HarvestClusterProof> acquireHarvestCluster(AIPlayerEntity bot, BlockPos seed) {
        if (bot == null || seed == null || !ExternalBodyAccess.reserved(bot)) return Optional.empty();
        Task owner = TaskManager.INSTANCE.getActive(bot).orElse(null);
        if (owner == null || !"gather".equals(owner.name())) return Optional.empty();
        if (!bot.getServerWorld().getBlockState(seed).isIn(BlockTags.LOGS) || !BreakPolicy.mayBreak(bot, seed)) return Optional.empty();
        Cluster cluster = inspect(bot, seed);
        if (!cluster.rooted || cluster.logs.size() < MIN_LOGS || cluster.leaves < MIN_NEARBY_LEAVES) return Optional.empty();
        String dimension = dimension(bot);
        Set<Long> frozen = Set.copyOf(cluster.logs);
        LEASES.put(bot.getUuid(), new HarvestLease(owner, dimension, frozen));
        return Optional.of(new HarvestClusterProof(dimension, frozen));
    }

    public static boolean isNaturalTreeLog(AIPlayerEntity bot, BlockPos seed) {
        if (bot == null || seed == null) return false;
        if (!ExternalBodyAccess.reserved(bot)) return bot.getServerWorld().getBlockState(seed).isIn(BlockTags.LOGS);
        if (!bot.getServerWorld().getBlockState(seed).isIn(BlockTags.LOGS) || !BreakPolicy.mayBreak(bot, seed)) return false;
        Cluster cluster = inspect(bot, seed);
        return cluster.rooted && cluster.logs.size() >= MIN_LOGS && cluster.leaves >= MIN_NEARBY_LEAVES;
    }

    private static boolean leasedToActiveGather(AIPlayerEntity bot, BlockPos pos) {
        HarvestLease lease = LEASES.get(bot.getUuid());
        if (lease == null) return false;
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        Task paused = TaskManager.INSTANCE.peekPaused(bot).orElse(null);
        boolean ownerStillManaged = (active == lease.owner || paused == lease.owner)
                && (lease.owner.state() == io.github.zoyluo.aibot.task.TaskState.RUNNING
                || lease.owner.state() == io.github.zoyluo.aibot.task.TaskState.PAUSED);
        if (!ownerStillManaged || !lease.dimension.equals(dimension(bot))) {
            LEASES.remove(bot.getUuid());
            return false;
        }
        return lease.logs.contains(pos.asLong()) && BreakPolicy.mayBreak(bot, pos)
                && bot.getServerWorld().getBlockState(pos).isIn(BlockTags.LOGS);
    }

    private static Cluster inspect(AIPlayerEntity bot, BlockPos seed) {
        ServerWorld world = bot.getServerWorld();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<Long> logs = new HashSet<>();
        Set<Long> leaves = new HashSet<>();
        open.add(seed.toImmutable());
        logs.add(seed.asLong());
        boolean rooted = false;

        while (!open.isEmpty() && logs.size() <= MAX_LOGS) {
            BlockPos current = open.removeFirst();
            if (isPlausibleRoot(world.getBlockState(current.down()))
                    && !world.getBlockState(current.down()).isIn(BlockTags.LOGS)) rooted = true;
            for (BlockPos nearby : BlockPos.iterate(current.add(-1, -1, -1), current.add(1, 1, 1))) {
                if (world.getBlockState(nearby).isIn(BlockTags.LEAVES)) leaves.add(nearby.asLong());
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = current.offset(direction);
                if (Math.abs(next.getX() - seed.getX()) > MAX_HORIZONTAL_FROM_SEED
                        || Math.abs(next.getZ() - seed.getZ()) > MAX_HORIZONTAL_FROM_SEED
                        || Math.abs(next.getY() - seed.getY()) > MAX_VERTICAL_FROM_SEED) continue;
                if (!world.getBlockState(next).isIn(BlockTags.LOGS)
                        || !BreakPolicy.mayBreak(bot, next)
                        || !logs.add(next.asLong())) continue;
                open.addLast(next.toImmutable());
                if (logs.size() > MAX_LOGS) return new Cluster(Set.of(), false, 0);
            }
        }
        return new Cluster(Set.copyOf(logs), rooted, leaves.size());
    }

    private static boolean isPlausibleRoot(BlockState state) {
        return state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.PODZOL)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT)
                || state.isOf(Blocks.MYCELIUM)
                || state.isOf(Blocks.MOSS_BLOCK);
    }

    private static String dimension(AIPlayerEntity bot) {
        return bot.getServerWorld().getRegistryKey().getValue().toString();
    }
}
