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

/** Tick-driven normal-client actuator. It uses the same movement and interaction manager as a player. */
final class RealClientActionController {
    private final RealClientClientTransport transport;
    private Action active;

    RealClientActionController(RealClientClientTransport transport) {
        this.transport=transport;
    }

    void command(JsonObject message) {
        String executionId=message.get("execution_id").getAsString();
        String operation=message.get("operation").getAsString();
        JsonObject args=JsonParser.parseString(
                message.get("arguments_json").getAsString()).getAsJsonObject();
        if(active!=null && !active.terminal()) {
            send(executionId,"failed",0D,"real_client_action_busy");
            return;
        }
        active=switch(operation) {
            case "say" -> new SayAction(executionId,args.get("message").getAsString());
            case "goto" -> new GotoAction(
                    executionId,args.get("x").getAsDouble(),
                    args.get("y").getAsDouble(),args.get("z").getAsDouble(),
                    args.has("arrival_radius")?args.get("arrival_radius").getAsDouble():2.5D,
                    args.has("face_x")&&args.has("face_y")&&args.has("face_z")
                            ?new BlockPos(args.get("face_x").getAsInt(),
                                    args.get("face_y").getAsInt(),args.get("face_z").getAsInt())
                            :null);
            case "mine_opportunity" -> new MineAction(
                    executionId,
                    new BlockPos(args.get("x").getAsInt(),args.get("y").getAsInt(),args.get("z").getAsInt()),
                    Direction.valueOf(args.get("face").getAsString().toUpperCase(Locale.ROOT)),
                    args.get("slot").getAsInt(),args.get("block").getAsString(),
                    args.get("expected_item").getAsString(),args.get("baseline_count").getAsInt());
            case "deposit" -> new DepositAction(
                    executionId,
                    new BlockPos(args.get("x").getAsInt(),args.get("y").getAsInt(),args.get("z").getAsInt()),
                    Direction.valueOf(args.get("face").getAsString().toUpperCase(Locale.ROOT)));
            default -> null;
        };
        if(active==null)send(executionId,"failed",0D,"real_client_operation_unsupported");
        else send(executionId,"running",0D,"real_client_action_admitted");
    }

    void control(JsonObject message,MinecraftClient client) {
        if(active==null || !active.executionId.equals(
                message.get("execution_id").getAsString()))return;
        String action=message.get("action").getAsString();
        switch(action) {
            case "pause" -> {
                active.paused=true;clearInputs(client);
                send(active.executionId,"paused",active.progress,"external_pause");
            }
            case "resume" -> {
                active.paused=false;
                send(active.executionId,"running",active.progress,"external_resume");
            }
            case "cancel" -> {
                active.cancelled=true;clearInputs(client);
                if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();
                closeHandled(client);
                send(active.executionId,"cancelled",active.progress,
                        message.has("reason")?message.get("reason").getAsString():"external_cancel");
            }
        }
    }

    void tick(MinecraftClient client) {
        if(active==null)return;
        if(client.player==null || client.world==null || client.interactionManager==null) {
            clearInputs(client);return;
        }
        if(active.terminal()) { clearInputs(client);return; }
        if(active.paused) { clearInputs(client);return; }
        try {
            active.tick(client);
        } catch(RuntimeException failure) {
            clearInputs(client);
            closeHandled(client);
            active.failed=true;
            send(active.executionId,"failed",active.progress,
                    "real_client_action_exception:"+failure.getClass().getSimpleName());
        }
    }

    void controlSessionLost(MinecraftClient client) {
        clearInputs(client);
        if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();
        closeHandled(client);
        if(active!=null && !active.terminal())active.failed=true;
        active=null;
    }

    void gameSessionStarted(MinecraftClient client) {
        clearInputs(client);
        if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();
        closeHandled(client);
        if(active!=null && !active.terminal())active.failed=true;
        active=null;
    }

    void disconnected(MinecraftClient client) {
        clearInputs(client);
        closeHandled(client);
        if(active!=null && !active.terminal()) {
            active.failed=true;
            send(active.executionId,"outcome_unknown",active.progress,
                    "minecraft_connection_lost");
        }
        active=null;
    }

    private void send(String executionId,String state,double progress,String reason) {
        JsonObject status=new JsonObject();
        status.addProperty("type","execution");
        status.addProperty("execution_id",executionId);
        status.addProperty("state",state);
        status.addProperty("progress",progress);
        status.addProperty("reason",reason);
        transport.send(status);
    }

    private static void clearInputs(MinecraftClient client) {
        if(client.options==null)return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.attackKey.setPressed(false);
    }

    private static void closeHandled(MinecraftClient client) {
        if(client.player!=null
                && client.player.currentScreenHandler!=client.player.playerScreenHandler)
            client.player.closeHandledScreen();
    }

    private abstract class Action {
        final String executionId;
        boolean paused,cancelled,failed,completed;
        double progress;
        Action(String executionId){this.executionId=executionId;}
        abstract void tick(MinecraftClient client);
        boolean terminal(){return cancelled||failed||completed;}
        void complete(String reason){completed=true;progress=1D;send(executionId,"completed",1D,reason);}
        void fail(String reason){failed=true;send(executionId,"failed",progress,reason);}
    }

    private final class SayAction extends Action {
        final String message; boolean sent;
        SayAction(String executionId,String message){super(executionId);this.message=message;}
        @Override void tick(MinecraftClient client) {
            if(sent)return;
            client.player.networkHandler.sendChatMessage(message);
            sent=true;complete("client_chat_packet_sent");
        }
    }

    private final class GotoAction extends Action {
        final Vec3d target; final double radius; final BlockPos faceTarget;
        int facingTicks;
        static final int MAX_FACING_TICKS=100;
        GotoAction(String executionId,double x,double y,double z,double radius,BlockPos faceTarget) {
            super(executionId);this.target=new Vec3d(x+.5D,y,z+.5D);
            this.radius=radius;this.faceTarget=faceTarget;
        }
        @Override void tick(MinecraftClient client) {
            double distance=client.player.getPos().distanceTo(target);
            if(distance<=radius) {
                clearInputs(client);
                if(faceTarget==null) { complete("client_arrival_reported");return; }
                lookAt(client,faceTarget.toCenterPos());
                if(client.crosshairTarget instanceof BlockHitResult hit
                        && hit.getType()==HitResult.Type.BLOCK
                        && hit.getBlockPos().equals(faceTarget)) {
                    complete("client_arrival_and_facing_reported");return;
                }
                if(++facingTicks>MAX_FACING_TICKS)
                    fail("client_final_facing_timeout");
                else send(executionId,"running",progress,"client_final_facing");
                return;
            }
            clearInputs(client);lookAt(client,target);
            client.options.forwardKey.setPressed(true);
            client.options.sprintKey.setPressed(distance>6D);
            progress=Math.max(progress,Math.min(.95D,1D-distance/32D));
            send(executionId,"running",progress,"walking_to_target");
        }
    }

    private final class MineAction extends Action {
        final BlockPos target; final Direction face; final int slot,baseline;
        final String blockId,expectedItem; boolean started;
        MineAction(String executionId,BlockPos target,Direction face,int slot,
                String blockId,String expectedItem,int baseline) {
            super(executionId);this.target=target;this.face=face;this.slot=slot;
            this.blockId=blockId;this.expectedItem=expectedItem;this.baseline=baseline;
        }
        @Override void tick(MinecraftClient client) {
            int current=count(client,expectedItem);
            var state=client.world.getBlockState(target);
            String actual=Registries.BLOCK.getId(state.getBlock()).toString();
            if(state.isAir()) {
                if(current>baseline) {
                    clearInputs(client);client.interactionManager.cancelBlockBreaking();
                    complete("client_block_gone_and_inventory_gain_observed");return;
                }
                walkTo(client,target.toCenterPos());progress=.9D;
                send(executionId,"running",progress,"waiting_for_physical_pickup");return;
            }
            if(!actual.equals(blockId)) { clearInputs(client);fail("client_target_cell_changed");return; }
            if(client.player.getPos().squaredDistanceTo(target.toCenterPos())>16D) {
                walkTo(client,target.toCenterPos());progress=Math.max(progress,.1D);return;
            }
            clearInputs(client);client.player.getInventory().selectedSlot=slot;
            lookAt(client,target.toCenterPos());
            if(!started) {
                started=client.interactionManager.attackBlock(target,face);
                if(!started) { fail("client_attack_block_rejected");return; }
            }
            client.interactionManager.updateBlockBreakingProgress(target,face);
            client.player.swingHand(Hand.MAIN_HAND);
            progress=Math.max(progress,.5D);
            send(executionId,"running",progress,"client_breaking_block");
        }
    }

    /** Vanilla barrel GUI vertical slice: open normally and QUICK_MOVE player inventory stacks. */
    private final class DepositAction extends Action {
        final BlockPos target; final Direction face;
        boolean interactionSent;
        int openTicks,clickCooldown,settleTicks;
        DepositAction(String executionId,BlockPos target,Direction face) {
            super(executionId);this.target=target;this.face=face;
        }
        @Override void tick(MinecraftClient client) {
            if(client.currentScreen instanceof HandledScreen<?> handled
                    && client.player.currentScreenHandler!=client.player.playerScreenHandler) {
                clearInputs(client);
                if(clickCooldown>0) { clickCooldown--;return; }
                Slot next=nextDepositable(client,handled);
                if(next!=null) {
                    client.interactionManager.clickSlot(
                            handled.getScreenHandler().syncId,next.id,0,
                            SlotActionType.QUICK_MOVE,client.player);
                    clickCooldown=2;settleTicks=0;progress=.7D;
                    send(executionId,"running",progress,"client_container_quick_move");
                    return;
                }
                if(++settleTicks<10) {
                    send(executionId,"running",.9D,"client_container_settling");
                    return;
                }
                closeHandled(client);
                complete("client_container_quick_move_finished");
                return;
            }
            if(client.player.getPos().squaredDistanceTo(target.toCenterPos())>16D) {
                walkTo(client,target.toCenterPos());progress=.1D;return;
            }
            clearInputs(client);lookAt(client,target.toCenterPos());
            if(client.crosshairTarget instanceof BlockHitResult hit
                    && hit.getType()==HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(target)) {
                if(!interactionSent) {
                    ActionResult result=client.interactionManager.interactBlock(
                            client.player,Hand.MAIN_HAND,hit);
                    interactionSent=result.isAccepted();
                    client.player.swingHand(Hand.MAIN_HAND);
                }
            }
            if(++openTicks>100) { fail("client_container_open_timeout");return; }
            send(executionId,"running",.25D,
                    interactionSent?"waiting_for_container_screen":"aiming_at_container");
        }

        private Slot nextDepositable(
                MinecraftClient client,HandledScreen<?> handled) {
            int selected=client.player.getInventory().selectedSlot;
            for(Slot slot:handled.getScreenHandler().slots) {
                if(slot.inventory!=client.player.getInventory())continue;
                int index=slot.getIndex();
                if(index<0 || index>=36 || index==selected)continue;
                if(slot.getStack().isEmpty() || !slot.canTakeItems(client.player))continue;
                return slot;
            }
            return null;
        }
    }

    private static int count(MinecraftClient client,String itemId) {
        int total=0;
        for(int slot=0;slot<client.player.getInventory().size();slot++) {
            ItemStack stack=client.player.getInventory().getStack(slot);
            if(!stack.isEmpty()
                    && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId))
                total+=stack.getCount();
        }
        return total;
    }

    private static void walkTo(MinecraftClient client,Vec3d target) {
        clearInputs(client);lookAt(client,target);
        client.options.forwardKey.setPressed(true);
        client.options.sprintKey.setPressed(false);
    }

    private static void lookAt(MinecraftClient client,Vec3d target) {
        Vec3d eye=client.player.getEyePos();
        double dx=target.x-eye.x,dy=target.y-eye.y,dz=target.z-eye.z;
        double horizontal=Math.sqrt(dx*dx+dz*dz);
        float yaw=(float)(MathHelper.atan2(dz,dx)*180D/Math.PI)-90F;
        float pitch=(float)(-(MathHelper.atan2(dy,horizontal)*180D/Math.PI));
        client.player.setYaw(yaw);
        client.player.setHeadYaw(yaw);
        client.player.setBodyYaw(yaw);
        client.player.setPitch(MathHelper.clamp(pitch,-90F,90F));
    }
}
