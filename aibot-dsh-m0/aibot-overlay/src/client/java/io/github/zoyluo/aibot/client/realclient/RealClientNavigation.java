package io.github.zoyluo.aibot.client.realclient;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * MC-RCF-1-R1 C2: constrained Baritone navigation adapter.
 *
 * <p>Library is ONLY a route executor. Constraints are re-asserted and
 * VERIFIED on every admission (R1: the old {@code if(constrained) return}
 * let a tampered setting pass as constrained). Arrival is proven by real
 * 3D position, never by {@code !isPathing()} — "no path task", "planning
 * failed" and "arrived" are distinct states.</p>
 */
public final class RealClientNavigation {
    private RealClientNavigation() {}

    private static BlockPos currentGoal;
    private static int currentRadius=-1;

    public static boolean available() {
        try {
            return net.fabricmc.loader.api.FabricLoader
                    .getInstance().isModLoaded("baritone");
        } catch (Throwable t) {
            return false;
        }
    }

    /** R1-C2: re-assert AND verify the read-only-terrain profile on every
     *  admission. Tampered values are restored; if a value still reads back
     *  wrong we refuse instead of navigating unconstrained. */
    private static void ensureConstrained() {
        try {
            var s=BaritoneAPI.getSettings();
            s.allowBreak.value=false;
            s.allowPlace.value=false;
            s.allowInventory.value=false;
            s.chatControl.value=false;
            if(s.allowBreak.value||s.allowPlace.value
                    ||s.allowInventory.value||s.chatControl.value)
                throw new IllegalStateException(
                        "baritone_settings_tampered_unrecoverable");
        } catch(IllegalStateException ise) {
            throw ise;
        } catch(Throwable t) {
            throw new IllegalStateException(
                    "baritone_settings_unconstrainable",t);
        }
    }

    /** Path to the exact stand block (radius<1: GoalBlock, 3D including y)
     *  or an approach goal (radius>=1: GoalNear — dig/interact actions do
     *  their own final approach; canopy targets are unreachable with
     *  GoalBlock, measured). */
    public static void pathTo(MinecraftClient client,Vec3d target,double radius) {
        ensureConstrained();
        BlockPos goal=BlockPos.ofFloored(target.x,target.y,target.z);
        if(goal.equals(currentGoal)&&pathing())
            return;
        currentGoal=goal;
        // R2 诊断修复:radius<1 必须是 GoalBlock——旧代码 ceil(0.5)=1
        // 把"精确站格"退化成 GoalNear(1),站在目标邻格即视为到达,
        // bodyInTarget 走出目标格永远不发生(诊断链 place 卡死根因)。
        currentRadius=Math.max(0,(int)Math.floor(radius));
        BaritoneAPI.getProvider().getPrimaryBaritone()
                .getCustomGoalProcess()
                .setGoalAndPath(currentRadius>=1
                        ?new GoalNear(goal,currentRadius)
                        :new GoalBlock(goal));
    }

    /** True while the library is planning or walking the current goal. */
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
        // MC-RCF-1-R3:cancelEverything 只取消当前路径段,custom goal
        // 进程仍持有 goal 并自发重新寻路,其旋转行为每 tick 覆写
        // lookAt 的精确视角(实测卡在相差 ~3° 的平滑值,准星永远落
        // 邻格 → facing_timeout)。必须把 goal 一并清掉。
        try {
            BaritoneAPI.getProvider().getPrimaryBaritone()
                    .getCustomGoalProcess().setGoal(null);
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
            client.options.sneakKey.setPressed(false);
        }
    }

    /** R1-C2: arrival = real 3D position inside the goal envelope.
     *  GoalBlock: exact block (y included). GoalNear: horizontal distance
     *  within radius (+ step tolerance) and y within 2 blocks. */
    public static boolean atGoal(MinecraftClient client) {
        if(currentGoal==null||client==null||client.player==null)
            return false;
        BlockPos p=client.player.getBlockPos();
        if(currentRadius>=1) {
            int dx=p.getX()-currentGoal.getX();
            int dz=p.getZ()-currentGoal.getZ();
            int dy=p.getY()-currentGoal.getY();
            return dx*dx+dz*dz<=currentRadius*currentRadius+1
                    &&Math.abs(dy)<=2;
        }
        return p.equals(currentGoal);
    }

    /** Three-state navigation outcome. R1-C2: {@code !pathing()} alone is
     *  NOT arrival — planning failure and no-task must never report
     *  success. Kept for callers that still want the boolean; prefer
     *  {@link #atGoal}. */
    public static boolean goalReached(MinecraftClient client) {
        return atGoal(client);
    }
}
