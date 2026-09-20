package io.github.zoyluo.aibot.external.realclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.AIBotMod;
import io.github.zoyluo.aibot.craft.CraftingHelper;
import io.github.zoyluo.aibot.external.BodyBackend;
import io.github.zoyluo.aibot.external.BridgeFault;
import io.github.zoyluo.aibot.external.JsonOutput;
import io.github.zoyluo.aibot.external.PhysicalExecutionDriver;
import net.minecraft.item.Item;
import net.minecraft.block.BlockState;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class RealClientExecutionDriver
        implements PhysicalExecutionDriver {
    public static final Set<String> OPERATIONS=
            Set.of("say","goto","mine_opportunity","deposit","craft","eat","place","smelt");

    private static final int MAX_GOTO_DISTANCE=32;
    private static final int EXECUTION_TIMEOUT_TICKS=20*120;
    private static final long SCREEN_FRESH_MS=2500L;

    private final MinecraftServer server;
    private final RealClientServerTransport transport;
    private final RealClientOpportunityTracker tracker;
    private final Supplier<ServerPlayerEntity> body;
    private String activeExecution="";

    public RealClientExecutionDriver(
            MinecraftServer server,
            RealClientServerTransport transport,
            RealClientOpportunityTracker tracker,
            Supplier<ServerPlayerEntity> body) {
        this.server=Objects.requireNonNull(server);
        this.transport=Objects.requireNonNull(transport);
        this.tracker=Objects.requireNonNull(tracker);
        this.body=Objects.requireNonNull(body);
    }

    @Override public BodyBackend.Handle start(
            Request request) {
        onThread();
        if(!OPERATIONS.contains(request.operation()))
            throw new BridgeFault(
                    409,
                    "operation_not_supported_by_real_client_backend");
        ServerPlayerEntity player=requireBody();
        JsonObject args=parse(request.argumentsJson());
        long startedAt=server.getTicks();
        activeExecution=request.executionId();
        return switch(request.operation()) {
            case "say" ->
                    startSay(request,args,startedAt);
            case "goto" ->
                    startGoto(
                            request,args,player,startedAt);
            case "mine_opportunity" ->
                    startMine(
                            request,args,player,startedAt);
            case "deposit" ->
                    startDeposit(
                            request,args,player,startedAt);
            case "craft" ->
                    startCraft(
                            request,args,player,startedAt);
            case "eat" ->
                    startEat(
                            request,args,player,startedAt);
            case "place" ->
                    startPlace(
                            request,args,player,startedAt);
            case "smelt" ->
                    startSmelt(
                            request,args,player,startedAt);
            default -> throw new BridgeFault(
                    409,
                    "operation_not_supported_by_real_client_backend");
        };
    }

    private BodyBackend.Handle startCraft(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of("item","count"));
        Item target;
        try {
            Identifier targetId=Identifier.tryParse(
                    string(args,"item",120));
            target=targetId==null?null:Registries.ITEM.get(targetId);
        } catch(RuntimeException invalid) {
            throw new BridgeFault(400,"craft_unknown_item");
        }
        if(target==null||Registries.ITEM.getId(target).toString().equals("minecraft:air"))
            throw new BridgeFault(400,"craft_unknown_item");
        int count=integer(args,"count",1,64);
        int baseline=InventoryCrafting.countItem(player,target);
        CraftingHelper.CraftPlan plan=CraftingHelper.plan(
                player,target,count);
        if(!plan.success())
            throw new BridgeFault(
                    400,"craft_missing:"+plan.missingDescription());
        String failure=InventoryCrafting.execute(player,plan,target);
        if(failure!=null)
            throw new BridgeFault(
                    409,"craft_failed:"+failure);
        int after=InventoryCrafting.countItem(player,target);
        String reason="server_side_inventory_transformation:"
                +"baseline="+baseline+":after="+after;
        return ()->new BodyBackend.Snapshot(
                "completed",1.0D,reason);
    }

    private BodyBackend.Handle startEat(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of());
        if(player.getHungerManager().getFoodLevel()>=20)
            throw new BridgeFault(409,"eat_not_hungry");
        // 安全食物优先(排除高危),与上游 InventoryAction 语义一致
        int harmfulSlot=-1,slot=-1;
        var inventory=player.getInventory();
        for(int i=0;i<inventory.main.size();i++) {
            ItemStack stack=inventory.main.get(i);
            if(stack.isEmpty())continue;
            var food=stack.get(net.minecraft.component.DataComponentTypes.FOOD);
            if(food==null)continue;
            boolean harmful=isHarmfulFood(stack.getItem());
            if(harmful){if(harmfulSlot<0)harmfulSlot=i;continue;}
            slot=i;break;
        }
        if(slot<0)slot=harmfulSlot;
        if(slot<0)
            throw new BridgeFault(409,"eat_no_food_in_inventory");
        final int eatSlot=slot;
        String itemId=Registries.ITEM.getId(
                inventory.main.get(slot).getItem()).toString();
        int before=inventory.main.get(slot).getCount();
        int hungerBefore=player.getHungerManager().getFoodLevel();
        if(!transport.sendCommand(
                request.executionId(),"eat",
                JsonOutput.encode(Map.of(
                        "slot",slot<9?slot:0))))
            throw new BridgeFault(
                    503,"real_client_command_queue_unavailable");
        return ()->eatSnapshot(
                request.executionId(),startedAt,
                eatSlot,itemId,before,hungerBefore);
    }

    private BodyBackend.Snapshot eatSnapshot(
            String executionId,long startedAt,
            int slot,String itemId,int before,int hungerBefore) {
        onThread();
        ServerPlayerEntity player=body.get();
        if(player==null)
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,"real_client_body_unavailable");
        var remote=transport.execution(executionId).orElse(null);
        if(remote!=null&&Set.of("failed","cancelled","outcome_unknown")
                .contains(remote.state()))
            return new BodyBackend.Snapshot(
                    remote.state(),remote.progress(),remote.reason());
        ItemStack stack=player.getInventory().getStack(slot);
        int after=stack.isOf(Registries.ITEM.get(
                Identifier.tryParse(itemId)))
                ?stack.getCount():0;
        int hunger=player.getHungerManager().getFoodLevel();
        if(after<before||hunger>hungerBefore)
            return new BodyBackend.Snapshot(
                    "completed",1D,
                    "server_authoritative_food_consumed:"
                            +itemId+":"+before+"->"+after
                            +":hunger:"+hungerBefore+"->"+hunger);
        if(server.getTicks()-startedAt>EXECUTION_TIMEOUT_TICKS) {
            transport.sendControl(executionId,"cancel",
                    "real_client_execution_timeout");
            return new BodyBackend.Snapshot(
                    "failed",0D,"real_client_execution_timeout");
        }
        return new BodyBackend.Snapshot(
                "running",
                remote==null?0D:Math.min(.95D,remote.progress()),
                remote==null?"awaiting_real_client_ack":remote.reason());
    }

    private BodyBackend.Handle startPlace(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of("x","y","z","slot"));
        BlockPos target=new BlockPos(
                integer(args,"x",-29999984,29999984),
                integer(args,"y",player.getServerWorld().getBottomY(),
                        player.getServerWorld().getBottomY()
                                +player.getServerWorld().getHeight()-1),
                integer(args,"z",-29999984,29999984));
        if(!player.getServerWorld().getBlockState(target).isAir())
            throw new BridgeFault(409,"place_target_not_air");
        if(player.getEyePos().distanceTo(target.toCenterPos())>4.5D)
            throw new BridgeFault(409,"place_target_too_far");
        // 槽位自动选择:显式 slot 无 BlockItem 时扫 0-35 找任意可放置方块
        // (玩层看不到槽位号,固定传 0 是坏桩,实测封洞时 0 号槽常是工具)。
        int slot=-1;
        if(args.has("slot")) {
            int requested=integer(args,"slot",0,35);
            ItemStack req=player.getInventory().getStack(requested);
            if(!req.isEmpty()
                    &&req.getItem() instanceof net.minecraft.item.BlockItem)
                slot=requested;
        }
        if(slot<0) {
            for(int i=0;i<player.getInventory().size();i++) {
                ItemStack cand=player.getInventory().getStack(i);
                if(!cand.isEmpty()
                        &&cand.getItem() instanceof net.minecraft.item.BlockItem) {
                    slot=i;
                    break;
                }
            }
        }
        if(slot<0)
            throw new BridgeFault(409,"place_no_block_item_in_inventory");
        ItemStack stack=player.getInventory().getStack(slot);
        String itemId=Registries.ITEM.getId(stack.getItem()).toString();
        int before=stack.getCount();
        if(slot>8)
            throw new BridgeFault(
                    409,"place_block_item_not_in_hotbar");
        if(!transport.sendCommand(
                request.executionId(),"place",
                JsonOutput.encode(Map.of(
                        "slot",slot,
                        "x",target.getX(),
                        "y",target.getY(),
                        "z",target.getZ()))))
            throw new BridgeFault(
                    503,"real_client_command_queue_unavailable");
        return ()->placeSnapshot(
                request.executionId(),startedAt,target,itemId,before);
    }

    private BodyBackend.Snapshot placeSnapshot(
            String executionId,long startedAt,
            BlockPos target,String itemId,int before) {
        onThread();
        ServerPlayerEntity player=body.get();
        if(player==null)
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,"real_client_body_unavailable");
        var state=player.getServerWorld().getBlockState(target);
        if(!state.isAir()) {
            int after=countItem(player,itemId);
            return new BodyBackend.Snapshot(
                    "completed",1D,
                    "server_authoritative_block_placed:"
                            +itemId+":"+before+"->"+after);
        }
        if(server.getTicks()-startedAt>20*20) {
            transport.sendControl(executionId,"cancel",
                    "real_client_execution_timeout");
            return new BodyBackend.Snapshot(
                    "failed",0D,"real_client_execution_timeout");
        }
        var remote=transport.execution(executionId).orElse(null);
        return new BodyBackend.Snapshot(
                "running",
                remote==null?0D:Math.min(.9D,remote.progress()),
                remote==null?"awaiting_real_client_ack":remote.reason());
    }

    private BodyBackend.Handle startSmelt(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of("input_item","fuel_item","count"));
        String inputId=string(args,"input_item",120);
        String fuelId=string(args,"fuel_item",120);
        int count=integer(args,"count",1,12);
        Identifier inId=Identifier.tryParse(inputId);
        Identifier fuId=Identifier.tryParse(fuelId);
        if(inId==null||fuId==null
                ||!Registries.ITEM.containsId(inId)
                ||!Registries.ITEM.containsId(fuId))
            throw new BridgeFault(400,"smelt_unknown_item");
        if(countItem(player,inputId)<count)
            throw new BridgeFault(409,"smelt_input_insufficient");
        if(countItem(player,fuelId)<1)
            throw new BridgeFault(409,"smelt_fuel_missing");
        // 找伸手可及的熔炉(半径 2)
        BlockPos found=null;
        BlockPos origin=player.getBlockPos();
        for(BlockPos pos:BlockPos.iterate(
                origin.add(-2,-2,-2),origin.add(2,2,2))) {
            if(player.getServerWorld().getBlockState(pos)
                    .isOf(net.minecraft.block.Blocks.FURNACE)) {
                found=pos.toImmutable();
                break;
            }
        }
        if(found==null)
            throw new BridgeFault(
                    409,"smelt_no_furnace_within_reach");
        if(player.getEyePos().distanceTo(found.toCenterPos())>4.5D)
            throw new BridgeFault(409,"smelt_furnace_too_far");
        int baseline=countItem(player,inputId);
        if(!transport.sendCommand(
                request.executionId(),"smelt",
                JsonOutput.encode(Map.of(
                        "furnace_x",found.getX(),
                        "furnace_y",found.getY(),
                        "furnace_z",found.getZ(),
                        "input_item",inputId,
                        "fuel_item",fuelId))))
            throw new BridgeFault(
                    503,"real_client_command_queue_unavailable");
        return ()->smeltSnapshot(
                request.executionId(),startedAt,inputId,baseline);
    }

    private BodyBackend.Snapshot smeltSnapshot(
            String executionId,long startedAt,
            String inputId,int baseline) {
        onThread();
        ServerPlayerEntity player=body.get();
        if(player==null)
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,"real_client_body_unavailable");
        var remote=transport.execution(executionId).orElse(null);
        if(remote!=null&&Set.of("failed","cancelled","outcome_unknown")
                .contains(remote.state()))
            return new BodyBackend.Snapshot(
                    remote.state(),remote.progress(),remote.reason());
        int current=countItem(player,inputId);
        if("completed".equals(remote==null?"":remote.state())
                &&current<baseline)
            return new BodyBackend.Snapshot(
                    "completed",1D,
                    "server_authoritative_input_consumed:"
                            +baseline+"->"+current);
        if(server.getTicks()-startedAt>20*240) {
            transport.sendControl(executionId,"cancel",
                    "real_client_execution_timeout");
            return new BodyBackend.Snapshot(
                    "failed",0D,"real_client_execution_timeout");
        }
        return new BodyBackend.Snapshot(
                "running",
                remote==null?0D:Math.min(.95D,remote.progress()),
                remote==null?"awaiting_real_client_ack":remote.reason());
    }

    private static boolean isHarmfulFood(Item item) {
        return item==net.minecraft.item.Items.CHICKEN
                ||item==net.minecraft.item.Items.ROTTEN_FLESH
                ||item==net.minecraft.item.Items.PUFFERFISH
                ||item==net.minecraft.item.Items.SPIDER_EYE
                ||item==net.minecraft.item.Items.POISONOUS_POTATO;
    }

    private BodyBackend.Handle startSay(
            Request request,JsonObject args,long startedAt) {
        only(args,Set.of("message"));
        String message=string(args,"message",1000);
        if(!transport.sendCommand(
                request.executionId(),"say",
                JsonOutput.encode(Map.of(
                        "message",message))))
            throw new BridgeFault(
                    503,
                    "real_client_command_queue_unavailable");
        return ()->remoteSnapshot(
                request.executionId(),startedAt,null,true);
    }

    private BodyBackend.Handle startGoto(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of(
                "x","y","z",
                "allow_terrain_changes",
                "face_x","face_y","face_z"));
        // MC-RCF-1 G2: 本轮导航默认不挖不放(客户端 Baritone allowBreak/allowPlace
        // 强制 false)。旧注释"true 已授权"指的挖穿机制已从正式路径移除;
        // 现在 true 必须显式拒绝,不得默默忽略(01_ROUTE §3 导航路线)。
        if(args.has("allow_terrain_changes")
                &&args.get("allow_terrain_changes").getAsBoolean())
            throw new BridgeFault(
                    400,
                    "terrain_changes_not_supported_this_round");


        BlockPos target=new BlockPos(
                integer(
                        args,"x",
                        -29999984,29999984),
                integer(
                        args,"y",
                        player.getServerWorld().getBottomY(),
                        player.getServerWorld().getBottomY()
                                +player.getServerWorld()
                                        .getHeight()-1),
                integer(
                        args,"z",
                        -29999984,29999984));
        if(player.getBlockPos().getSquaredDistance(target)
                >MAX_GOTO_DISTANCE*MAX_GOTO_DISTANCE)
            throw new BridgeFault(
                    400,
                    "real_client_goto_distance_limit_32");

        boolean hasFace=
                args.has("face_x")
                ||args.has("face_y")
                ||args.has("face_z");
        if(hasFace
                &&(!args.has("face_x")
                ||!args.has("face_y")
                ||!args.has("face_z")))
            throw new BridgeFault(
                    400,
                    "real_client_goto_face_requires_all_axes");

        BlockPos faceTarget=null;
        if(hasFace) {
            faceTarget=new BlockPos(
                    integer(
                            args,"face_x",
                            -29999984,29999984),
                    integer(
                            args,"face_y",
                            player.getServerWorld().getBottomY(),
                            player.getServerWorld().getBottomY()
                                    +player.getServerWorld()
                                            .getHeight()-1),
                    integer(
                            args,"face_z",
                            -29999984,29999984));
            if(player.getBlockPos()
                    .getSquaredDistance(faceTarget)>16D*16D)
                throw new BridgeFault(
                        400,
                        "real_client_goto_face_distance_limit_16");
        }

        Map<String,Object> command=
                new LinkedHashMap<>();
        command.put("x",target.getX());
        command.put("y",target.getY());
        command.put("z",target.getZ());
        command.put("arrival_radius",2.5D);
        if(faceTarget!=null) {
            command.put("face_x",faceTarget.getX());
            command.put("face_y",faceTarget.getY());
            command.put("face_z",faceTarget.getZ());
        }
        if(!transport.sendCommand(
                request.executionId(),"goto",
                JsonOutput.encode(command)))
            throw new BridgeFault(
                    503,
                    "real_client_command_queue_unavailable");

        BlockPos finalFace=faceTarget;
        final double[] lastDistance={-1D};
        final long[] lastProgressTick={startedAt};
        return ()->gotoSnapshot(
                request.executionId(),startedAt,
                target,finalFace,lastDistance,lastProgressTick);
    }

    private BodyBackend.Snapshot gotoSnapshot(
            String executionId,long startedAt,
            BlockPos target,BlockPos faceTarget,
            double[] lastDistance,long[] lastProgressTick) {
        onThread();
        ServerPlayerEntity current=body.get();
        if(current==null)
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_body_unavailable");
        // 无进展检测:120 秒距目标距离无改善即失败并通知客户端收尾
        // (需覆盖客户端绕障序列 6x25tick + 空手挖穿石墙的时间),
        // 避免卡死执行占满服务器单槽 240 秒(实测执行槽饥饿)。
        double distanceNow=current.getPos()
                .squaredDistanceTo(target.toCenterPos());
        if(lastDistance[0]<0D
                ||distanceNow<lastDistance[0]-0.25D) {
            lastDistance[0]=distanceNow;
            lastProgressTick[0]=server.getTicks();
        } else if(server.getTicks()-lastProgressTick[0]
                >20*120) {
            transport.sendControl(
                    executionId,"cancel",
                    "real_client_goto_stalled");
            return new BodyBackend.Snapshot(
                    "failed",0D,"real_client_goto_stalled");
        }

        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(
                        System.currentTimeMillis()))
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_session_unavailable");

        var remote=transport.execution(
                executionId).orElse(null);
        if(remote!=null
                &&Set.of(
                        "failed","cancelled","outcome_unknown")
                        .contains(remote.state()))
            return new BodyBackend.Snapshot(
                    remote.state(),remote.progress(),
                    remote.reason());

        boolean arrived=current.getPos()
                .squaredDistanceTo(target.toCenterPos())
                <=6.25D;
        if(faceTarget==null && arrived)
            return new BodyBackend.Snapshot(
                    "completed",1D,
                    "server_authoritative_arrival_within_2_5_blocks");

        if(faceTarget!=null
                &&arrived
                &&remote!=null
                &&"completed".equals(remote.state())
                &&"client_arrival_and_facing_reported"
                        .equals(remote.reason())) {
            var sensor=session.sensor();
            long now=System.currentTimeMillis();
            if(sensor!=null
                    &&sensor.gameSession()
                            .equals(session.gameSession())
                    &&now-sensor.receivedAtMs()
                            <=RealClientOpportunityTracker
                                    .FRAME_FRESH_MS
                    &&sensor.crosshairPresent()
                    &&Math.abs(sensor.crosshairX()-faceTarget.getX())<=1
                    &&Math.abs(sensor.crosshairY()-faceTarget.getY())<=1
                    &&Math.abs(sensor.crosshairZ()-faceTarget.getZ())<=1)
                return new BodyBackend.Snapshot(
                        "completed",1D,
                        "server_authoritative_arrival_and_facing_verified");
        }

        if(server.getTicks()-startedAt
                >EXECUTION_TIMEOUT_TICKS) {
            transport.sendControl(
                    executionId,"cancel",
                    "real_client_execution_timeout");
            return new BodyBackend.Snapshot(
                    "failed",0D,
                    "real_client_execution_timeout");
        }

        String reason=faceTarget!=null && arrived
                ?"awaiting_client_final_facing_ack"
                :remote==null
                        ?"awaiting_real_client_ack"
                        :remote.reason();
        return new BodyBackend.Snapshot(
                "running",
                remote==null
                        ?0D
                        :Math.min(.99D,remote.progress()),
                reason);
    }

    private BodyBackend.Handle startMine(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of("id"));
        String opportunityId=string(args,"id",64);
        RealClientOpportunityTracker.Opportunity opportunity=
                tracker.opportunity(player,opportunityId)
                        .orElseThrow(
                                ()->new BridgeFault(
                                        404,
                                        "real_client_resource_"
                                                +"opportunity_not_found"));
        var state=player.getServerWorld()
                .getBlockState(opportunity.pos());
        String actualBlock=
                Registries.BLOCK.getId(
                        state.getBlock()).toString();
        if(!actualBlock.equals(opportunity.blockId())) {
            tracker.markStale(
                    request.executionId(),
                    opportunity,
                    "target_cell_changed_before_dispatch");
            throw new BridgeFault(
                    409,
                    "real_client_resource_opportunity_stale");
        }

        Identifier expectedId=
                Identifier.tryParse(
                        opportunity.expectedItem());
        if(expectedId==null
                ||!Registries.ITEM.containsId(expectedId)
                ||"minecraft:air".equals(
                        opportunity.expectedItem()))
            throw new BridgeFault(
                    409,
                    "real_client_expected_drop_not_supported");

        int slot=selectSuitableHotbar(player,state);
        if(slot<0)
            throw new BridgeFault(
                    409,
                    "real_client_required_hotbar_tool_missing");

        int baseline=countItem(
                player,opportunity.expectedItem());
        String face=transport.session()
                .map(RealClientServerTransport
                        .SessionSnapshot::sensor)
                .filter(sensor->
                        sensor!=null
                        &&sensor.crosshairPresent()
                        &&sensor.crosshairX()
                                ==opportunity.pos().getX()
                        &&sensor.crosshairY()
                                ==opportunity.pos().getY()
                        &&sensor.crosshairZ()
                                ==opportunity.pos().getZ())
                .map(RealClientServerTransport
                        .SensorSnapshot::crosshairSide)
                .orElse("UP");

        Map<String,Object> command=
                new LinkedHashMap<>();
        command.put("x",opportunity.pos().getX());
        command.put("y",opportunity.pos().getY());
        command.put("z",opportunity.pos().getZ());
        command.put("face",face);
        command.put("slot",slot);
        command.put("block",opportunity.blockId());
        command.put(
                "expected_item",
                opportunity.expectedItem());
        command.put("baseline_count",baseline);
        if(!transport.sendCommand(
                request.executionId(),
                "mine_opportunity",
                JsonOutput.encode(command)))
            throw new BridgeFault(
                    503,
                    "real_client_command_queue_unavailable");
        return ()->mineSnapshot(
                request.executionId(),startedAt,
                opportunity,baseline);
    }

    private BodyBackend.Snapshot mineSnapshot(
            String executionId,long startedAt,
            RealClientOpportunityTracker.Opportunity opportunity,
            int baseline) {
        onThread();
        ServerPlayerEntity player=body.get();
        if(player==null)
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_body_unavailable");

        var currentState=player.getServerWorld()
                .getBlockState(opportunity.pos());
        String currentBlock=
                Registries.BLOCK.getId(
                        currentState.getBlock()).toString();
        int current=countItem(
                player,opportunity.expectedItem());

        if(!currentState.isAir()
                &&!currentBlock.equals(
                        opportunity.blockId())) {
            tracker.markStale(
                    executionId,opportunity,
                    "target_cell_replaced_during_execution");
            return new BodyBackend.Snapshot(
                    "failed",0D,
                    "real_client_resource_opportunity_stale");
        }

        if(currentState.isAir()&&current>baseline) {
            tracker.markConsumed(
                    executionId,opportunity,
                    player,baseline,current);
            return new BodyBackend.Snapshot(
                    "completed",1D,
                    "server_authoritative_block_and_"
                            +"inventory_gain_verified:"
                            +baseline+"->"+current);
        }

        if(server.getTicks()-startedAt
                >EXECUTION_TIMEOUT_TICKS) {
            transport.sendControl(
                    executionId,"cancel",
                    "real_client_execution_timeout");
            if(currentState.isAir()) {
                tracker.markStale(
                        executionId,opportunity,
                        "block_gone_without_inventory_"
                                +"gain_before_timeout");
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "real_client_resource_opportunity_stale");
            }
            return new BodyBackend.Snapshot(
                    "failed",0D,
                    "real_client_execution_timeout");
        }

        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(
                        System.currentTimeMillis()))
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_session_unavailable");

        var remote=transport.execution(
                executionId).orElse(null);
        if(remote!=null
                &&Set.of(
                        "failed","cancelled","outcome_unknown")
                        .contains(remote.state())) {
            if(currentState.isAir()
                    &&!"outcome_unknown".equals(
                            remote.state())) {
                tracker.markStale(
                        executionId,opportunity,
                        "block_gone_without_inventory_gain:"
                                +remote.state());
                return new BodyBackend.Snapshot(
                        "failed",remote.progress(),
                        "real_client_resource_opportunity_stale");
            }
            return new BodyBackend.Snapshot(
                    remote.state(),remote.progress(),
                    remote.reason());
        }

        String reason=remote!=null
                &&"completed".equals(remote.state())
                ?"client_completed_awaiting_server_"
                        +"block_inventory_proof"
                :remote==null
                        ?"awaiting_real_client_ack"
                        :remote.reason();
        return new BodyBackend.Snapshot(
                "running",
                remote==null
                        ?0D
                        :Math.min(.99D,remote.progress()),
                reason);
    }

    private BodyBackend.Handle startDeposit(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of());
        RealClientStorageTarget target=
                RealClientStorageTarget.resolve(player,transport);

        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(System.currentTimeMillis()))
            throw new BridgeFault(
                    409,"real_client_sensor_unavailable");
        var priorScreen=session.screen();
        if(priorScreen!=null && priorScreen.present())
            throw new BridgeFault(
                    409,"real_client_screen_already_open");
        long baselineScreenSeq=
                priorScreen==null?-1L:priorScreen.screenSeq();

        int playerBaseline=countPlayerInventory(player);
        long targetBaseline=target.count();

        Map<String,Object> command=new LinkedHashMap<>();
        command.put("phase","open");
        command.put("target_kind",target.kindWire());
        command.put("x",target.pos().getX());
        command.put("y",target.pos().getY());
        command.put("z",target.pos().getZ());
        command.put("face",target.face());
        command.put("baseline_screen_seq",baselineScreenSeq);

        if(!transport.sendCommand(
                request.executionId(),"deposit",
                JsonOutput.encode(command)))
            throw new BridgeFault(
                    503,"real_client_command_queue_unavailable");

        return new DepositHandle(
                request.executionId(),startedAt,
                target,playerBaseline,
                targetBaseline,baselineScreenSeq);
    }

    private final class DepositHandle
            implements BodyBackend.Handle {
        private final String executionId;
        private final long startedAt;
        private final RealClientStorageTarget target;
        private final int playerBaseline;
        private final long targetBaseline;
        private final long baselineScreenSeq;

        private boolean commitSent;
        private boolean mutationAckSeen;
        private String ownedScreenEpoch="";
        private String ownedAdapterId="";
        private long ownedScreenSeq=-1L;
        private int ownedSyncId=-1;

        DepositHandle(
                String executionId,long startedAt,
                RealClientStorageTarget target,
                int playerBaseline,long targetBaseline,
                long baselineScreenSeq) {
            this.executionId=executionId;
            this.startedAt=startedAt;
            this.target=target;
            this.playerBaseline=playerBaseline;
            this.targetBaseline=targetBaseline;
            this.baselineScreenSeq=baselineScreenSeq;
        }

        @Override public BodyBackend.Snapshot snapshot() {
            onThread();
            ServerPlayerEntity player=body.get();
            if(player==null)
                return new BodyBackend.Snapshot(
                        "outcome_unknown",0D,
                        "real_client_body_unavailable");

            if(!target.stillValid(player)) {
                transport.sendControl(
                        executionId,"cancel",
                        "real_client_deposit_target_changed");
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "real_client_deposit_target_changed");
            }

            int playerCurrent=countPlayerInventory(player);
            long targetCurrent=target.count();
            int fromPlayer=playerBaseline-playerCurrent;
            long intoTarget=targetCurrent-targetBaseline;

            var session=transport.session().orElse(null);
            if(session==null
                    ||!session.fresh(System.currentTimeMillis()))
                return new BodyBackend.Snapshot(
                        "outcome_unknown",0D,
                        "real_client_session_unavailable");

            var remote=transport.execution(executionId).orElse(null);
            if(commitSent && remote!=null
                    &&("client_owned_screen_quick_move"
                            .equals(remote.reason())
                    ||"client_owned_screen_settling"
                            .equals(remote.reason())
                    ||"client_owned_screen_quick_move_finished"
                            .equals(remote.reason())
                    ||"completed".equals(remote.state())))
                mutationAckSeen=true;

            if(commitSent && mutationAckSeen
                    &&fromPlayer>0 && intoTarget==fromPlayer)
                return new BodyBackend.Snapshot(
                        "completed",1D,
                        "server_authoritative_owned_screen_"
                                +target.kindWire()
                                +"_transfer_verified:"+fromPlayer);

            if(remote!=null
                    &&Set.of("failed","cancelled","outcome_unknown")
                            .contains(remote.state()))
                return new BodyBackend.Snapshot(
                        remote.state(),remote.progress(),
                        remote.reason());

            if(server.getTicks()-startedAt
                    >EXECUTION_TIMEOUT_TICKS) {
                transport.sendControl(
                        executionId,"cancel",
                        "real_client_execution_timeout");
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "real_client_storage_transfer_not_proven");
            }

            var screen=session.screen();
            if(!commitSent) {
                if(screen!=null
                        &&screen.present()
                        &&screen.screenSeq()>baselineScreenSeq)
                    authorizeOwnedScreen(player,screen);
                if(!commitSent)
                    return new BodyBackend.Snapshot(
                            "running",
                            remote==null
                                    ?.25D
                                    :Math.min(.49D,remote.progress()),
                            "awaiting_server_owned_target_screen");
            }

            // A normal completed client action closes the screen before the server observes its
            // inventory proof. Ownership loss is fatal only while the client still claims RUNNING.
            boolean clientCompleted=remote!=null
                    &&"completed".equals(remote.state());
            if(!clientCompleted
                    &&(screen==null
                    ||!screen.present()
                    ||!ownedScreenEpoch.equals(screen.screenEpoch())
                    ||!ownedAdapterId.equals(screen.adapterId())
                    ||ownedSyncId!=screen.syncId()
                    ||screen.screenSeq()<ownedScreenSeq)) {
                io.github.zoyluo.aibot.AIBotMod.LOGGER.warn(
                        "real-client deposit ownership lost diag:"
                                +" remote={} screenPresent={}",
                        remote==null?"null":remote.state(),
                        screen==null?"null":Boolean.toString(screen.present()));
                transport.sendControl(
                        executionId,"cancel",
                        "real_client_screen_ownership_lost");
                return new BodyBackend.Snapshot(
                        "failed",
                        remote==null?0D:remote.progress(),
                        "real_client_screen_ownership_lost");
            }

            String reason=remote!=null
                    &&"completed".equals(remote.state())
                    ?"client_completed_awaiting_server_"
                            +"storage_inventory_proof"
                    :remote==null
                            ?"awaiting_real_client_ack"
                            :remote.reason();
            return new BodyBackend.Snapshot(
                    "running",
                    remote==null?.5D:Math.min(.99D,remote.progress()),
                    reason);
        }

        private void authorizeOwnedScreen(
                ServerPlayerEntity player,
                RealClientServerTransport.ScreenSnapshot screen) {
            if(System.currentTimeMillis()-screen.receivedAtMs()
                    >SCREEN_FRESH_MS)
                return;
            if(!sessionMatchesCurrentGame(screen))
                return;
            if(!target.adapterAllowed(screen.adapterId()))
                return;
            if(!screen.capabilities().contains("deposit_quick_move"))
                return;
            if(player.currentScreenHandler==player.playerScreenHandler)
                return;
            if(player.currentScreenHandler.syncId!=screen.syncId())
                return;
            if(!target.handlerOwns(player,screen))
                return;

            Map<String,Object> commit=new LinkedHashMap<>();
            commit.put("phase","commit");
            commit.put("target_kind",target.kindWire());
            commit.put("screen_epoch",screen.screenEpoch());
            commit.put("screen_seq",screen.screenSeq());
            commit.put("sync_id",screen.syncId());
            commit.put("adapter_id",screen.adapterId());
            if(!transport.sendCommand(
                    executionId,"deposit",
                    JsonOutput.encode(commit)))
                throw new BridgeFault(
                        503,"real_client_command_queue_unavailable");

            commitSent=true;
            ownedScreenEpoch=screen.screenEpoch();
            ownedAdapterId=screen.adapterId();
            ownedScreenSeq=screen.screenSeq();
            ownedSyncId=screen.syncId();
        }

        private boolean sessionMatchesCurrentGame(
                RealClientServerTransport.ScreenSnapshot screen) {
            var session=transport.session().orElse(null);
            return session!=null
                    &&screen.gameSession().equals(session.gameSession());
        }
    }

    private BodyBackend.Snapshot remoteSnapshot(
            String executionId,long startedAt,
            Supplier<BodyBackend.Snapshot> proof,
            boolean allowRemoteCompleted) {
        onThread();
        BodyBackend.Snapshot proven=
                proof==null?null:proof.get();
        if(proven!=null)return proven;

        if(server.getTicks()-startedAt
                >EXECUTION_TIMEOUT_TICKS) {
            transport.sendControl(
                    executionId,"cancel",
                    "real_client_execution_timeout");
            return new BodyBackend.Snapshot(
                    "failed",0D,
                    "real_client_execution_timeout");
        }

        var session=transport.session().orElse(null);
        if(session==null
                ||!session.fresh(
                        System.currentTimeMillis()))
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,
                    "real_client_session_unavailable");

        var remote=transport.execution(
                executionId).orElse(null);
        if(remote==null)
            return new BodyBackend.Snapshot(
                    "running",0D,
                    "awaiting_real_client_ack");
        if("completed".equals(remote.state())
                &&!allowRemoteCompleted)
            return new BodyBackend.Snapshot(
                    "running",
                    Math.min(.99D,remote.progress()),
                    "client_completed_awaiting_server_postcondition");
        return new BodyBackend.Snapshot(
                remote.state(),remote.progress(),
                remote.reason());
    }

    @Override public void pause() {
        onThread();
        if(!activeExecution.isBlank())
            transport.sendControl(
                    activeExecution,"pause",
                    "external_pause");
    }

    @Override public void resume() {
        onThread();
        if(!activeExecution.isBlank())
            transport.sendControl(
                    activeExecution,"resume",
                    "external_resume");
    }

    @Override public void cancel(String reason) {
        onThread();
        if(!activeExecution.isBlank())
            transport.sendControl(
                    activeExecution,"cancel",
                    reason==null
                            ?"external_cancel":reason);
    }

    private ServerPlayerEntity requireBody() {
        ServerPlayerEntity player=body.get();
        if(player==null || !player.isAlive())
            throw new BridgeFault(
                    409,"body_unavailable");
        return player;
    }

    private void onThread() {
        if(!server.isOnThread())
            throw new IllegalStateException(
                    "minecraft_access_off_server_thread");
    }

    private static int selectSuitableHotbar(
            ServerPlayerEntity player,BlockState state) {
        for(int slot=0;slot<9;slot++) {
            ItemStack stack=
                    player.getInventory().getStack(slot);
            if(!stack.isEmpty()
                    &&stack.isSuitableFor(state))
                return slot;
        }
        // 无工具要求的方块(原木/泥土等)徒手即可挖:用当前持物槽。
        if(!state.isToolRequired())
            return player.getInventory().selectedSlot;
        return -1;
    }

    private static int countItem(
            ServerPlayerEntity player,String itemId) {
        int count=0;
        for(int slot=0;
                slot<player.getInventory().size();
                slot++) {
            ItemStack stack=
                    player.getInventory().getStack(slot);
            if(!stack.isEmpty()
                    &&Registries.ITEM.getId(
                            stack.getItem()).toString()
                            .equals(itemId))
                count+=stack.getCount();
        }
        return count;
    }

    private static int countPlayerInventory(
            ServerPlayerEntity player) {
        int count=0;
        int selected=
                player.getInventory().selectedSlot;
        for(int slot=0;slot<36;slot++) {
            if(slot==selected)continue;
            ItemStack stack=
                    player.getInventory().getStack(slot);
            if(!stack.isEmpty())
                count+=stack.getCount();
        }
        return count;
    }

    private static int countInventory(
            Inventory inventory) {
        int count=0;
        for(int slot=0;slot<inventory.size();slot++)
            if(!inventory.getStack(slot).isEmpty())
                count+=inventory.getStack(slot).getCount();
        return count;
    }

    private static JsonObject parse(String raw) {
        try {
            JsonElement value=
                    JsonParser.parseString(raw);
            if(!value.isJsonObject())
                throw new IllegalArgumentException();
            return value.getAsJsonObject();
        } catch(RuntimeException invalid) {
            throw new BridgeFault(
                    400,
                    "invalid_arguments_json_object");
        }
    }

    private static void only(
            JsonObject object,Set<String> allowed) {
        if(!allowed.containsAll(object.keySet()))
            throw new BridgeFault(
                    400,"unknown_argument");
    }

    private static String string(
            JsonObject object,String key,int max) {
        JsonElement value=object.get(key);
        if(value==null
                ||!value.isJsonPrimitive()
                ||!value.getAsJsonPrimitive().isString())
            throw new BridgeFault(
                    400,"string_required:"+key);
        String result=value.getAsString();
        if(result.isBlank() || result.length()>max)
            throw new BridgeFault(
                    400,"invalid_string_length:"+key);
        return result;
    }

    private static int integer(
            JsonObject object,String key,int min,int max) {
        try {
            JsonElement value=object.get(key);
            if(value==null
                    ||!value.isJsonPrimitive()
                    ||!value.getAsJsonPrimitive()
                            .isNumber())
                throw new IllegalArgumentException();
            int result=value.getAsBigDecimal()
                    .intValueExact();
            if(result<min || result>max)
                throw new IllegalArgumentException();
            return result;
        } catch(RuntimeException invalid) {
            throw new BridgeFault(
                    400,"integer_out_of_range:"+key);
        }
    }
}
