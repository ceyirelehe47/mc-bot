package io.github.zoyluo.aibot.external.realclient;

import io.github.zoyluo.aibot.craft.CraftingHelper;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务端背包变换合成(Real Client 身体):配方真实匹配、材料真实扣减、产物真实入包。
 * 无 GUI、无创造物品;工作台需求沿用上游语义("附近有方块 或 背包持有 即可 3x3")。
 * Planner 语义与上游 CraftingHelper 一致(事务性深拷贝规划);执行原语与上游 CraftTask 一致
 * (先深拷贝预演、容量证明后才 commit 活背包)。AIPlayerEntity 类型依赖在此解耦。
 */
public final class InventoryCrafting {
    private InventoryCrafting() {
    }

    public record Plan(CraftingHelper.CraftPlan plan) {
        public boolean success() {
            return plan.success();
        }
    }

    /** 返回 null=成功(产出 target 的真实数量);非 null=失败原因。 */
    public static String execute(ServerPlayerEntity player, CraftingHelper.CraftPlan plan, Item target) {
        int[] crafted = {0};
        for (CraftingHelper.CraftStep step : plan.steps()) {
            String failure = craftStep(player, step, target, crafted);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private static String craftStep(
            ServerPlayerEntity player, CraftingHelper.CraftStep step, Item target, int[] crafted) {
        RecipeRegistry.Recipe recipe = step.recipe();
        if (recipe.needsCraftingTable()
                && !nearbyCraftingTable(player)
                && !holdingCraftingTable(player)) {
            return "needs_crafting_table";
        }
        PreparedCraft prepared = prepareCraft(player, step);
        if (prepared.missingIngredient() != null) {
            return "need:" + Registries.ITEM.getId(
                    prepared.missingIngredient().anyOf().isEmpty()
                            ? recipe.output()
                            : prepared.missingIngredient().anyOf().get(0))
                    + ":x" + prepared.missingCount();
        }
        if (prepared.availableOutput() < step.outputCount()) {
            return "craft_output_capacity:item="
                    + Registries.ITEM.getId(recipe.output())
                    + ":count=" + step.outputCount()
                    + ":available=" + prepared.availableOutput();
        }
        commit(player, prepared);
        if (recipe.output() == target) {
            crafted[0] += step.outputCount();
        }
        return null;
    }

    static boolean nearbyCraftingTable(ServerPlayerEntity player) {
        BlockPos origin = player.getBlockPos();
        return BlockPos.stream(origin.add(-8, -2, -8), origin.add(8, 3, 8))
                .anyMatch(pos -> player.getServerWorld()
                        .getBlockState(pos).isOf(Blocks.CRAFTING_TABLE));
    }

    private static boolean holdingCraftingTable(ServerPlayerEntity player) {
        return countItem(player, net.minecraft.item.Items.CRAFTING_TABLE) > 0;
    }

    public static int countItem(ServerPlayerEntity player, Item item) {
        int count = 0;
        PlayerInventory inventory = player.getInventory();
        for (List<ItemStack> region : List.of(inventory.main, inventory.offHand)) {
            for (ItemStack stack : region) {
                if (stack.isOf(item)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    // ---------- 事务预演 + 提交(与上游 CraftTask 同语义) ----------

    private static PreparedCraft prepareCraft(ServerPlayerEntity player, CraftingHelper.CraftStep step) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> main = copyStacks(inventory.main);
        List<ItemStack> offHand = copyStacks(inventory.offHand);
        for (RecipeRegistry.Ingredient ingredient : step.recipe().ingredients()) {
            int required = ingredient.count() * step.crafts();
            if (!removeIngredient(main, offHand, ingredient, required)) {
                return new PreparedCraft(main, offHand, ingredient, required, 0);
            }
        }
        ItemStack output = new ItemStack(step.recipe().output(), step.outputCount());
        int available = outputCapacity(main, output);
        if (available >= output.getCount()) {
            insertEntireStack(main, output);
        }
        return new PreparedCraft(main, offHand, null, 0, available);
    }

    private static void commit(ServerPlayerEntity player, PreparedCraft prepared) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            inventory.main.set(slot, prepared.main().get(slot));
        }
        for (int slot = 0; slot < inventory.offHand.size(); slot++) {
            inventory.offHand.set(slot, prepared.offHand().get(slot));
        }
        inventory.markDirty();
    }

    private static List<ItemStack> copyStacks(List<ItemStack> source) {
        return source.stream().map(ItemStack::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static boolean removeIngredient(
            List<ItemStack> main, List<ItemStack> offHand,
            RecipeRegistry.Ingredient ingredient, int count) {
        int total = 0;
        for (Item item : ingredient.anyOf()) {
            total += countInRegions(main, offHand, item);
        }
        if (total < count) {
            return false;
        }
        int remaining = count;
        for (Item item : ingredient.anyOf()) {
            if (remaining <= 0) {
                return true;
            }
            for (List<ItemStack> region : List.of(main, offHand)) {
                for (ItemStack stack : region) {
                    if (remaining <= 0) {
                        return true;
                    }
                    if (!stack.isOf(item)) {
                        continue;
                    }
                    int take = Math.min(remaining, stack.getCount());
                    stack.decrement(take);
                    remaining -= take;
                }
            }
        }
        return remaining == 0;
    }

    private static int countInRegions(List<ItemStack> main, List<ItemStack> offHand, Item item) {
        int count = 0;
        for (List<ItemStack> region : List.of(main, offHand)) {
            for (ItemStack stack : region) {
                if (stack.isOf(item)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    private static int outputCapacity(List<ItemStack> main, ItemStack output) {
        long available = 0L;
        for (ItemStack stack : main) {
            if (stack.isEmpty()) {
                available += output.getMaxCount();
            } else if (ItemStack.areItemsAndComponentsEqual(stack, output)) {
                available += Math.max(0, stack.getMaxCount() - stack.getCount());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, available);
    }

    private static void insertEntireStack(List<ItemStack> main, ItemStack output) {
        for (ItemStack stack : main) {
            if (output.isEmpty()) {
                return;
            }
            if (!stack.isEmpty() && ItemStack.areItemsAndComponentsEqual(stack, output)) {
                int moved = Math.min(output.getCount(), stack.getMaxCount() - stack.getCount());
                if (moved > 0) {
                    stack.increment(moved);
                    output.decrement(moved);
                }
            }
        }
        for (int slot = 0; slot < main.size() && !output.isEmpty(); slot++) {
            if (!main.get(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(output.getCount(), output.getMaxCount());
            main.set(slot, output.copyWithCount(moved));
            output.decrement(moved);
        }
        if (!output.isEmpty()) {
            throw new IllegalStateException("craft output preflight capacity mismatch");
        }
    }

    private record PreparedCraft(
            List<ItemStack> main,
            List<ItemStack> offHand,
            RecipeRegistry.Ingredient missingIngredient,
            int missingCount,
            int availableOutput) {
    }
}
