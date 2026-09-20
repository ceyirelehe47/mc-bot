package io.github.zoyluo.aibot.client.realclient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

/**
 * MC-RCF-1 G3: native inventory transaction primitives.
 *
 * <p>Every mutation goes through {@code interactionManager.clickSlot} on the
 * CURRENT screen handler — the exact same packet path a real player produces.
 * Semantic positions (hotbar_N/main_N) map to handler slot ids HERE; callers
 * never guess raw ids. Item identity is compared by registry id AND stack
 * count; same-id-different-component stacks are never blindly merged on the
 * client side — the server driver owns protection/quota checks.</p>
 */
final class RealClientInventoryOps {
    private RealClientInventoryOps() {}

    // PlayerScreenHandler 固定布局(不适用于外部容器!)
    static final int PLAYER_CRAFT_RESULT=0;
    static final int PLAYER_CRAFT_START=1;          // 1..4 (2x2)
    static final int PLAYER_ARMOR_START=5;          // 5..8
    static final int PLAYER_MAIN_START=9;           // 9..35
    static final int PLAYER_HOTBAR_START=36;        // 36..44
    static final int PLAYER_OFFHAND=45;

    static String itemId(ItemStack stack) {
        return stack.isEmpty()?"":Registries.ITEM
                .getId(stack.getItem()).toString();
    }

    static boolean is(ItemStack stack,String id) {
        return !stack.isEmpty()&&itemId(stack).equals(id);
    }

    /** 在 handler 的 [from,to) 范围找指定物品的堆叠。 */
    static int findStack(
            ScreenHandler handler,String id,int from,int to) {
        for(int slot=from;slot<to&&slot<handler.slots.size();slot++) {
            if(is(handler.slots.get(slot).getStack(),id))
                return slot;
        }
        return -1;
    }

    /** 在 [from,to) 找空槽。 */
    static int findEmpty(
            ScreenHandler handler,int from,int to) {
        for(int slot=from;slot<to&&slot<handler.slots.size();slot++) {
            if(handler.slots.get(slot).getStack().isEmpty())
                return slot;
        }
        return -1;
    }

    /** 当前手持快捷栏物品 id(客户端权威)。 */
    static String heldItemId(MinecraftClient client) {
        ClientPlayerEntity p=client.player;
        if(p==null)return "";
        return itemId(p.getInventory().getMainHandStack());
    }

    /** 原生槽位点击(与真实玩家同一 C2S 路径)。 */
    static void click(
            MinecraftClient client,ScreenHandler handler,
            int slot,int button,SlotActionType type) {
        client.interactionManager.clickSlot(
                handler.syncId,slot,button,type,client.player);
    }

    /** 语义快捷栏格号(0-8)→玩家屏 slot id。 */
    static int hotbarSlot(int semantic) {
        if(semantic<0||semantic>8)
            throw new IllegalArgumentException("hotbar semantic 0..8");
        return PLAYER_HOTBAR_START+semantic;
    }

    /** 玩家库存区基址随 handler 变化:PlayerScreenHandler=9/36,
     *  CraftingScreenHandler=10/37(实测余料回 9 号会落进合成格)。 */
    static int invMainStart(ScreenHandler h) {
        return h instanceof net.minecraft.screen.CraftingScreenHandler
                ||h instanceof net.minecraft.screen.ForgingScreenHandler
                ||h instanceof net.minecraft.screen.AnvilScreenHandler
                        ?10:9;
    }

    static int invHotbarStart(ScreenHandler h) {
        return invMainStart(h)==10?37:36;
    }

    /** 光标持有指定物品且数量≥min。 */
    static boolean cursorHolds(
            MinecraftClient client,String id,int min) {
        ItemStack cursor=client.player.currentScreenHandler
                .getCursorStack();
        return is(cursor,id)&&cursor.getCount()>=min;
    }
}
