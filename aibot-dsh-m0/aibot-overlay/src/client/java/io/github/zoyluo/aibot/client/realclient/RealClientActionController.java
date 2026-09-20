package io.github.zoyluo.aibot.client.realclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.Item;
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

        // MC-RCF-1 G2:移动承载动作依赖受控导航组件;组件缺失时诚实失败,
        // 不回退到旧偏航/挖穿逻辑(旧代码已从正式路径移除)。
        if(switch(operation) {
            case "goto","mine","place","smelt","deposit" -> true;
            default -> false;
        } && !RealClientNavigation.available()) {
            send(executionId,"failed",0D,
                    "navigation_component_unavailable");
            return;
        }
        RealClientNavigation.stop(null);
        active=switch(operation) {
            case "say" -> new SayAction(
                    executionId,
                    args.get("message").getAsString());
            case "smelt" -> new SmeltAction(
                    executionId,
                    args.get("furnace_x").getAsInt(),
                    args.get("furnace_y").getAsInt(),
                    args.get("furnace_z").getAsInt(),
                    args.get("input_item").getAsString(),
                    args.get("fuel_item").getAsString());
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
        // MC-RCF-1 G1(C06/C02): useKey 是 eat/place 的持续输入,sneakKey 影响落点与
        // 挖掘,pickItem 可能被屏幕路径按下;取消/终态/替换清理必须一并复位,
        // 否则取消进食/放置后按键残留会继续产生未授权使用。
        client.options.useKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
        client.options.pickItemKey.setPressed(false);
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

    private final class SmeltAction extends Action {
        final BlockPos furnace;
        final Item inputItem,fuelItem;
        int ticks;
        boolean opened,collected;

        SmeltAction(String executionId,int fx,int fy,int fz,
                    String inputId,String fuelId) {
            super(executionId);
            this.furnace=new BlockPos(fx,fy,fz);
            this.inputItem=Registries.ITEM.get(
                    net.minecraft.util.Identifier.tryParse(inputId));
            this.fuelItem=Registries.ITEM.get(
                    net.minecraft.util.Identifier.tryParse(fuelId));
        }

        @Override void tick(MinecraftClient client) {
            if(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen)
                client.setScreen(null);
            if(++ticks>20*220) {
                closeHandled(client);
                fail("client_smelt_timeout");
                return;
            }
            var handled=client.player.currentScreenHandler;
            boolean furnaceOpen=handled!=client.player.playerScreenHandler
                    &&handled instanceof net.minecraft.screen.AbstractFurnaceScreenHandler;
            if(!furnaceOpen) {
                if(opened) {
                    closeHandled(client);
                    fail("client_furnace_screen_closed");
                    return;
                }
                if(client.player.getPos().squaredDistanceTo(
                        furnace.toCenterPos())>20D) {
                    walkTo(client,furnace.toCenterPos());
                    send(executionId,"running",.1D,"client_walking_to_furnace");
                    return;
                }
                lookAt(client,furnace.toCenterPos());
                if(client.crosshairTarget instanceof BlockHitResult hit
                        &&hit.getType()==HitResult.Type.BLOCK
                        &&hit.getBlockPos().equals(furnace)) {
                    client.interactionManager.interactBlock(
                            client.player,Hand.MAIN_HAND,hit);
                    client.player.swingHand(Hand.MAIN_HAND);
                }
                send(executionId,"running",.15D,"client_opening_furnace");
                return;
            }
            opened=true;
            // 喂料:快捷栏/背包槽 QUICK_MOVE(vanilla 自动路由 可熔物->输入 燃料->燃料槽)
            boolean fedThisTick=false;
            for(int i=3;i<handled.slots.size()&&i<=38;i++) {
                ItemStack stack=handled.getSlot(i).getStack();
                if(stack.isEmpty())continue;
                if(stack.getItem()==inputItem||stack.getItem()==fuelItem) {
                    client.interactionManager.clickSlot(
                            handled.syncId,i,0,
                            net.minecraft.screen.slot.SlotActionType.QUICK_MOVE,
                            client.player);
                    fedThisTick=true;
                    break;
                }
            }
            if(fedThisTick) {
                send(executionId,"running",.3D,"client_feeding_furnace");
                return;
            }
            // 收集产物(输出槽 id=2)
            ItemStack out=handled.getSlot(2).getStack();
            if(!out.isEmpty()) {
                client.interactionManager.clickSlot(
                        handled.syncId,2,0,
                        net.minecraft.screen.slot.SlotActionType.QUICK_MOVE,
                        client.player);
                collected=true;
                send(executionId,"running",.8D,"client_collecting_output");
                return;
            }
            boolean inputEmpty=handled.getSlot(0).getStack().isEmpty();
            if(inputEmpty&&collected) {
                closeHandled(client);
                complete("client_smelt_batch_collected");
                return;
            }
            if(inputEmpty&&!collected
                    &&handled.getSlot(1).getStack().isEmpty()
                    &&ticks>20*10) {
                closeHandled(client);
                fail("client_smelt_no_output");
                return;
            }
            send(executionId,"running",
                    Math.min(.75D,.3D+ticks/400D),"client_smelting");
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
            // 移动统一走 walkTo → 受控 Baritone 适配器(G2 起)
            walkTo(client,target);
            progress=Math.max(
                    progress,Math.min(.95D,1D-distance/32D));
            send(executionId,"running",
                    progress,"walking_to_target");
            if(client.world.getTime()%40L==0L)
                io.github.zoyluo.aibot.AIBotMod.LOGGER.info(
                        "[AIBot] goto-diag pos={} yaw={} pathing={} goal={} dist={}",
                        client.player.getBlockPos(),
                        (int)client.player.getYaw(),
                        RealClientNavigation.pathing(),
                        String.valueOf(distance),
                        String.format("%.1f",distance));
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

    // ---------- 寻路运动:MC-RCF-1 G2 起由受控 Baritone 适配器执行 ----------
    // 旧机制(直线+偏航绕行+60tick 无改善挖穿)已从正式导航路径移除:
    // 挖穿属未授权地形修改,绕行误判会导致坠崖;成熟寻路组件接管。

    private static void walkTo(
            MinecraftClient client,Vec3d target) {
        // 任何 UI 屏(聊天/菜单/残留界面)都会吞掉移动输入:移动类动作开始前强制清屏
        if(client.currentScreen!=null)
            client.setScreen(null);
        clearInputs(client);
        RealClientNavigation.pathTo(client,target,2.0D);
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
