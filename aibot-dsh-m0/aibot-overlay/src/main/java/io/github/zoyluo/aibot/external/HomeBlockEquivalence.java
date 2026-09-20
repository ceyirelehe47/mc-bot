package io.github.zoyluo.aibot.external;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

/**
 * R2.1 narrow explicit equivalence policy for HOME desired-state baselines.
 *
 * A captured baseline stores exact block ids. Some expected cells drift through ordinary natural
 * mechanics — grass spreading turns captured {@code minecraft:dirt} into {@code minecraft:grass_block}
 * — and the integrity/conflict check used to classify that drift as a destructive "wrong" cell,
 * permanently fail-closing every later repair (observed twice on the live server in LIVE-R2-7).
 *
 * This policy exists ONLY to stop natural state drift from being misread as destructive conflict.
 * It is deliberately an explicit whitelist of block pairs, never a tag/palette family match:
 * structural materials (logs, planks, stone variants, ...) are never equivalent to each other.
 * Baselines keep the original captured id — if an equivalent cell later goes missing, repair still
 * restores the ORIGINAL captured block, and the live cell is never force-overwritten back.
 */
public final class HomeBlockEquivalence {
    private HomeBlockEquivalence() {
    }

    /** Symmetric dirt <-> grass_block: grass spread/survival converts both directions naturally. */
    public static boolean equivalent(Block expected, Block actual) {
        if (expected == null || actual == null || expected == actual) {
            return false; // exact equality is handled by callers before consulting the policy
        }
        return (expected == Blocks.DIRT && actual == Blocks.GRASS_BLOCK)
                || (expected == Blocks.GRASS_BLOCK && actual == Blocks.DIRT);
    }
}
