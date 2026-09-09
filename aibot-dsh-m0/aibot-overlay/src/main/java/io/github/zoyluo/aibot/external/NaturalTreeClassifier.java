package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Conservative, deterministic evidence test for natural tree logs. Unknown logs are NOT resources. */
public final class NaturalTreeClassifier {
    private static final int MAX_LOGS = 32;
    private static final int MAX_HORIZONTAL_FROM_SEED = 5;
    private static final int MAX_VERTICAL_FROM_SEED = 10;
    private static final int MIN_LOGS = 2;
    private static final int MIN_NEARBY_LEAVES = 4;

    private NaturalTreeClassifier() {}

    public static boolean isHarvestCandidate(AIPlayerEntity bot, BlockPos pos) {
        if (!BreakPolicy.mayBreak(bot, pos)) {
            return false;
        }
        BlockState state = bot.getServerWorld().getBlockState(pos);
        return !state.isIn(BlockTags.LOGS) || isNaturalTreeLog(bot, pos);
    }

    public static boolean isNaturalTreeLog(AIPlayerEntity bot, BlockPos seed) {
        if (bot == null || seed == null || !ExternalBodyAccess.reserved(bot)) {
            return bot != null && seed != null && bot.getServerWorld().getBlockState(seed).isIn(BlockTags.LOGS);
        }
        ServerWorld world = bot.getServerWorld();
        if (!world.getBlockState(seed).isIn(BlockTags.LOGS) || !BreakPolicy.mayBreak(bot, seed)) {
            return false;
        }

        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<Long> logs = new HashSet<>();
        Set<Long> leaves = new HashSet<>();
        open.add(seed.toImmutable());
        logs.add(seed.asLong());
        boolean rooted = false;

        while (!open.isEmpty() && logs.size() <= MAX_LOGS) {
            BlockPos current = open.removeFirst();
            if (isPlausibleRoot(world.getBlockState(current.down()))
                    && !world.getBlockState(current.down()).isIn(BlockTags.LOGS)) {
                rooted = true;
            }
            for (BlockPos nearby : BlockPos.iterate(current.add(-1, -1, -1), current.add(1, 1, 1))) {
                if (world.getBlockState(nearby).isIn(BlockTags.LEAVES)) {
                    leaves.add(nearby.asLong());
                }
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = current.offset(direction);
                if (Math.abs(next.getX() - seed.getX()) > MAX_HORIZONTAL_FROM_SEED
                        || Math.abs(next.getZ() - seed.getZ()) > MAX_HORIZONTAL_FROM_SEED
                        || Math.abs(next.getY() - seed.getY()) > MAX_VERTICAL_FROM_SEED) {
                    continue;
                }
                if (!world.getBlockState(next).isIn(BlockTags.LOGS)
                        || !BreakPolicy.mayBreak(bot, next)
                        || !logs.add(next.asLong())) {
                    continue;
                }
                open.addLast(next.toImmutable());
                if (logs.size() > MAX_LOGS) {
                    return false; // giant/ambiguous log structures fail closed in this slice
                }
            }
        }
        return rooted && logs.size() >= MIN_LOGS && leaves.size() >= MIN_NEARBY_LEAVES;
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
}
