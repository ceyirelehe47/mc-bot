package io.github.zoyluo.aibot.client.realclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/** Tick-driven normal-client actuator using the same movement and interaction path as a player. */
final class RealClientActionController {
    private final RealClientClientTransport transport;
    private final RealClientScreenController screens;
    private Action active;

    RealClientActionController(
            RealClientClientTransport transport,
            RealClientScreenController screens) {
        this.transport=transport;
        this.screens=screens;
    }

    void command(JsonObject message,MinecraftClient client) {
        String executionId=safeExecutionId(message);
        try {
            commandChecked(message);
        } catch(RuntimeException failure) {
            failMalformedCurrent(
                client,executionId,
                "real_client_command_invalid:"
                        +failure.getClass().getSimpleName());
        }
    }

    private void commandChecked(JsonObject message) {
        String executionId=
                message.get("execution_id").getAsString();
        String operation=
                message.get("operation").getAsString();
        JsonObject args=JsonParser.parseString(
                message.get("arguments_json").getAsString())
                .getAsJsonObject();
        String phase=args.has("phase") && args.get("phase").isJsonPrimitive()
                ?args.get("phase").getAsString():"";

        // Deposit is a two-phase protocol. The server authorizes mutation only after proving
        // the newly opened Screen belongs to the requested target inventory.
        if(active instanceof DepositAction deposit
                && !active.terminal()
                && active.executionId.equals(executionId)
                && "deposit".equals(operation)
                && "commit".equals(
                        args.has("phase")
                                ?args.get("phase").getAsString():"")) {
            deposit.authorize(args);
            return;
        }

        if("deposit".equals(operation) && "commit".equals(phase)) {
            send(executionId,"failed",0D,
                    "real_client_stale_or_unowned_commit");
            return;
        }
        if(active!=null && !active.terminal()) {
            // 服务器执行槽单语义:服务器发出新命令=旧执行已被服务器终止。
            // 残留的旧 action 只可能是 cancel 回执丢失造成的孤儿,直接替换并回报终态。
            send(active.executionId,"failed",active.progress,
                    "client_action_superseded");
            // 输入清理交给新 action 的首个 tick(walkTo/tick 均先 clearInputs+清屏)
        }

        resetLocomotion();
        active=switch(operation) {
            case "say" -> new SayAction(
                    executionId,
                    args.get("message").getAsString());
            case "place" -> new PlaceAction(
                    executionId,
                    args.has("slot")
                            ?args.get("slot").getAsInt():-1,
                    args.get("x").getAsInt(),
                    args.get("y").getAsInt(),
                    args.get("z").getAsInt());
            case "eat" -> new EatAction(
                    executionId,
                    args.has("slot")
                            ?args.get("slot").getAsInt():-1);
            case "goto" -> new GotoAction(
                    executionId,
                    args.get("x").getAsDouble(),
                    args.get("y").getAsDouble(),
                    args.get("z").getAsDouble(),
                    args.has("arrival_radius")
                            ?args.get("arrival_radius").getAsDouble()
                            :2.5D,
                    args.has("face_x")
                            &&args.has("face_y")
                            &&args.has("face_z")
                            ?new BlockPos(
                                    args.get("face_x").getAsInt(),
                                    args.get("face_y").getAsInt(),
                                    args.get("face_z").getAsInt())
                            :null);
            case "mine_opportunity" -> new MineAction(
                    executionId,
                    new BlockPos(
                            args.get("x").getAsInt(),
                            args.get("y").getAsInt(),
                            args.get("z").getAsInt()),
                    Direction.valueOf(
                            args.get("face").getAsString()
                                    .toUpperCase(Locale.ROOT)),
                    args.get("slot").getAsInt(),
                    args.get("block").getAsString(),
                    args.get("expected_item").getAsString(),
                    args.get("baseline_count").getAsInt());
            case "deposit" -> new DepositAction(
                    executionId,
                    new BlockPos(
                            args.get("x").getAsInt(),
                            args.get("y").getAsInt(),
                            args.get("z").getAsInt()),
                    Direction.valueOf(
                            args.get("face").getAsString()
                                    .toUpperCase(Locale.ROOT)),
                    args.get("baseline_screen_seq").getAsLong(),
                    args.get("target_kind").getAsString());
            default -> null;
        };
        if(active==null)
            send(executionId,"failed",0D,
                    "real_client_operation_unsupported");
        else
            send(executionId,"running",0D,
                    "real_client_action_admitted");
    
    }

    void control(
            JsonObject message,MinecraftClient client) {
        String executionId=safeExecutionId(message);
        try {
            controlChecked(message,client);
        } catch(RuntimeException failure) {
            String reason="real_client_control_invalid:"
                    +failure.getClass().getSimpleName();
            failMalformedCurrent(client,executionId,reason);
        }
    }

    private void controlChecked(
            JsonObject message,MinecraftClient client) {
        if(active==null || !active.executionId.equals(
                message.get("execution_id").getAsString()))
            return;
        String action=message.get("action").getAsString();
        switch(action) {
            case "pause" -> {
                active.paused=true;
                clearInputs(client);
                send(active.executionId,"paused",
                        active.progress,"external_pause");
            }
            case "resume" -> {
                active.paused=false;
                send(active.executionId,"running",
                        active.progress,"external_resume");
            }
            case "cancel" -> {
                active.cancelled=true;
                clearInputs(client);
                if(client.interactionManager!=null)
                    client.interactionManager
                            .cancelBlockBreaking();
                closeHandled(client);
                send(active.executionId,"cancelled",
                        active.progress,
                        message.has("reason")
                                ?message.get("reason").getAsString()
                                :"external_cancel");
            }
        }
    
    }

    void tick(MinecraftClient client) {
        if(active==null)return;
        if(client.player==null
                ||client.world==null
                ||client.interactionManager==null) {
            clearInputs(client);
            return;
        }
        if(active.terminal()) {
            clearInputs(client);
            return;
        }
        if(active.paused) {
            clearInputs(client);
            return;
        }
        if(++active.ageTicks>20*150) {
            // 硬超时防呆:残留 action 永不终态会占死执行槽(real_client_action_busy 死锁,实测)
            clearInputs(client);
            closeHandled(client);
            send(active.executionId,"failed",
                    active.progress,"client_action_hard_timeout");
            active=null;
            return;
        }
        try {
            active.tick(client);
        } catch(RuntimeException failure) {
            clearInputs(client);
            closeHandled(client);
            active.failed=true;
            send(active.executionId,"failed",
                    active.progress,
                    "real_client_action_exception:"
                            +failure.getClass().getSimpleName());
        }
    }

    void controlSessionLost(MinecraftClient client) {
        clearInputs(client);
        if(client.interactionManager!=null)
            client.interactionManager.cancelBlockBreaking();
        closeHandled(client);
        if(active!=null && !active.terminal())
            active.failed=true;
        active=null;
    }

    void gameSessionStarted(MinecraftClient client) {
        clearInputs(client);
        if(client.interactionManager!=null)
            client.interactionManager.cancelBlockBreaking();
        closeHandled(client);
        if(active!=null && !active.terminal())
            active.failed=true;
        active=null;
    }

    void disconnected(MinecraftClient client) {
        clearInputs(client);
        closeHandled(client);
        if(active!=null && !active.terminal()) {
            active.failed=true;
            send(active.executionId,"outcome_unknown",
                    active.progress,
                    "minecraft_connection_lost");
        }
        active=null;
    }

    private void failMalformedCurrent(
            MinecraftClient client,String executionId,String reason) {
        boolean ownsCurrent=active!=null
                &&executionId.equals(active.executionId)
                &&!active.terminal();
        if(!ownsCurrent) {
            send(executionId,"failed",0D,reason);
            return;
        }
        clearInputs(client);
        if(client.interactionManager!=null)
            client.interactionManager.cancelBlockBreaking();
        closeHandled(client);
        double progress=active.progress;
        active.failed=true;
        active=null;
        send(executionId,"failed",progress,reason);
    }

    private static String safeExecutionId(JsonObject message) {
        try {
            if(message!=null && message.has("execution_id")
                    &&message.get("execution_id").isJsonPrimitive()) {
                String value=message.get("execution_id").getAsString();
                if(!value.isBlank() && value.length()<=160)return value;
            }
        } catch(RuntimeException ignored) {}
        return "invalid-command";
    }

    private void send(
            String executionId,String state,
            double progress,String reason) {
        JsonObject status=new JsonObject();
        status.addProperty("type","execution");
        status.addProperty(
                "execution_id",executionId);
        status.addProperty("state",state);
        status.addProperty("progress",progress);
        status.addProperty("reason",reason);
        transport.send(status);
    }

    private static void clearInputs(
            MinecraftClient client) {
        if(client.options==null)return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.attackKey.setPressed(false);
    }

    private static void closeHandled(
            MinecraftClient client) {
        if(client.player!=null
                &&client.player.currentScreenHandler
                        !=client.player.playerScreenHandler)
            client.player.closeHandledScreen();
    }

    private abstract class Action {
        final String executionId;
        boolean paused,cancelled,failed,completed;
        double progress;
        int ageTicks;

        Action(String executionId) {
            this.executionId=executionId;
        }

        abstract void tick(MinecraftClient client);

        boolean terminal() {
            return cancelled||failed||completed;
        }

        void complete(String reason) {
            completed=true;
            progress=1D;
            send(executionId,"completed",1D,reason);
        }

        void fail(String reason) {
            failed=true;
            send(executionId,"failed",progress,reason);
        }
    }

    private final class PlaceAction extends Action {
        final int slot;
        final BlockPos target;
        int ticks;
        boolean sent;

        PlaceAction(String executionId,int slot,int x,int y,int z) {
            super(executionId);
            this.slot=slot;
            this.target=new BlockPos(x,y,z);
        }

        @Override void tick(MinecraftClient client) {
            if(client.currentScreen!=null)
                client.setScreen(null);
            if(++ticks>20*10) {
                fail("client_place_timeout");
                return;
            }
            if(!client.world.getBlockState(target).isAir()) {
                if(sent)complete("client_block_placed");
                else complete("client_block_already_present");
                return;
            }
            if(slot>=0&&slot<9)
                client.player.getInventory().selectedSlot=slot;
            // 朝目标中心看;crosshair 命中相邻实体面即在其上放置
            lookAt(client,target.toCenterPos());
            if(client.crosshairTarget instanceof BlockHitResult hit
                    &&hit.getType()==HitResult.Type.BLOCK
                    &&!sent) {
                client.interactionManager.interactBlock(
                        client.player,Hand.MAIN_HAND,hit);
                client.player.swingHand(Hand.MAIN_HAND);
                sent=true;
                send(executionId,"running",.5D,"client_place_sent");
                return;
            }
            if(sent&&client.world.getBlockState(target).isAir()
                    &&ticks%20==0) {
                // 已发送但未落块:重试一次交互
                if(client.crosshairTarget instanceof BlockHitResult hit2
                        &&hit2.getType()==HitResult.Type.BLOCK) {
                    client.interactionManager.interactBlock(
                            client.player,Hand.MAIN_HAND,hit2);
                    client.player.swingHand(Hand.MAIN_HAND);
                }
            }
            send(executionId,"running",.2D,"client_placing");
        }
    }

    private final class EatAction extends Action {
        final int slot;
        int ticks;
        boolean started;

        EatAction(String executionId,int slot) {
            super(executionId);
            this.slot=slot;
        }

        @Override void tick(MinecraftClient client) {
            if(client.currentScreen!=null)
                client.setScreen(null);
            if(!started) {
                if(slot>=0&&slot<9)
                    client.player.getInventory().selectedSlot=slot;
                client.options.useKey.setPressed(true);
                started=true;
                send(executionId,"running",.2D,"client_eating_started");
                return;
            }
            ticks++;
            client.options.useKey.setPressed(true);
            if(ticks>12&&!client.player.isUsingItem()) {
                client.options.useKey.setPressed(false);
                complete("client_food_consumed");
                return;
            }
            if(ticks>20*12) {
                client.options.useKey.setPressed(false);
                fail("client_eat_timeout");
                return;
            }
            send(executionId,"running",Math.min(.9D,ticks/160D),
                    "client_eating");
        }
    }

    private final class SayAction extends Action {
        final String message;
        boolean sent;

        SayAction(
                String executionId,String message) {
            super(executionId);
            this.message=message;
        }

        @Override void tick(MinecraftClient client) {
            if(sent)return;
            client.player.networkHandler
                    .sendChatMessage(message);
            sent=true;
            complete("client_chat_packet_sent");
        }
    }

    private final class GotoAction extends Action {
        private static final int ARRIVAL_STABLE_TICKS=8;
        private static final int MAX_FACING_TICKS=400;
        private static final double VELOCITY_EPSILON_SQUARED=.0025D;

        final Vec3d target;
        final double radius;
        final BlockPos faceTarget;
        int stableTicks,facingTicks;

        GotoAction(
                String executionId,double x,double y,double z,
                double radius,BlockPos faceTarget) {
            super(executionId);
            this.target=new Vec3d(x+.5D,y,z+.5D);
            this.radius=radius;
            this.faceTarget=faceTarget;
        }

        @Override void tick(MinecraftClient client) {
            double distance=
                    client.player.getPos().distanceTo(target);
            if(distance<=radius) {
                clearInputs(client);
                if(faceTarget==null) {
                    complete("client_arrival_reported");
                    return;
                }

                // Stop first, then begin facing. Movement residual and server correction no longer
                // consume the facing timeout or leave old key intent racing the new view direction.
                double velocity=client.player.getVelocity()
                        .horizontalLengthSquared();
                if(velocity>VELOCITY_EPSILON_SQUARED) {
                    stableTicks=0;
                    send(executionId,"running",
                            progress,"client_arrival_settling");
                    return;
                }
                if(stableTicks++<ARRIVAL_STABLE_TICKS) {
                    send(executionId,"running",
                            progress,"client_arrival_settling");
                    return;
                }

                lookAt(client,faceTarget.toCenterPos());
                if(client.crosshairTarget
                        instanceof BlockHitResult hit
                        &&hit.getType()==HitResult.Type.BLOCK
                        &&hit.getBlockPos().equals(faceTarget)) {
                    complete(
                            "client_arrival_and_facing_reported");
                    return;
                }
                if(++facingTicks>MAX_FACING_TICKS)
                    fail("client_final_facing_timeout");
                else
                    send(executionId,"running",
                            progress,"client_final_facing");
                return;
            }

            stableTicks=0;
            facingTicks=0;
            // 移动统一走 walkTo:绕障偏航/挖穿/清屏/跳跃都在那里
            // (此前这里是内联直线走,绕障逻辑从未接入 goto,实测山顶死锁)。
            walkTo(client,target);
            progress=Math.max(
                    progress,Math.min(.95D,1D-distance/32D));
            send(executionId,"running",
                    progress,"walking_to_target");
            if(client.world.getTime()%40L==0L)
                io.github.zoyluo.aibot.AIBotMod.LOGGER.info(
                        "[AIBot] goto-diag pos={} yaw={} detour={} phase={} stuck={} breaking={} fwd={} jump={} collision={}",
                        client.player.getBlockPos(),
                        (int)client.player.getYaw(),
                        detourActive,detourPhase,stuckTicks,
                        breakingPos==null?"-":breakingPos.toString(),
                        client.options.forwardKey.isPressed(),
                        client.options.jumpKey.isPressed(),
                        client.player.horizontalCollision);
        }
    }

    private final class MineAction extends Action {
        final BlockPos target;
        final Direction face;
        final int slot,baseline;
        final String blockId,expectedItem;
        boolean started;

        MineAction(
                String executionId,BlockPos target,
                Direction face,int slot,String blockId,
                String expectedItem,int baseline) {
            super(executionId);
            this.target=target;
            this.face=face;
            this.slot=slot;
            this.blockId=blockId;
            this.expectedItem=expectedItem;
            this.baseline=baseline;
        }

        @Override void tick(MinecraftClient client) {
            int current=count(client,expectedItem);
            var state=client.world.getBlockState(target);
            String actual=Registries.BLOCK.getId(
                    state.getBlock()).toString();
            if(state.isAir()) {
                if(current>baseline) {
                    clearInputs(client);
                    client.interactionManager
                            .cancelBlockBreaking();
                    complete(
                            "client_block_gone_and_inventory_gain_observed");
                    return;
                }
                walkTo(client,target.toCenterPos());
                progress=.9D;
                send(executionId,"running",
                        progress,
                        "waiting_for_physical_pickup");
                return;
            }
            if(!actual.equals(blockId)) {
                clearInputs(client);
                fail("client_target_cell_changed");
                return;
            }
            if(client.player.getPos().squaredDistanceTo(
                    target.toCenterPos())>16D) {
                walkTo(client,target.toCenterPos());
                progress=Math.max(progress,.1D);
                return;
            }
            clearInputs(client);
            client.player.getInventory().selectedSlot=slot;
            lookAt(client,target.toCenterPos());
            if(!started) {
                started=client.interactionManager
                        .attackBlock(target,face);
                if(!started) {
                    fail("client_attack_block_rejected");
                    return;
                }
            }
            client.interactionManager
                    .updateBlockBreakingProgress(target,face);
            client.player.swingHand(Hand.MAIN_HAND);
            progress=Math.max(progress,.5D);
            send(executionId,"running",
                    progress,"client_breaking_block");
        }
    }

    /**
     * Two-phase GUI mutation:
     * 1. open the validated target normally;
     * 2. wait for server authorization bound to a new screen epoch, sync id and adapter;
     * 3. mutate only that owned ScreenHandler.
     */
    private final class DepositAction extends Action {
        final BlockPos target;
        final Direction face;
        final long baselineScreenSeq;
        final String targetKind;

        boolean interactionSent;
        boolean authorized;
        String authorizedScreenEpoch="";
        String authorizedAdapterId="";
        long authorizedScreenSeq=-1L;
        int authorizedSyncId=-1;
        int openTicks,authorizationTicks,clickCooldown,settleTicks;

        DepositAction(
                String executionId,BlockPos target,
                Direction face,long baselineScreenSeq,
                String targetKind) {
            super(executionId);
            this.target=target;
            this.face=face;
            this.baselineScreenSeq=baselineScreenSeq;
            this.targetKind=targetKind;
        }

        void authorize(JsonObject args) {
            String epoch=args.get("screen_epoch").getAsString();
            String adapter=args.get("adapter_id").getAsString();
            String commitTargetKind=args.get("target_kind").getAsString();
            long seq=args.get("screen_seq").getAsLong();
            int sync=args.get("sync_id").getAsInt();
            if(epoch.isBlank()
                    ||!targetKind.equals(commitTargetKind)
                    ||seq<=baselineScreenSeq
                    ||sync<0
                    ||!RealClientScreenAdapterRegistry
                            .supportsDeposit(adapter)) {
                fail("client_deposit_authorization_invalid");
                return;
            }
            authorizedScreenEpoch=epoch;
            authorizedAdapterId=adapter;
            authorizedScreenSeq=seq;
            authorizedSyncId=sync;
            authorized=true;
        }

        @Override void tick(MinecraftClient client) {
            RealClientScreenController.CurrentScreen current=
                    screens.current();

            if(!interactionSent
                    &&(current.present()
                    ||client.currentScreen
                            instanceof HandledScreen<?>)) {
                closeHandled(client);
                fail("client_preexisting_screen_open");
                return;
            }

            if(client.currentScreen instanceof HandledScreen<?> handled
                    &&client.player.currentScreenHandler
                            !=client.player.playerScreenHandler) {
                clearInputs(client);
                if(!authorized) {
                    if(++authorizationTicks>160) {
                        closeHandled(client);
                        fail("client_screen_authorization_timeout");
                    } else {
                        send(executionId,"running",
                                .45D,
                                "client_target_screen_open_waiting_authorization");
                    }
                    return;
                }
                if(!current.present()
                        ||!authorizedScreenEpoch.equals(
                                current.screenEpoch())
                        ||!authorizedAdapterId.equals(
                                current.adapterId())
                        ||current.syncId()!=authorizedSyncId
                        ||current.screenSeq()<authorizedScreenSeq
                        ||handled.getScreenHandler().syncId
                                !=authorizedSyncId
                        ||!RealClientScreenAdapterRegistry
                                .supportsDeposit(
                                        current.adapterId())) {
                    closeHandled(client);
                    fail("client_screen_ownership_lost");
                    return;
                }

                if(clickCooldown>0) {
                    clickCooldown--;
                    return;
                }
                Slot next=nextDepositable(client,handled);
                if(next!=null) {
                    client.interactionManager.clickSlot(
                            handled.getScreenHandler().syncId,
                            next.id,0,
                            SlotActionType.QUICK_MOVE,
                            client.player);
                    clickCooldown=2;
                    settleTicks=0;
                    progress=.7D;
                    send(executionId,"running",
                            progress,
                            "client_owned_screen_quick_move");
                    return;
                }
                if(++settleTicks<10) {
                    send(executionId,"running",
                            .9D,
                            "client_owned_screen_settling");
                    return;
                }
                closeHandled(client);
                complete(
                        "client_owned_screen_quick_move_finished");
                return;
            }

            if(authorized) {
                fail("client_authorized_screen_closed");
                return;
            }

            if(client.player.getPos().squaredDistanceTo(
                    target.toCenterPos())>16D) {
                walkTo(client,target.toCenterPos());
                progress=.1D;
                return;
            }
            clearInputs(client);
            lookAt(client,target.toCenterPos());
            if(client.crosshairTarget
                    instanceof BlockHitResult hit
                    &&hit.getType()==HitResult.Type.BLOCK
                    &&hit.getBlockPos().equals(target)) {
                if(!interactionSent) {
                    ActionResult result=
                            client.interactionManager.interactBlock(
                                    client.player,
                                    Hand.MAIN_HAND,hit);
                    interactionSent=result.isAccepted();
                    client.player.swingHand(
                            Hand.MAIN_HAND);
                }
            }
            if(++openTicks>160) {
                fail("client_container_open_timeout");
                return;
            }
            send(executionId,"running",
                    .25D,
                    interactionSent
                            ?"waiting_for_owned_target_screen"
                            :"aiming_at_container");
        }

        private Slot nextDepositable(
                MinecraftClient client,
                HandledScreen<?> handled) {
            int selected=
                    client.player.getInventory().selectedSlot;
            for(Slot slot:
                    handled.getScreenHandler().slots) {
                if(slot.inventory
                        !=client.player.getInventory())
                    continue;
                int index=slot.getIndex();
                if(index<0 || index>=36
                        ||index==selected)
                    continue;
                if(slot.getStack().isEmpty()
                        ||!slot.canTakeItems(client.player))
                    continue;
                return slot;
            }
            return null;
        }
    }

    private static int count(
            MinecraftClient client,String itemId) {
        int total=0;
        for(int slot=0;
                slot<client.player.getInventory().size();
                slot++) {
            ItemStack stack=
                    client.player.getInventory().getStack(slot);
            if(!stack.isEmpty()
                    &&Registries.ITEM.getId(
                            stack.getItem()).toString()
                            .equals(itemId))
                total+=stack.getCount();
        }
        return total;
    }

    // ---------- 寻路运动状态:直线 -> 偏航绕行 -> 挖穿(真实挖掘) ----------
    private static int stuckTicks,clearTicks,detourTicks,detourPhase;
    private static double bestDistance=-1D;
    private static int noProgressTicks;
    private static float detourBaseYaw,detourYaw;
    private static boolean detourActive;
    private static BlockPos breakingPos;
    private static Direction breakingFace;
    private static final float[] DETOURS={45F,-45F,90F,-90F};

    private static void resetLocomotion() {
        stuckTicks=0;clearTicks=0;detourTicks=0;detourPhase=0;
        detourActive=false;breakingPos=null;breakingFace=null;
        bestDistance=-1D;noProgressTicks=0;
    }

    private static void startBreaking(
            MinecraftClient client,Vec3d target) {
        // 确定性位置挖掘:先朝向目标再取面朝方向(墙在目标方向上),
        // 否则水平朝向是偏航残留,会挖错方向。raycast 不可靠(视线掠近墙)。
        lookAt(client,target);
        Direction dir=client.player.getHorizontalFacing();
        BlockPos feet=client.player.getBlockPos();
        BlockPos head=feet.up();
        BlockPos footAhead=feet.offset(dir);
        BlockPos headAhead=head.offset(dir);
        BlockPos pick=null;
        if(!client.world.getBlockState(headAhead).isAir())
            pick=headAhead;
        else if(!client.world.getBlockState(footAhead).isAir())
            pick=footAhead;
        else if(!client.world.getBlockState(headAhead.up()).isAir())
            pick=headAhead.up();
        if(pick==null) {
            io.github.zoyluo.aibot.AIBotMod.LOGGER.info(
                    "[AIBot] dig-diag nothing-ahead feet={} dir={} state={}",
                    feet,dir,
                    Registries.BLOCK.getId(client.world
                            .getBlockState(footAhead).getBlock()));
            return;
        }
        io.github.zoyluo.aibot.AIBotMod.LOGGER.info(
                "[AIBot] dig-diag start pick={} face={}",
                pick,dir.getOpposite());
        breakingPos=pick;
        breakingFace=dir.getOpposite();
        client.interactionManager.attackBlock(
                breakingPos,breakingFace);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private static void walkTo(
            MinecraftClient client,Vec3d target) {
        // 任何 UI 屏(聊天/菜单/残留界面)都会吞掉移动输入:移动类动作开始前强制清屏
        if(client.currentScreen!=null)
            client.setScreen(null);
        clearInputs(client);
        // 挖掘进行中:持续挖掘直到方块消失,期间不移动
        if(breakingPos!=null) {
            if(client.world.getBlockState(breakingPos).isAir()
                    ||client.player.getEyePos().distanceTo(
                            breakingPos.toCenterPos())>4.5D) {
                breakingPos=null;
                client.interactionManager.cancelBlockBreaking();
                return;
            }
            client.interactionManager.updateBlockBreakingProgress(
                    breakingPos,breakingFace);
            client.player.swingHand(Hand.MAIN_HAND);
            return;
        }
        if(detourActive) {
            turnTowards(client,detourYaw);
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(
                    client.player.horizontalCollision);
            detourTicks++;
            if(client.player.horizontalCollision)
                clearTicks=0;
            else {
                clearTicks++;
                // 弹跳瞬间会短暂脱墙(0.4s),过短会误判脱困并丢弃绕障进度(实测)
                if(clearTicks>25) {
                    resetLocomotion();
                    return;
                }
            }
            if(detourTicks>40) {
                detourTicks=0;clearTicks=0;
                detourPhase++;
                if(detourPhase>=DETOURS.length) {
                    // 六个偏航方向全部失败:挖穿视线内的墙(真实挖掘,工具与时间遵循游戏规则)
                    detourActive=false;
                    startBreaking(client,target);
                    return;
                }
                detourYaw=detourBaseYaw+DETOURS[detourPhase];
            }
            return;
        }
        lookAt(client,target);
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(false);
        // 1 格台阶/迎面碰撞自动跳:无跳跃的 W 按住在梯田地形必然卡死(实测)。
        client.options.jumpKey.setPressed(
                client.player.horizontalCollision);
        // 距目标无改善检测:原地弹跳(114<->115 震荡)会骗过位置不变检测,
        // 只有持续接近目标才算有效移动;60 tick 无改善即进入偏航绕行,仍不通再挖穿。
        double dNow=client.player.getPos().distanceTo(target);
        if(bestDistance<0D||dNow<bestDistance-0.1D) {
            bestDistance=dNow;
            noProgressTicks=0;
        } else {
            noProgressTicks++;
        }
        if(noProgressTicks>60) {
            detourActive=true;detourTicks=0;clearTicks=0;
            detourBaseYaw=client.player.getYaw();
            detourYaw=detourBaseYaw
                    +DETOURS[Math.min(detourPhase,DETOURS.length-1)];
            noProgressTicks=0;
            bestDistance=-1D;
        }
    }

    /** 平滑转向:每 tick 最多转 12 度,消除视角瞬移造成的画面抽搐。 */
    private static void turnTowards(
            MinecraftClient client,float targetYaw) {
        float current=client.player.getYaw();
        float delta=net.minecraft.util.math.MathHelper
                .wrapDegrees(targetYaw-current);
        float step=MathHelper.clamp(delta,-12F,12F);
        float next=current+step;
        client.player.setYaw(next);
        client.player.setHeadYaw(next);
        client.player.setBodyYaw(next);
    }

    private static void lookAt(
            MinecraftClient client,Vec3d target) {
        Vec3d eye=client.player.getEyePos();
        double dx=target.x-eye.x;
        double dy=target.y-eye.y;
        double dz=target.z-eye.z;
        double horizontal=Math.sqrt(dx*dx+dz*dz);
        float yaw=(float)(
                MathHelper.atan2(dz,dx)*180D/Math.PI)-90F;
        float pitch=(float)(
                -(MathHelper.atan2(dy,horizontal)
                        *180D/Math.PI));
        client.player.setYaw(yaw);
        client.player.setHeadYaw(yaw);
        client.player.setBodyYaw(yaw);
        client.player.setPitch(
                MathHelper.clamp(pitch,-90F,90F));
    }
}
