package io.github.zoyluo.aibot.client.realclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
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
                    args.has("arrival_radius")?args.get("arrival_radius").getAsDouble():2.5D);
            case "mine_opportunity" -> new MineAction(
                    executionId,
                    new BlockPos(args.get("x").getAsInt(),args.get("y").getAsInt(),args.get("z").getAsInt()),
                    Direction.valueOf(args.get("face").getAsString().toUpperCase(Locale.ROOT)),
                    args.get("slot").getAsInt(),args.get("block").getAsString(),
                    args.get("expected_item").getAsString(),args.get("baseline_count").getAsInt());
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
                send(active.executionId,"cancelled",active.progress,
                        message.has("reason")?message.get("reason").getAsString():"external_cancel");
            }
        }
    }

    void tick(MinecraftClient client) {
        if(active==null)return;
        if(client.player==null || client.world==null || client.interactionManager==null) {
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
        try {
            active.tick(client);
        } catch(RuntimeException failure) {
            clearInputs(client);
            active.failed=true;
            send(active.executionId,"failed",active.progress,
                    "real_client_action_exception:"+failure.getClass().getSimpleName());
        }
    }

    void controlSessionLost(MinecraftClient client) {
        clearInputs(client);
        if(client.interactionManager!=null)client.interactionManager.cancelBlockBreaking();
        // The server has fenced this physical session. Never retain an old intent across reconnect.
        if(active!=null && !active.terminal())active.failed=true;
        active=null;
    }

    void disconnected(MinecraftClient client) {
        clearInputs(client);
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
        final String message;
        boolean sent;
        SayAction(String executionId,String message){super(executionId);this.message=message;}
        @Override void tick(MinecraftClient client) {
            if(sent)return;
            client.player.networkHandler.sendChatMessage(message);
            sent=true;
            complete("client_chat_packet_sent");
        }
    }

    private final class GotoAction extends Action {
        final Vec3d target;
        final double radius;
        GotoAction(String executionId,double x,double y,double z,double radius) {
            super(executionId);this.target=new Vec3d(x+.5D,y,z+.5D);this.radius=radius;
        }
        @Override void tick(MinecraftClient client) {
            double distance=client.player.getPos().distanceTo(target);
            if(distance<=radius) {
                clearInputs(client);complete("client_arrival_reported");return;
            }
            clearInputs(client);
            lookAt(client,target);
            client.options.forwardKey.setPressed(true);
            client.options.sprintKey.setPressed(distance>6D);
            progress=Math.max(progress,Math.min(.95D,1D-distance/32D));
            send(executionId,"running",progress,"walking_to_target");
        }
    }

    private final class MineAction extends Action {
        final BlockPos target;
        final Direction face;
        final int slot,baseline;
        final String blockId,expectedItem;
        boolean started;
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
                walkTo(client,target.toCenterPos());
                progress=.9D;
                send(executionId,"running",progress,"waiting_for_physical_pickup");
                return;
            }
            if(!actual.equals(blockId)) {
                clearInputs(client);fail("client_target_cell_changed");return;
            }
            if(client.player.getPos().squaredDistanceTo(target.toCenterPos())>16D) {
                walkTo(client,target.toCenterPos());
                progress=Math.max(progress,.1D);
                return;
            }
            clearInputs(client);
            client.player.getInventory().selectedSlot=slot;
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
        clearInputs(client);
        lookAt(client,target);
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
        client.player.setPitch(MathHelper.clamp(pitch,-90F,90F));
    }
}
