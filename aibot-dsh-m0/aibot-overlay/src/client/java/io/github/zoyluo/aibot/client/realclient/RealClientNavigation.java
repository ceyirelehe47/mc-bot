package io.github.zoyluo.aibot.client.realclient;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * MC-RCF-1 G2: constrained Baritone navigation adapter.
 *
 * <p>The library is used ONLY as a route executor for goals handed down by the
 * body controller. Resource scanning, autonomous mining and free exploration of
 * the library are structurally disarmed: {@code allowBreak}/{@code allowPlace}/
 * {@code allowInventory}/{@code allowDownload} are forced false on every call
 * (defence in depth on top of the locked settings file). Terrain changes remain
 * unsupported this round: {@code allow_terrain_changes=true} is rejected by the
 * server driver, never silently ignored here.</p>
 *
 * <p>All input authority stays with the single actuator: {@link #stop(MinecraftClient)}
 * cancels the pathing process and releases keys; the caller clears the rest.</p>
 */
public final class RealClientNavigation {
    private RealClientNavigation() {}

    private static BlockPos currentGoal;
    private static int currentRadius=-1;
    private static boolean constrained;

    public static boolean available() {
        try {
            return net.fabricmc.loader.api.FabricLoader
                    .getInstance().isModLoaded("baritone");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Force the library into a read-only-terrain profile on every entry.
     *  chatControl=false closes the unauthorized chat command entrance;
     *  navigation is driven ONLY by the body controller through this adapter. */
    private static void ensureConstrained() {
        if(constrained) return;
        try {
            BaritoneAPI.getSettings().allowBreak.value=false;
            BaritoneAPI.getSettings().allowPlace.value=false;
            BaritoneAPI.getSettings().allowInventory.value=false;
            BaritoneAPI.getSettings().chatControl.value=false;
            constrained=true;
        } catch(Throwable t) {
            // settings unreachable: treat as unavailable rather than run unconstrained
            throw new IllegalStateException(
                    "baritone_settings_unconstrainable",t);
        }
    }

    /** Path to the exact stand block (GoalBlock: 3D 含 y;"到指定可站格"语义)。
     *  GoalNear 只保水平距离,地形起伏会造成 y 失配假到达,弃用。 */
    public static void pathTo(MinecraftClient client,Vec3d target,double radius) {
        ensureConstrained();
        BlockPos goal=BlockPos.ofFloored(target.x,target.y,target.z);
        if(goal.equals(currentGoal)&&pathing())
            return;
        currentGoal=goal;
        currentRadius=Math.max(0,(int)Math.ceil(radius));
        BaritoneAPI.getProvider().getPrimaryBaritone()
                .getCustomGoalProcess()
                .setGoalAndPath(new GoalBlock(goal));
    }

    /** True while the library believes it still has work for the current goal. */
    public static boolean pathing() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone()
                    .getPathingBehavior().isPathing();
        } catch(Throwable t) {
            return false;
        }
    }

    /** Cancel the pathing process and release movement keys. Old processes
     *  must never keep writing input after cancel/terminal/session loss. */
    public static void stop(MinecraftClient client) {
        try {
            BaritoneAPI.getProvider().getPrimaryBaritone()
                    .getPathingBehavior().cancelEverything();
        } catch(Throwable ignored) {
        }
        currentGoal=null;
        currentRadius=-1;
        if(client!=null&&client.options!=null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
        }
    }

    /** Goal reached per the library's own completion report. */
    public static boolean goalReached() {
        try {
            return currentGoal==null||!pathing();
        } catch(Throwable t) {
            return true;
        }
    }
}
