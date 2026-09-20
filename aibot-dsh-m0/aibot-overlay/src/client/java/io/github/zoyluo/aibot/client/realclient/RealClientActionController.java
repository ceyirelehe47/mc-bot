package io.github.zoyluo.aibot.client.realclient;

import com.google.gson.JsonArray;

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
            commandChecked(message,client);
        } catch(RuntimeException failure) {
            failMalformedCurrent(
                client,executionId,
                "real_client_command_invalid:"
                        +failure.getClass().getSimpleName());
        }
    }

    private void commandChecked(JsonObject message,MinecraftClient client) {
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
            // R1-C1:替换同样走统一收尾——旧导航任务/输入/GUI 不留给新动作
            finishAction(client);
        }

        // MC-RCF-1 G2/R1-C2:移动承载动作依赖受控导航组件;组件缺失时诚实失败,
        // 不回退到旧偏航/挖穿逻辑(旧代码已从正式路径移除)。
        // R1 修正:操作名对齐实际下发名 mine_opportunity(旧 "mine" 永不匹配,
        // 组件缺失时挖掘动作不被拒——审查点名)。
        if(switch(operation) {
            case "goto","mine_opportunity","place","smelt","deposit","craft" -> true;
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
            case "smelt" -> {
                // R1-I7:未验收能力,客户端同样拒绝(纵深防御)
                send(executionId,"failed",0D,
                        "smelt_not_available_unvalidated");
                yield null;
            }
            case "container_transfer" -> new ContainerAction(
                    executionId,
                    new BlockPos(
                            args.get("x").getAsInt(),
                            args.get("y").getAsInt(),
                            args.get("z").getAsInt()),
                    args.get("item").getAsString(),
                    args.has("count")
                            ?args.get("count").getAsInt():-1,
                    "withdraw".equals(
                            args.has("direction")
                                    ?args.get("direction")
                                            .getAsString():"deposit"),
                    args.has("source_slot")
                            ?args.get("source_slot").getAsInt():-1);
            case "smelt-disabled" -> new SmeltAction(
                    executionId,
                    args.get("furnace_x").getAsInt(),
                    args.get("furnace_y").getAsInt(),
                    args.get("furnace_z").getAsInt(),
                    args.get("input_item").getAsString(),
                    args.get("fuel_item").getAsString());
            case "place" -> new PlaceAction(
                    executionId,
                    args.get("item").getAsString(),
                    args.get("x").getAsInt(),
                    args.get("y").getAsInt(),
                    args.get("z").getAsInt());
            case "eat" -> new EatAction(
                    executionId,
                    args.get("food_item").getAsString());
            case "move_items" -> new MoveItemsAction(
                    executionId,
                    args.get("item").getAsString(),
                    args.has("count")
                            ?args.get("count").getAsInt():-1,
                    args.get("hotbar").getAsInt(),
                    args.has("source_slot")
                            ?args.get("source_slot").getAsInt():-1,
                    args.has("protect_slot")
                            ?args.get("protect_slot").getAsInt():-1);
            case "craft" -> {
                JsonArray grid=args.getAsJsonArray("grid");
                String[] cells=new String[grid.size()];
                for(int i=0;i<grid.size();i++) {
                    var e=grid.get(i);
                    cells[i]=e.isJsonNull()?null:e.getAsString();
                }
                yield new CraftAction(
                        executionId,
                        cells,
                        args.get("result_item").getAsString(),
                        args.get("batches").getAsInt(),
                        args.has("table_x")
                                ?new BlockPos(
                                        args.get("table_x").getAsInt(),
                                        args.get("table_y").getAsInt(),
                                        args.get("table_z").getAsInt())
                                :null);
            }
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

    /** R1-C1 统一收尾:所有执行生命周期出口共用同一顺序——
     * 停本执行调度已由调用方处理;这里停 Baritone 目标/路径任务及转向
     * →释放移动/use/attack/sneak 输入与破坏状态 → 关 GUI(cursor 恢复
     * 规则由各动作自己的 closeHandled 语义承载)。clearInputs 不再被
     * 当作 Baritone cancel(实测库会继续接管按键)。 */
    private void finishAction(MinecraftClient client) {
        RealClientNavigation.stop(client);
        clearInputs(client);
        if(client.interactionManager!=null)
            client.interactionManager.cancelBlockBreaking();
        closeHandled(client);
    }

    private void controlChecked(
            JsonObject message,MinecraftClient client) {
        if(active==null || !active.executionId.equals(
                message.get("execution_id").getAsString()))
            return;
        String action=message.get("action").getAsString();
        switch(action) {
            case "pause" -> {
                // R1-C1:暂停保留语义意图,但停止路径任务与全部输入——
                // 库持有任务时仅清键会立刻被 pathing 重新接管。
                active.paused=true;
                finishAction(client);
                send(active.executionId,"paused",
                        active.progress,"external_pause");
            }
            case "resume" -> {
                // resume 对当前身份/目标重新验证再规划:各动作 tick 内
                // walkTo 每次以真实位置/目标重设 goal,组件或世界不符时
                // 其自身验证路径会诚实失败。
                active.paused=false;
                send(active.executionId,"running",
                        active.progress,"external_resume");
            }
            case "cancel" -> {
                active.cancelled=true;
                finishAction(client);
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
            finishAction(client);
            send(active.executionId,"failed",
                    active.progress,"client_action_hard_timeout");
            active=null;
            return;
        }
        try {
            active.tick(client);
        } catch(RuntimeException failure) {
            finishAction(client);
            active.failed=true;
            send(active.executionId,"failed",
                    active.progress,
                    "real_client_action_exception:"
                            +failure.getClass().getSimpleName());
        }
    }

    void controlSessionLost(MinecraftClient client) {
        finishAction(client);
        if(active!=null && !active.terminal())
            active.failed=true;
        active=null;
    }

    void gameSessionStarted(MinecraftClient client) {
        finishAction(client);
        if(active!=null && !active.terminal())
            active.failed=true;
        active=null;
    }

    void disconnected(MinecraftClient client) {
        finishAction(client);
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
        final String itemId;
        final BlockPos target;
        int ticks,selectCooldown,selectSettle;
        boolean sent;
        Direction supportFace;

        PlaceAction(String executionId,String itemId,int x,int y,int z) {
            super(executionId);
            this.itemId=itemId;
            this.target=new BlockPos(x,y,z);
        }

        @Override void tick(MinecraftClient client) {
            if(client.currentScreen!=null)
                client.setScreen(null);
            // MC-RCF-1 G3c:事务层自助准备手持物品(真实调槽,节流防同步竞态)
            if(!RealClientInventoryOps.heldItemId(client)
                    .equals(itemId)) {
                if(selectCooldown>0) {
                    selectCooldown--;
                    return;
                }
                var handler=client.player.currentScreenHandler;
                var cursor=handler.getCursorStack();
                if(RealClientInventoryOps.is(cursor,itemId)) {
                    int empty=RealClientInventoryOps.findEmpty(
                            handler,
                            RealClientInventoryOps.PLAYER_HOTBAR_START,
                            RealClientInventoryOps.PLAYER_HOTBAR_START+9);
                    if(empty<0)empty=
                            RealClientInventoryOps.PLAYER_HOTBAR_START;
                    RealClientInventoryOps.click(client,handler,
                            empty,0,SlotActionType.PICKUP);
                    selectCooldown=3;
                    return;
                }
                if(!cursor.isEmpty()) {
                    int back=RealClientInventoryOps.findEmpty(handler,
                            RealClientInventoryOps.PLAYER_MAIN_START,
                            RealClientInventoryOps.PLAYER_MAIN_START+27);
                    RealClientInventoryOps.click(client,handler,
                            back<0?36:back,0,SlotActionType.PICKUP);
                    selectCooldown=3;
                    return;
                }
                int hotbar=RealClientInventoryOps.findStack(
                        handler,itemId,
                        RealClientInventoryOps.PLAYER_HOTBAR_START,
                        RealClientInventoryOps.PLAYER_HOTBAR_START+9);
                if(hotbar>=0) {
                    client.player.getInventory().selectedSlot=
                            hotbar-RealClientInventoryOps.PLAYER_HOTBAR_START;
                    return;
                }
                int main=RealClientInventoryOps.findStack(
                        handler,itemId,
                        RealClientInventoryOps.PLAYER_MAIN_START,
                        RealClientInventoryOps.PLAYER_MAIN_START+27);
                if(main<0) {
                    if(++selectSettle>20) {
                        fail("client_place_item_not_found");
                        return;
                    }
                    return;
                }
                RealClientInventoryOps.click(client,handler,
                        main,0,SlotActionType.PICKUP);
                selectCooldown=3;
                send(executionId,"running",.05D,
                        "client_place_selecting_item");
                return;
            }
            if(++ticks>20*12) {
                fail("client_place_timeout");
                return;
            }
            var world=client.world;
            // 正交邻位站位:对角站位射线无法命中目标支撑面(实测),
            // 先到目标的水平邻格再瞄准——G3c 工作站位语义
            BlockPos stand=null;
            for(Direction f:new Direction[]{Direction.NORTH,
                    Direction.SOUTH,Direction.EAST,Direction.WEST}) {
                BlockPos n=target.offset(f);
                if(world.getBlockState(n).isAir()
                        &&world.getBlockState(n.down())
                                .isSideSolidFullSquare(
                                        world,n.down(),Direction.UP)) {
                    stand=n;
                    break;
                }
            }
            if(stand==null) {
                fail("client_place_no_stance");
                return;
            }
            if(!client.player.getBlockPos().equals(stand)) {
                walkTo(client,stand.toCenterPos());
                send(executionId,"running",.15D,
                        "client_place_approaching_stance");
                return;
            }
            // (瞄空气格中心时射线常从侧壁穿出,落点校验永不成立——实测教训)
            BlockPos support=null;
            Direction[] faces={Direction.DOWN,Direction.NORTH,
                    Direction.SOUTH,Direction.EAST,Direction.WEST,
                    Direction.UP};
            for(Direction f:faces) {
                BlockPos n=target.offset(f);
                if(world.getBlockState(n).isSideSolidFullSquare(
                        world,n,f.getOpposite())) {
                    support=n;
                    supportFace=f.getOpposite(); // 命中的是支撑块的暴露面
                    break;
                }
            }
            if(support==null) {
                fail("client_place_no_support_face");
                return;
            }
            lookAt(client,support.toCenterPos());
            if(ticks%5==0) {
                String cross=client.crosshairTarget==null?"null"
                        :client.crosshairTarget.getType()
                                +(client.crosshairTarget
                                instanceof BlockHitResult b
                                ?"@"+b.getBlockPos()
                                +":"+b.getSide():"");
                io.github.zoyluo.aibot.AIBotMod.LOGGER.info(
                        "[AIBot] place-diag pos={} stand={} support={}{} face={} yaw={} pitch={} cross={} held={} sent={}",
                        client.player.getBlockPos(),stand,
                        support,support==null?"":"@"+support,
                        supportFace,Math.round(client.player.getYaw()),
                        Math.round(client.player.getPitch()),cross,
                        RealClientInventoryOps.heldItemId(client),sent);
            }
            if(client.crosshairTarget instanceof BlockHitResult hit
                    &&hit.getType()==HitResult.Type.BLOCK
                    &&hit.getBlockPos().equals(support)
                    &&hit.getSide()==supportFace
                    &&!sent) {
                client.interactionManager.interactBlock(
                        client.player,Hand.MAIN_HAND,hit);
                client.player.swingHand(Hand.MAIN_HAND);
                sent=true;
                send(executionId,"running",.5D,"client_place_sent");
                return;
            }
            if(sent&&ticks%10==0
                    &&client.crosshairTarget
                            instanceof BlockHitResult hit2
                    &&hit2.getType()==HitResult.Type.BLOCK
                    &&hit2.getBlockPos().equals(support)
                    &&hit2.getSide()==supportFace) {
                client.interactionManager.interactBlock(
                        client.player,Hand.MAIN_HAND,hit2);
                client.player.swingHand(Hand.MAIN_HAND);
            }
            send(executionId,"running",.2D,"client_placing");
        }
    }

    private final class EatAction extends Action {
        final String foodItem;
        int phase;      // 0=确保手持 1=进食中 2=收尾验证
        int ticks,eatTicks,selectCooldown;

        EatAction(String executionId,String foodItem) {
            super(executionId);
            this.foodItem=foodItem;
        }

        @Override void tick(MinecraftClient client) {
            if(client.currentScreen!=null)
                client.setScreen(null);
            if(++ticks>20*14) {
                client.options.useKey.setPressed(false);
                fail("client_eat_timeout");
                return;
            }
            var inv=client.player.getInventory();
            var handler=client.player.currentScreenHandler;
            if(phase==0) {
                // MC-RCF-1 G3d:真实定位食物并调入快捷栏选中,
                if(RealClientInventoryOps.heldItemId(client)
                        .equals(foodItem)) {
                    phase=1; // 已手持:进入进食
                    return;
                }
                int hotbar=RealClientInventoryOps.findStack(
                        handler,foodItem,
                        RealClientInventoryOps.PLAYER_HOTBAR_START,
                        RealClientInventoryOps.PLAYER_HOTBAR_START+9);
                if(hotbar>=0) {
                    inv.selectedSlot=
                            hotbar-RealClientInventoryOps.PLAYER_HOTBAR_START;
                    return;
                }
                if(selectCooldown>0) {
                    selectCooldown--;
                    return; // 点击节流:服务端同步窗口
                }
                var cursor=handler.getCursorStack();
                if(RealClientInventoryOps.is(cursor,foodItem)) {
                    int empty=RealClientInventoryOps.findEmpty(
                            handler,
                            RealClientInventoryOps.PLAYER_HOTBAR_START,
                            RealClientInventoryOps.PLAYER_HOTBAR_START+9);
                    if(empty<0)empty=
                            RealClientInventoryOps.PLAYER_HOTBAR_START;
                    RealClientInventoryOps.click(client,handler,
                            empty,0,SlotActionType.PICKUP);
                    selectCooldown=3;
                    return;
                }
                if(!cursor.isEmpty()) {
                    int back=RealClientInventoryOps.findEmpty(handler,
                            RealClientInventoryOps.PLAYER_MAIN_START,
                            RealClientInventoryOps.PLAYER_MAIN_START+27);
                    RealClientInventoryOps.click(client,handler,
                            back<0?36:back,0,SlotActionType.PICKUP);
                    selectCooldown=3;
                    return;
                }
                int main=RealClientInventoryOps.findStack(
                        handler,foodItem,
                        RealClientInventoryOps.PLAYER_MAIN_START,
                        RealClientInventoryOps.PLAYER_MAIN_START+27);
                if(main<0) {
                    fail("client_food_not_found");
                    return;
                }
                RealClientInventoryOps.click(client,handler,main,0,
                        SlotActionType.PICKUP);
                selectCooldown=3;
            }
            if(phase==1) {
                // 抬头再吃:准星对可交互方块(如刚放的工作台)时 use 会变成
                // 开屏而非进食(实测 A08 超时根因之二)
                client.player.setPitch(-85F);
                if(eatTicks<48)
                    client.options.useKey.setPressed(true);
                else {
                    client.options.useKey.setPressed(false);
                    if(!client.player.isUsingItem()) {
                        phase=2;
                        return;
                    }
                }
                eatTicks++;
                send(executionId,"running",
                        Math.min(.9D,eatTicks/160D),"client_eating");
                return;
            }
            // 收尾:手持不再是该食物,或数量减少
            client.options.useKey.setPressed(false);
            var held=inv.getMainHandStack();
            boolean consumed=held.isEmpty()
                    ||!RealClientInventoryOps.is(held,foodItem)
                    ||held.getCount()<64; // 数量减少即消费(服务端终验)
            if(consumed)
                complete("client_food_consumed");
            else
                fail("client_eat_no_effect");
        }
    }

    private final class MoveItemsAction extends Action {
        // 状态机(每次点击后 cooldown=3 给服务端同步窗口,状态由实况驱动):
        // A=光标空&目标未满足 → 取源
        // B=光标持物 → 整堆:放 dest;精确:右键单放至足数后余量放回
        // C=光标空 → 终验 dest
        // R1-I2:sourceSlot(0..35)绑定指定源堆叠(同 ID 异组件选择);
        // protectSlot 保护槽绝不作为源;count=本次净增量。
        final String item;
        final int count;           // -1=整堆;>0=精确数量
        final int hotbarSemantic;  // 0..8
        final int sourceSlot,protectSlot;
        int cooldown,stuckTicks;

        MoveItemsAction(String executionId,String item,int count,
                        int hotbarSemantic,int sourceSlot,int protectSlot) {
            super(executionId);
            this.item=item;
            this.count=count;
            this.hotbarSemantic=hotbarSemantic;
            this.sourceSlot=sourceSlot;
            this.protectSlot=protectSlot;
        }

        @Override void tick(MinecraftClient client) {
            if(cooldown>0) {
                cooldown--;
                return;
            }
            var handler=client.player.currentScreenHandler;
            int dest=RealClientInventoryOps.hotbarSlot(hotbarSemantic);
            var destStack=handler.slots.get(dest).getStack();
            var cursor=handler.getCursorStack();

            // C:光标空 → 终验
            if(cursor.isEmpty()) {
                if(RealClientInventoryOps.is(destStack,item)
                        &&(count<0||destStack.getCount()>=count)) {
                    complete("client_items_moved");
                    return;
                }
                if(!destStack.isEmpty()
                        &&!RealClientInventoryOps.is(destStack,item)) {
                    fail("client_move_dest_occupied_other_item");
                    return;
                }
                // A:取源(指定源槽优先;否则主包扫描,排除保护槽与 dest)
                int src=-1;
                if(sourceSlot>=0) {
                    int mapped=sourceSlot<9
                            ?RealClientInventoryOps.PLAYER_HOTBAR_START
                                    +sourceSlot
                            :RealClientInventoryOps.PLAYER_MAIN_START
                                    +(sourceSlot-9);
                    var s=handler.slots.get(mapped).getStack();
                    if(RealClientInventoryOps.is(s,item))
                        src=mapped;
                } else {
                    src=RealClientInventoryOps.findStack(handler,item,
                            RealClientInventoryOps.PLAYER_MAIN_START,
                            RealClientInventoryOps.PLAYER_MAIN_START+27);
                    if(src<0||src==dest)
                        src=RealClientInventoryOps.findStack(handler,item,
                                RealClientInventoryOps.PLAYER_HOTBAR_START,
                                RealClientInventoryOps.PLAYER_HOTBAR_START+9);
                    if(protectSlot>=0) {
                        int mapped=protectSlot<9
                                ?RealClientInventoryOps.PLAYER_HOTBAR_START
                                        +protectSlot
                                :RealClientInventoryOps.PLAYER_MAIN_START
                                        +(protectSlot-9);
                        if(src==mapped)
                            src=RealClientInventoryOps.findStack(handler,
                                    item,src+1,
                                    RealClientInventoryOps.PLAYER_MAIN_START
                                            +27);
                    }
                }
                if(src<0||src==dest) {
                    fail("client_move_source_not_found");
                    return;
                }
                RealClientInventoryOps.click(client,handler,src,0,
                        SlotActionType.PICKUP);
                cooldown=3;
                stuckTicks=0;
                return;
            }

            // B:光标持物
            if(!RealClientInventoryOps.is(cursor,item)) {
                if(++stuckTicks>10) {
                    fail("client_move_cursor_unexpected");
                    return;
                }
                return; // 等同步:光标应为本物品
            }
            stuckTicks=0;
            if(!destStack.isEmpty()
                    &&!RealClientInventoryOps.is(destStack,item)) {
                fail("client_move_dest_occupied_other_item");
                return;
            }
            if(count<0) {
                // 整堆:放 dest(空格落下/同 id 合并)
                RealClientInventoryOps.click(client,handler,dest,0,
                        SlotActionType.PICKUP);
                cooldown=3;
                return; // 光标空后 C 终验
            }
            if(destStack.getCount()<count) {
                // 右键单放一件
                RealClientInventoryOps.click(client,handler,dest,1,
                        SlotActionType.PICKUP);
                cooldown=2;
                return;
            }
            // dest 已足数:余量放回主包
            int back=RealClientInventoryOps.findEmpty(handler,
                    RealClientInventoryOps.PLAYER_MAIN_START,
                    RealClientInventoryOps.PLAYER_MAIN_START+27);
            if(back<0)back=RealClientInventoryOps.findEmpty(handler,
                    RealClientInventoryOps.PLAYER_HOTBAR_START,
                    RealClientInventoryOps.PLAYER_HOTBAR_START+9);
            RealClientInventoryOps.click(client,handler,
                    back<0?dest:back,0,SlotActionType.PICKUP);
            cooldown=3;
        }
    }

    private final class CraftAction extends Action {
        // 原生合成:材料真实放入合成格,结果真实拾取入包,经正常玩家处理链。
        // gridItems: 下标=格序(0..3 或 0..8),值=物品 id 或 null。
        // 本地 handler 滞后于点击:每次点击后节流等待服务端同步确认,
        // 确认依据=格子内容/光标数量实际变化(实测连续快点击导致重复放置)。
        final String[] gridItems;
        final String resultItem;
        final int batches;
        final BlockPos tablePos;   // null=玩家 2x2
        int phase;  // 0=开台/等屏 1=填格 2=取结果 3=入包 4=批间 5=收尾
        int batch,fillIndex,settleTicks,openWait;
        int openedSyncId=-1;
        int cooldown;
        int attempts;

        CraftAction(String executionId,String[] gridItems,
                    String resultItem,int batches,BlockPos tablePos) {
            super(executionId);
            this.gridItems=gridItems;
            this.resultItem=resultItem;
            this.batches=batches;
            this.tablePos=tablePos;
        }

        @Override void tick(MinecraftClient client) {
            if(cooldown>0) {
                cooldown--;
                return; // 点击节流:给服务端同步留窗口
            }
            var handler=client.player.currentScreenHandler;
            if(phase==0) {
                if(tablePos==null) {
                    // R1-I1/V05:个人 2x2 只在真实个人屏执行。外部屏
                    // (工作台/箱子/熔炉)打开时先正常关闭并等同步,
                    // 绝不 setScreen(null) 后假设服务端已回个人 handler。
                    if(client.currentScreen!=null) {
                        closeHandled(client);
                        return;
                    }
                    if(!(handler instanceof
                            net.minecraft.screen.PlayerScreenHandler)) {
                        fail("client_personal_screen_required");
                        return;
                    }
                    phase=1; // 玩家自身 2x2 合成格无需开屏
                    return;
                }
                if(client.currentScreen==null) {
                    if(client.player.getPos().squaredDistanceTo(
                            tablePos.toCenterPos())>16D) {
                        walkTo(client,tablePos.toCenterPos());
                        return;
                    }
                    lookAt(client,tablePos.toCenterPos());
                    if(client.crosshairTarget
                            instanceof BlockHitResult hit
                            &&hit.getType()==HitResult.Type.BLOCK
                            &&hit.getBlockPos().equals(tablePos)) {
                        client.interactionManager.interactBlock(
                                client.player,Hand.MAIN_HAND,hit);
                        client.player.swingHand(Hand.MAIN_HAND);
                        cooldown=4;
                    }
                    return;
                }
                var current=screens.current();
                boolean exactTableScreen=
                        client.currentScreen
                                instanceof net.minecraft.client.gui.screen
                                        .ingame.CraftingScreen
                                &&handler instanceof net.minecraft.screen
                                        .CraftingScreenHandler
                                &&current.present()
                                &&current.syncId()==handler.syncId;
                if(!exactTableScreen) {
                    if(openWait++<80) {
                        send(executionId,"running",.1D,
                                "client_table_screen_opening");
                        return;
                    }
                    closeHandled(client);
                    fail("client_table_screen_expected");
                    return;
                }
                openedSyncId=handler.syncId;
                clearInputs(client);
                phase=1;
                fillIndex=0;
                attempts=0;
                return;
            }
            if(tablePos!=null&&phase>0&&phase<5
                    &&(client.currentScreen==null
                        ||handler.syncId!=openedSyncId)) {
                fail("client_craft_screen_lost");
                return;
            }
            if(phase==1) {
                // 光标持他物:先放回主包空位(绝不点结果槽)
                var cursor=handler.getCursorStack();
                if(!cursor.isEmpty()
                        &&!RealClientInventoryOps.is(cursor,
                                fillIndex<gridItems.length
                                        ?gridItems[fillIndex]:"")) {
                    int back=RealClientInventoryOps.findEmpty(handler,
                            RealClientInventoryOps.invMainStart(handler),
                            RealClientInventoryOps.invMainStart(handler)+27);
                    if(back<0)
                        back=RealClientInventoryOps.findEmpty(handler,
                                RealClientInventoryOps.invHotbarStart(handler),
                                RealClientInventoryOps.invHotbarStart(handler)+9);
                    if(back<0) {
                        closeHandled(client);
                        fail("client_craft_cursor_stuck");
                        return;
                    }
                    RealClientInventoryOps.click(client,handler,
                            back,0,SlotActionType.PICKUP);
                    cooldown=3;
                    return;
                }
                if(fillIndex>=gridItems.length) {
                    if(!cursor.isEmpty()) {
                        // 尾料:整堆放回
                        int back=RealClientInventoryOps.findEmpty(handler,
                                RealClientInventoryOps.PLAYER_MAIN_START,
                                RealClientInventoryOps.PLAYER_MAIN_START+27);
                        if(back<0)
                            back=RealClientInventoryOps.findEmpty(handler,
                                    RealClientInventoryOps.PLAYER_HOTBAR_START,
                                    RealClientInventoryOps.PLAYER_HOTBAR_START+9);
                        RealClientInventoryOps.click(client,handler,
                                back<0?RealClientInventoryOps.invHotbarStart(handler):back,0,SlotActionType.PICKUP);
                        cooldown=3;
                        return;
                    }
                    phase=2;
                    settleTicks=0;
                    return;
                }
                String need=gridItems[fillIndex];
                int cell=1+fillIndex;
                if(need==null) {
                    fillIndex++;
                    attempts=0;
                    return;
                }
                var cellStack=handler.slots.get(cell).getStack();
                if(RealClientInventoryOps.is(cellStack,need)
                        &&cellStack.getCount()>=1) {
                    fillIndex++;   // 已确认在格:下一格(服务端同步后)
                    attempts=0;
                    return;
                }
                if(RealClientInventoryOps.is(cursor,need)) {
                    if(attempts>4) {
                        closeHandled(client);
                        fail("client_craft_fill_not_confirmed:"
                                +"cell="+cell);
                        return;
                    }
                    // 每格只放一件(核心配方单件/格)
                    RealClientInventoryOps.click(client,handler,
                            cell,1,SlotActionType.PICKUP);
                    attempts++;
                    cooldown=3;
                    return;
                }
                if(!cursor.isEmpty()) {
                    // 持同 need 之外的空档:交由下一 tick 光标放回分支
                    cooldown=1;
                    return;
                }
                int src=RealClientInventoryOps.findStack(handler,need,
                        RealClientInventoryOps.PLAYER_MAIN_START,
                        RealClientInventoryOps.PLAYER_MAIN_START+27);
                if(src<0)
                    src=RealClientInventoryOps.findStack(handler,need,
                            RealClientInventoryOps.PLAYER_HOTBAR_START,
                            RealClientInventoryOps.PLAYER_HOTBAR_START+9);
                if(src<0) {
                    closeHandled(client);
                    fail("client_craft_material_missing:"+need);
                    return;
                }
                RealClientInventoryOps.click(client,handler,src,0,
                        SlotActionType.PICKUP);
                cooldown=3;
                return;
            }
            if(phase==2) {
                var result=handler.slots.get(0).getStack();
                if(!RealClientInventoryOps.is(result,resultItem)) {
                    if(settleTicks++%10==0) {
                        StringBuilder gridState=new StringBuilder();
                        for(int i=0;i<gridItems.length;i++) {
                            var s=handler.slots.get(1+i).getStack();
                            gridState.append(i).append('=')
                                    .append(RealClientInventoryOps
                                            .itemId(s))
                                    .append('x')
                                    .append(s.getCount()).append(' ');
                        }
                        io.github.zoyluo.aibot.AIBotMod.LOGGER.info(
                                "[AIBot] craft-diag want={} grid=[{}] cursor={}",
                                resultItem,gridState,
                                RealClientInventoryOps.itemId(
                                        handler.getCursorStack()));
                    }
                    if(settleTicks>60) {
                        closeHandled(client);
                        fail("client_craft_result_not_ready");
                        return;
                    }
                    return;
                }
                if(!handler.getCursorStack().isEmpty()) {
                    int back=RealClientInventoryOps.findEmpty(handler,
                            RealClientInventoryOps.invMainStart(handler),
                            RealClientInventoryOps.invMainStart(handler)+27);
                    if(back<0)
                        back=RealClientInventoryOps.findEmpty(handler,
                                RealClientInventoryOps.invHotbarStart(handler),
                                RealClientInventoryOps.invHotbarStart(handler)+9);
                    RealClientInventoryOps.click(client,handler,
                            back<0?RealClientInventoryOps.invHotbarStart(handler):back,0,SlotActionType.PICKUP);
                    cooldown=3;
                    return;
                }
                RealClientInventoryOps.click(client,handler,0,0,
                        SlotActionType.PICKUP); // 拾取合成结果
                phase=3;
                settleTicks=0;
                cooldown=3;
                return;
            }
            if(phase==3) {
                var cursor=handler.getCursorStack();
                if(!RealClientInventoryOps.is(cursor,resultItem)) {
                    if(++settleTicks>20) {
                        closeHandled(client);
                        fail("client_craft_result_not_held");
                        return;
                    }
                    return;
                }
                int dest=RealClientInventoryOps.findEmpty(handler,
                        RealClientInventoryOps.PLAYER_MAIN_START,
                        RealClientInventoryOps.PLAYER_MAIN_START+27);
                if(dest<0)
                    dest=RealClientInventoryOps.findEmpty(handler,
                            RealClientInventoryOps.PLAYER_HOTBAR_START,
                            RealClientInventoryOps.PLAYER_HOTBAR_START+9);
                if(dest<0) {
                    closeHandled(client);
                    fail("client_craft_inventory_full");
                    return;
                }
                RealClientInventoryOps.click(client,handler,dest,0,
                        SlotActionType.PICKUP);
                batch++;
                cooldown=3;
                if(batch>=batches) {
                    phase=5;
                    return;
                }
                phase=4;
                settleTicks=0;
                return;
            }
            if(phase==4) {
                if(settleTicks++<6)
                    return;
                fillIndex=0;
                attempts=0;
                phase=1;
                return;
            }
            if(phase==5) {
                for(int i=0;i<gridItems.length;i++) {
                    if(gridItems[i]!=null) {
                        int cell=1+i;
                        if(!handler.slots.get(cell).getStack().isEmpty()) {
                            RealClientInventoryOps.click(client,handler,
                                    cell,0,SlotActionType.QUICK_MOVE);
                            cooldown=3;
                            return;
                        }
                    }
                }
                if(!handler.getCursorStack().isEmpty()) {
                    int back=RealClientInventoryOps.findEmpty(handler,
                            RealClientInventoryOps.invMainStart(handler),
                            RealClientInventoryOps.invMainStart(handler)+27);
                    RealClientInventoryOps.click(client,handler,
                            back<0?RealClientInventoryOps.invHotbarStart(handler):back,0,SlotActionType.PICKUP);
                    cooldown=3;
                    return;
                }
                closeHandled(client);
                complete("client_native_crafted");
            }
        }
    }
    private final class ContainerAction extends Action {
        // R1-I4:普通箱子/木桶双向真实事务。开屏=瞄准指定方块交互;
        // 屏身份=GenericContainerScreen+syncId;转移=PICKUP 源→放目标
        // (同 id 合并/空位),精确数量右键单放,余量放回;全部点击节流
        // 并以实况驱动(与 MoveItems 同一状态机模式)。
        final BlockPos target;
        final String item;
        final int count;          // -1=整堆
        final boolean withdraw;   // true=容器→玩家
        final int sourceSlot;     // 可选:绑定源槽(玩家索引,deposit 用)
        int phase; // 0=开屏 1=取源 2=放置 3=终验
        int cooldown,stuckTicks,openWait,moved;
        int openedSyncId=-1;

        ContainerAction(String executionId,BlockPos target,
                        String item,int count,boolean withdraw,
                        int sourceSlot) {
            super(executionId);
            this.target=target;
            this.item=item;
            this.count=count;
            this.withdraw=withdraw;
            this.sourceSlot=sourceSlot;
        }

        private boolean isContainerSide(
                net.minecraft.screen.slot.Slot slot) {
            return !(slot.inventory
                    instanceof net.minecraft.entity.player
                            .PlayerInventory);
        }

        @Override void tick(MinecraftClient client) {
            if(cooldown>0) {
                cooldown--;
                return;
            }
            var handler=client.player.currentScreenHandler;
            if(phase==0) {
                if(client.currentScreen==null) {
                    if(client.player.getPos().squaredDistanceTo(
                            target.toCenterPos())>25D) {
                        walkTo(client,target.toCenterPos());
                        return;
                    }
                    lookAt(client,target.toCenterPos());
                    if(client.crosshairTarget
                            instanceof BlockHitResult hit
                            &&hit.getType()==HitResult.Type.BLOCK
                            &&hit.getBlockPos().equals(target)) {
                        client.interactionManager.interactBlock(
                                client.player,Hand.MAIN_HAND,hit);
                        client.player.swingHand(Hand.MAIN_HAND);
                        cooldown=4;
                    }
                    return;
                }
                boolean exactContainer=
                        client.currentScreen
                                instanceof net.minecraft.client.gui.screen
                                        .ingame.GenericContainerScreen
                                &&handler instanceof net.minecraft.screen
                                        .GenericContainerScreenHandler
                                &&screens.current().present()
                                &&screens.current().syncId()
                                        ==handler.syncId;
                if(!exactContainer) {
                    if(openWait++<80) {
                        send(executionId,"running",.1D,
                                "client_container_screen_opening");
                        return;
                    }
                    closeHandled(client);
                    fail("client_container_screen_expected");
                    return;
                }
                openedSyncId=handler.syncId;
                phase=1;
                return;
            }
            // 屏身份持续核验:换屏/关屏即拒绝
            if(client.currentScreen==null
                    ||!(handler instanceof
                            net.minecraft.screen
                                    .GenericContainerScreenHandler)
                    ||handler.syncId!=openedSyncId) {
                if(moved>0) {
                    complete("client_container_partial:"
                            +item+":"+moved);
                } else {
                    fail("client_container_screen_lost");
                }
                return;
            }
            var cursor=handler.getCursorStack();
            if(cursor.isEmpty()) {
                // 终验 or 取源
                if(count>0&&moved>=count) {
                    complete("client_container_moved:"
                            +item+":"+moved);
                    return;
                }
                if(count<0&&moved>0) {
                    complete("client_container_moved:"
                            +item+":"+moved);
                    return;
                }
                // 取源:deposit=玩家侧;withdraw=容器侧
                int src=findSource(handler);
                if(src<0) {
                    if(moved>0) {
                        complete("client_container_moved:"
                                +item+":"+moved);
                    } else {
                        fail("client_container_source_not_found");
                    }
                    return;
                }
                RealClientInventoryOps.click(client,handler,
                        src,0,SlotActionType.PICKUP);
                cooldown=3;
                return;
            }
            // 光标持物:放目标(deposit=容器侧;withdraw=玩家侧)
            if(!RealClientInventoryOps.is(cursor,item)) {
                if(++stuckTicks>10) {
                    fail("client_container_cursor_unexpected");
                    return;
                }
                return;
            }
            stuckTicks=0;
            int dest=findDest(handler,cursor.getCount());
            if(dest<0) {
                fail("client_container_dest_full");
                return;
            }
            if(count<0||cursor.getCount()+moved<=count) {
                RealClientInventoryOps.click(client,handler,
                        dest,0,SlotActionType.PICKUP);
                cooldown=3;
                if(count<0)moved=1; // 整堆语义:放下即记
                return;
            }
            // 需要部分:右键单放一件
            RealClientInventoryOps.click(client,handler,
                    dest,1,SlotActionType.PICKUP);
            moved++;
            cooldown=2;
        }

        private int findSource(
                net.minecraft.screen.ScreenHandler handler) {
            if(sourceSlot>=0&&!withdraw) {
                int mapped=sourceSlot<9
                        ?RealClientInventoryOps.PLAYER_HOTBAR_START
                                +sourceSlot
                        :RealClientInventoryOps.PLAYER_MAIN_START
                                +(sourceSlot-9);
                var s=handler.slots.get(mapped).getStack();
                if(RealClientInventoryOps.is(s,item))
                    return mapped;
                return -1;
            }
            for(int i=0;i<handler.slots.size();i++) {
                var slot=handler.slots.get(i);
                boolean side=isContainerSide(slot);
                if(side!=withdraw)continue; // deposit 取玩家侧
                if(RealClientInventoryOps.is(
                        slot.getStack(),item))
                    return i;
            }
            return -1;
        }

        private int findDest(
                net.minecraft.screen.ScreenHandler handler,
                int stackCount) {
            for(int i=0;i<handler.slots.size();i++) {
                var slot=handler.slots.get(i);
                boolean side=isContainerSide(slot);
                if(side!=(!withdraw))continue; // deposit 放容器侧
                var s=slot.getStack();
                if(s.isEmpty())
                    return i;
                if(RealClientInventoryOps.is(s,item)
                        &&s.getCount()<Math.min(
                                s.getMaxCount(),64))
                    return i;
            }
            return -1;
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
