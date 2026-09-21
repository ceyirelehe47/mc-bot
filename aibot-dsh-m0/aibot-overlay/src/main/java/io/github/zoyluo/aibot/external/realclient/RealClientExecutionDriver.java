package io.github.zoyluo.aibot.external.realclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.AIBotMod;
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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class RealClientExecutionDriver
        implements PhysicalExecutionDriver {
    public static final Set<String> OPERATIONS=
            Set.of("say","goto","mine_opportunity","deposit","craft",
                    "eat","place","move_items","container_transfer");

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
            case "container_transfer" ->
                    startContainerTransfer(
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
            case "move_items" ->
                    startMoveItems(
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
        // MC-RCF-1 G3b:原生客户端合成。count=本次新增产出目标(增量语义,
        // 按合法配方批次向上取整,回报实际新增)。服务端写包路径已撤除。
        String targetId=string(args,"item",120);
        if(Identifier.tryParse(targetId)==null
                ||Registries.ITEM.get(
                        Identifier.tryParse(targetId))
                        ==net.minecraft.item.Items.AIR)
            throw new BridgeFault(400,"craft_unknown_item");
        int count=integer(args,"count",1,2304);
        NativeLayout layout=nativeLayoutFor(
                targetId,player);
        if(layout==null)
            throw new BridgeFault(
                    400,"craft_recipe_not_in_core_chain");
        int batches=(count+layout.outputPerBatch-1)
                /layout.outputPerBatch;
        // 材料在场核验(增量批次所需)
        for(var e:layout.needed.entrySet()) {
            int have=countItem(player,e.getKey());
            if(have<e.getValue()*batches)
                throw new BridgeFault(409,
                        "craft_missing:"+e.getKey()
                                +"have="+have
                                +"need="+e.getValue()*batches);
        }
        BlockPos table=null;
        if(layout.needsTable) {
            table=findNearbyCraftingTable(player);
            if(table==null)
                throw new BridgeFault(
                        409,"craft_no_crafting_table_nearby");
        }
        int baseline=countItem(player,targetId);
        // R2/R07:材料基线在计划期捕获,完成时核对真实消耗守恒。
        Map<String,Integer> materialBaselines=new LinkedHashMap<>();
        for(var e:layout.needed.entrySet())
            materialBaselines.put(e.getKey(),countItem(player,e.getKey()));
        Map<String,Object> command=new LinkedHashMap<>();
        command.put("grid",layout.grid);
        command.put("result_item",targetId);
        command.put("batches",batches);
        if(table!=null) {
            command.put("table_x",table.getX());
            command.put("table_y",table.getY());
            command.put("table_z",table.getZ());
        }
        if(!transport.sendCommand(
                request.executionId(),"craft",
                JsonOutput.encode(command)))
            throw new BridgeFault(
                    503,"real_client_command_queue_unavailable");
        return ()->craftSnapshot(
                request.executionId(),startedAt,
                targetId,baseline,count,batches,
                layout.outputPerBatch,layout.needed,materialBaselines);
    }

    private BodyBackend.Snapshot craftSnapshot(
            String executionId,long startedAt,
            String targetId,int baseline,int want,int batches,
            int outputPerBatch,Map<String,Integer> needed,
            Map<String,Integer> materialBaselines) {
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
        if("completed".equals(remote==null?"":remote.state())) {
            int after=countItem(player,targetId);
            int delta=after-baseline;
            // R2/R07:q=5、p=4、两批应产8——completed 必须是完整批次乘积,
            // 不是 min(want,fullOutput) 下限;delta=5 或 q=32 只有8都拒绝。
            // delta>full 只能是外部混入,同样不可归因。
            int fullOutput=batches*outputPerBatch;
            if(delta!=fullOutput) {
                transport.sendControl(executionId,"cancel",
                        "craft_full_batch_unproven");
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "craft_full_batch_unproven:delta="+delta
                                +"/"+fullOutput
                                +"(want="+want+",batches="+batches
                                +",per="+outputPerBatch+")");
            }
            // 材料守恒:每个配方材料真实消耗==needed*batches。
            // 受并发影响不能归因时按 unknown 报告,不虚构守恒。
            for(var e:needed.entrySet()) {
                int before=materialBaselines.getOrDefault(
                        e.getKey(),0);
                int now=countItem(player,e.getKey());
                int consumed=before-now;
                int expected=e.getValue()*batches;
                if(consumed!=expected)
                    return new BodyBackend.Snapshot(
                            "outcome_unknown",1D,
                            "craft_material_balance_unattributable:"
                                    +e.getKey()+":"+before+"->"+now
                                    +":consumed="+consumed
                                    +":expected="+expected);
            }
            StringBuilder materials=new StringBuilder();
            for(var e:needed.entrySet())
                materials.append(e.getKey()).append(':')
                        .append(materialBaselines.get(e.getKey()))
                        .append("->").append(countItem(player,e.getKey()))
                        .append(',');
            return new BodyBackend.Snapshot(
                    "completed",1D,
                    "server_authoritative_native_craft:"
                            +targetId+":"+baseline+"->"+after
                            +":delta="+delta+":batches="+batches
                            +":output_per_batch="+outputPerBatch
                            +":materials="+materials);
        }
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

    /** 核心链配方布局(2×2 子集或 3×3),材料按玩家在场的具体木头推导。 */
    private record NativeLayout(
            List<String> grid,int outputPerBatch,
            boolean needsTable,
            Map<String,Integer> needed) {}

    private NativeLayout nativeLayoutFor(
            String targetId,ServerPlayerEntity player) {
        String[] WOODS={"oak","spruce","birch","jungle",
                "acacia","dark_oak","mangrove","cherry"};
        // 木板:x_log → x_planks(无序,单格)
        for(String w:WOODS) {
            if(targetId.equals("minecraft:"+w+"_planks")) {
                String log="minecraft:"+w+"_log";
                if(countItem(player,log)<=0)
                    return null; // 玩家无此木:让上层选在场木种
                return new NativeLayout(
                        Arrays.asList(log,null,null,null),
                        4,false,Map.of(log,1));
            }
        }
        if(targetId.equals("minecraft:stick")) {
            String planks=firstSufficient(player,2,
                    "minecraft:oak_planks","minecraft:spruce_planks",
                    "minecraft:birch_planks","minecraft:jungle_planks",
                    "minecraft:acacia_planks",
                    "minecraft:dark_oak_planks",
                    "minecraft:mangrove_planks",
                    "minecraft:cherry_planks");
            if(planks==null)return null;
            return new NativeLayout(
                    Arrays.asList(planks,null,planks,null),
                    4,false,Map.of(planks,2));
        }
        if(targetId.equals("minecraft:crafting_table")) {
            String planks=firstSufficient(player,4,
                    "minecraft:oak_planks","minecraft:spruce_planks",
                    "minecraft:birch_planks","minecraft:jungle_planks",
                    "minecraft:acacia_planks",
                    "minecraft:dark_oak_planks",
                    "minecraft:mangrove_planks",
                    "minecraft:cherry_planks");
            if(planks==null)return null;
            return new NativeLayout(
                    Arrays.asList(planks,planks,planks,planks),
                    1,false,Map.of(planks,4));
        }
        // 木镐:任意木板 3 + 木棍 2(3×3,需工作台)。原版 id=wooden_pickaxe
        if(targetId.equals("minecraft:wooden_pickaxe")) {
            String planks=firstSufficient(player,3,
                    "minecraft:oak_planks","minecraft:spruce_planks",
                    "minecraft:birch_planks","minecraft:jungle_planks",
                    "minecraft:acacia_planks",
                    "minecraft:dark_oak_planks",
                    "minecraft:mangrove_planks",
                    "minecraft:cherry_planks");
            if(planks==null)return null;
            return new NativeLayout(
                    Arrays.asList(planks,planks,planks,
                            null,"minecraft:stick",null,
                            null,"minecraft:stick",null),
                    1,true,
                    Map.of(planks,3,
                            "minecraft:stick",2));
        }
        if(targetId.equals("minecraft:stone_pickaxe")) {
            return new NativeLayout(
                    Arrays.asList("minecraft:cobblestone",
                            "minecraft:cobblestone",
                            "minecraft:cobblestone",
                            null,"minecraft:stick",null,
                            null,"minecraft:stick",null),
                    1,true,
                    Map.of("minecraft:cobblestone",3,
                            "minecraft:stick",2));
        }
        return null;
    }

    private String firstPresent(
            ServerPlayerEntity player,String...ids) {
        return firstSufficient(player,1,ids);
    }

    /** 木种选择:数量充足者优先(修"首个在场但不足"缺陷,实测 oak 2<4 而 birch 8)。 */
    private String firstSufficient(
            ServerPlayerEntity player,int need,String...ids) {
        String fallback=null;
        for(String id:ids) {
            int have=countItem(player,id);
            if(have>=need)return id;
            if(fallback==null&&have>0)fallback=id;
        }
        return fallback;
    }

    private BlockPos findNearbyCraftingTable(
            ServerPlayerEntity player) {
        // 半径 3 内已放置的工作台(真实存在才算 3×3 权限)
        BlockPos feet=player.getBlockPos();
        int r=3;
        for(int dx=-r;dx<=r;dx++)
            for(int dy=-r;dy<=r;dy++)
                for(int dz=-r;dz<=r;dz++) {
                    BlockPos p=feet.add(dx,dy,dz);
                    if(player.getServerWorld().getBlockState(p)
                            .isOf(net.minecraft.block.Blocks.CRAFTING_TABLE))
                        return p;
                }
        return null;
    }

    private BodyBackend.Handle startEat(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        only(args,Set.of());
        if(player.getHungerManager().getFoodLevel()>=20)
            throw new BridgeFault(409,"eat_not_hungry");
        // 安全食物优先(排除高危);MC-RCF-1 G3d:传物品 id,
        // 客户端真实定位/调入快捷栏(修"slot>=9 传 0"假映射)
        String safeId=null,harmfulId=null;
        var inventory=player.getInventory();
        for(int i=0;i<inventory.main.size();i++) {
            ItemStack stack=inventory.main.get(i);
            if(stack.isEmpty())continue;
            var food=stack.get(
                    net.minecraft.component.DataComponentTypes.FOOD);
            if(food==null)continue;
            String id=Registries.ITEM.getId(stack.getItem())
                    .toString();
            if(isHarmfulFood(stack.getItem())) {
                if(harmfulId==null)harmfulId=id;
            } else {
                safeId=id;
                break;
            }
        }
        String itemId=safeId!=null?safeId:harmfulId;
        if(itemId==null)
            throw new BridgeFault(409,"eat_no_food_in_inventory");
        int before=countItem(player,itemId);
        int hungerBefore=player.getHungerManager().getFoodLevel();
        if(!transport.sendCommand(
                request.executionId(),"eat",
                JsonOutput.encode(Map.of(
                        "food_item",itemId))))
            throw new BridgeFault(
                    503,"real_client_command_queue_unavailable");
        final String foodId=itemId;
        return ()->eatSnapshot(
                request.executionId(),startedAt,
                foodId,before,hungerBefore);
    }

    private BodyBackend.Snapshot eatSnapshot(
            String executionId,long startedAt,
            String itemId,int before,int hungerBefore) {
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
        int after=countItem(player,itemId);
        int hunger=player.getHungerManager().getFoodLevel();
        // R1-I5/V04:完成=客户端完成回执 AND 本次物品真实减少。
        // 饥饿值受自然饱和/外部效果并发影响,只作记录不作证据;
        // 物品被外部取走(after<before 但客户端未完成)不得冒充本次进食。
        boolean clientDone=remote!=null
                &&"completed".equals(remote.state());
        // R1-I5/V04:客户端回执携带本次真实消费数(client_consumed=N);
        // 服务端核对 after==before-N。外部取走物品(clear/他因)造成的
        // after<before 与 claimed 不符 → 不完成,不冒充本次进食。
        int claimed=0;
        if(clientDone&&remote.reason()!=null) {
            var m=java.util.regex.Pattern
                    .compile("client_consumed=(\\d+)")
                    .matcher(remote.reason());
            if(m.find())claimed=Integer.parseInt(m.group(1));
        }
        if(clientDone&&claimed>0&&after==before-claimed)
            return new BodyBackend.Snapshot(
                    "completed",1D,
                    "server_authoritative_food_consumed:"
                            +itemId+":"+before+"->"+after
                            +":claimed="+claimed
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
        // MC-RCF-1 G3c:精确放置——指定物品+目标格;不再任意 BlockItem 顶替
        only(args,Set.of("x","y","z","item"));
        BlockPos target=new BlockPos(
                integer(args,"x",-29999984,29999984),
                integer(args,"y",player.getServerWorld().getBottomY(),
                        player.getServerWorld().getBottomY()
                                +player.getServerWorld().getHeight()-1),
                integer(args,"z",-29999984,29999984));
        if(!player.getServerWorld().getBlockState(target).isAir())
            throw new BridgeFault(409,"place_target_not_air");
        // R2:身体占据目标格时不在这里提前拒绝——客户端 PlaceAction 的
        // bodyInTarget 分支会先走到真正邻位再放;此处拒绝会把可恢复
        // 站位变成立即失败。服务端仍以最终方块+消耗+见证裁决。
        if(player.getEyePos().distanceTo(
                target.toCenterPos())>5.5D)
            throw new BridgeFault(409,"place_target_too_far");
        String itemId=string(args,"item",120);
        var item=Registries.ITEM.get(
                Identifier.tryParse(itemId));
        if(!(item instanceof net.minecraft.item.BlockItem)
                ||countItem(player,itemId)<=0)
            throw new BridgeFault(
                    409,"place_item_not_in_inventory");
        int before=countItem(player,itemId);
        if(!transport.sendCommand(
                request.executionId(),"place",
                JsonOutput.encode(Map.of(
                        "item",itemId,
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
            var expected=((net.minecraft.item.BlockItem)Registries.ITEM
                    .get(Identifier.tryParse(itemId)))
                    .getBlock().getDefaultState();
            if(state.getBlock()!=expected.getBlock()) {
                transport.sendControl(executionId,"cancel",
                        "place_target_taken_by_other_block");
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "place_target_taken_by_other_block:"
                                +state.getBlock());
            }
            int after=countItem(player,itemId);
            // R1-I4/V03 + R2/R07:块类型正确+库存减少仍可能来自两件无关
            // 事件(他人放同类型块 + 另因减少 Bob 物品)。完成归因必须
            // 绑定服务器侧见证:执行窗口内 Bob 的方块交互命中过能产出
            // 目标格的支撑面(UseBlockCallback 服务端记录)。
            if(after<before) {
                boolean witnessed=RealClientPlacementWitness
                        .attributesPlacementTo(
                                player.getUuidAsString(),target,
                                startedAt-1,server.getTicks());
                if(witnessed)
                    return new BodyBackend.Snapshot(
                            "completed",1D,
                            "server_authoritative_block_placed:"
                                    +itemId+":"+before+"->"+after
                                    +":at="+target.toShortString()
                                    +":consumed=true"
                                    +":interaction_witnessed=true");
                transport.sendControl(executionId,"cancel",
                        "place_unwitnessed_consumption");
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "place_unwitnessed_consumption:"
                                +itemId+":"+before+"->"+after
                                +":at="+target.toShortString());
            }
            var remoteNow=transport.execution(executionId).orElse(null);
            boolean clientDone=remoteNow!=null
                    &&"completed".equals(remoteNow.state());
            if(clientDone) {
                transport.sendControl(executionId,"cancel",
                        "place_external_placement_unattributed");
                return new BodyBackend.Snapshot(
                        "failed",0D,
                        "place_external_placement_unattributed:"
                                +"block present without Bob consumption");
            }
            // 客户端仍在跑:块已出现但消耗未观测——给同步窗口,
            // 下一 snapshot 复核(消耗或客户端终态二选一裁决)
            return new BodyBackend.Snapshot(
                    "running",.9D,
                    "place_block_present_awaiting_consumption_proof");
        }
        if(server.getTicks()-startedAt>20*25) {
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

    private BodyBackend.Handle startMoveItems(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        // R1-I2:count=本次移动量(增量),非目标槽最终量。
        // source_slot(可选,0..35 玩家主包索引)绑定指定源堆叠(同 ID
        // 异组件选择,I03);protect_slot(可选)任务最低保留量槽,双侧执行。
        only(args,Set.of(
                "item","count","hotbar","source_slot","protect_slot"));
        String itemId=string(args,"item",120);
        int count=integer(args,"count",-1,64);
        if(count==0)
            throw new BridgeFault(400,"move_items_count_zero_noop");
        int hotbar=integer(args,"hotbar",0,8);
        int sourceSlot=args.has("source_slot")
                ?integer(args,"source_slot",0,35):-1;
        int protectSlot=args.has("protect_slot")
                ?integer(args,"protect_slot",0,35):-1;
        if(sourceSlot==hotbar
                ||(sourceSlot>=0&&sourceSlot==protectSlot))
            throw new BridgeFault(
                    400,"move_items_source_target_conflict");
        int have=countItem(player,itemId);
        if(have<=0)
            throw new BridgeFault(
                    409,"move_items_not_in_inventory");
        int moved=count;
        if(sourceSlot>=0) {
            ItemStack src=player.getInventory().main.get(sourceSlot);
            if(src.isEmpty()
                    ||!Registries.ITEM.getId(src.getItem())
                            .toString().equals(itemId))
                throw new BridgeFault(
                        409,"move_items_source_slot_mismatch");
            if(moved<0||moved>src.getCount())
                moved=moved<0?src.getCount():moved;
        } else if(count>have)
            throw new BridgeFault(
                    409,"move_items_insufficient:"
                            +"have="+have+":want="+count);
        // 基线:dest 槽现有量(增量判定)与源槽堆叠指纹(组件身份)
        int destIndex=hotbar;
        int destBaseline=player.getInventory()
                .main.get(destIndex).getCount();
        String destBaselineId=Registries.ITEM.getId(
                player.getInventory().main.get(destIndex)
                        .getItem()).toString();
        if(!destBaselineId.equals(itemId)&&destBaseline>0)
            throw new BridgeFault(
                    409,"move_items_dest_occupied_other_item");
        if(protectSlot>=0) {
            ItemStack prot=player.getInventory()
                    .main.get(protectSlot);
            if(!prot.isEmpty()
                    &&Registries.ITEM.getId(prot.getItem())
                            .toString().equals(itemId)) {
                int movable=have-prot.getCount();
                if(count>movable)
                    throw new BridgeFault(
                            409,"move_items_protect_reservation:"
                                    +"protect="+prot.getCount()
                                    +":movable="+movable
                                    +":want="+count);
            }
        }
        final int finalMoved=moved;
        // R2/R07:源堆叠身份基线——指定源槽时绑定具体堆叠(数量+组件
        // 指纹),完成时核对"那一把"真实移动且组件不变(I03)。
        final int srcBefore=sourceSlot>=0
                ?player.getInventory().main.get(sourceSlot).getCount():-1;
        final String srcComponentKey=sourceSlot>=0
                ?componentKey(player.getInventory().main.get(sourceSlot)):"";
        Map<String,Object> command=new LinkedHashMap<>();
        command.put("item",itemId);
        command.put("count",finalMoved);
        command.put("hotbar",hotbar);
        // R1-I2:客户端增量语义锚点——目标=destBaseline+count(净增),
        // 不是"目标槽最终=count"(实测该语义在目标已有内容时漏放)。
        command.put("dest_baseline",destBaseline);
        if(sourceSlot>=0)command.put("source_slot",sourceSlot);
        if(protectSlot>=0)command.put("protect_slot",protectSlot);
        if(!transport.sendCommand(
                request.executionId(),"move_items",
                JsonOutput.encode(command)))
            throw new BridgeFault(
                    503,"real_client_command_queue_unavailable");
        return ()->moveItemsSnapshot(
                request.executionId(),startedAt,
                itemId,finalMoved,hotbar,destBaseline,
                sourceSlot,srcBefore,srcComponentKey);
    }

    /** 组件指纹:item id + 全部数据组件的稳定串(身份比较,非数值)。 */
    private static String componentKey(ItemStack stack) {
        if(stack.isEmpty())return "";
        return Registries.ITEM.getId(stack.getItem())+"/"
                +stack.getComponents().toString();
    }

    private BodyBackend.Snapshot moveItemsSnapshot(
            String executionId,long startedAt,
            String itemId,int count,int hotbar,int destBaseline,
            int sourceSlot,int srcBefore,String srcComponentKey) {
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
        if(remote!=null&&"completed".equals(remote.state())) {
            ItemStack stack=player.getInventory().main.get(hotbar);
            int gained=stack.getCount()-destBaseline;
            // R1-I2/V02:完成=目标槽净增本次请求量(不是"最终≥count"
            // ——目标原有 10 件请求移 7 未动也过是审查点名缺陷)。
            boolean ok=stack.getItem()==Registries.ITEM.get(
                    Identifier.tryParse(itemId))
                    &&gained>=count;
            String extra="";
            if(ok&&sourceSlot>=0) {
                // R2/R07:源净减=本次请求量;目的组件指纹=源堆叠指纹
                //(指定那把不同组件工具真实移动,另一把留在原位)。
                ItemStack srcNow=player.getInventory().main.get(sourceSlot);
                int srcNowCount=srcNow.isEmpty()?0:srcNow.getCount();
                // 源净减≥本次请求量(余量可能整取后回放到其他主包空位,
                // 源槽清空合法);外部加料不能伪充移动。
                boolean sourceOk=srcBefore-srcNowCount>=count;
                boolean componentOk=componentKey(stack)
                        .equals(srcComponentKey);
                if(!sourceOk||!componentOk)
                    return new BodyBackend.Snapshot(
                            "failed",1D,
                            "move_items_identity_unproven:"
                                    +"src="+srcBefore+"->"+srcNowCount
                                    +":component_match="+componentOk);
                extra=":src="+sourceSlot+":"+srcBefore+"->"+srcNowCount
                        +":component_verified=true";
            }
            return ok
                    ?new BodyBackend.Snapshot(
                            "completed",1D,
                            "server_authoritative_items_moved:"
                                    +itemId+":hotbar="+hotbar
                                    +":baseline="+destBaseline
                                    +":after="+stack.getCount()
                                    +":gained="+gained+extra)
                    :new BodyBackend.Snapshot(
                            "failed",1D,
                            "move_items_increment_unproven:"
                                    +"gained="+gained+"/"+count
                                    +"(baseline="+destBaseline+")");
        }
        if(server.getTicks()-startedAt>20*20) {
            transport.sendControl(executionId,"cancel",
                    "real_client_execution_timeout");
            return new BodyBackend.Snapshot(
                    "failed",0D,"real_client_execution_timeout");
        }
        return new BodyBackend.Snapshot(
                "running",
                remote==null?0D:Math.min(.9D,remote.progress()),
                remote==null?"awaiting_real_client_ack":remote.reason());
    }

    private BodyBackend.Handle startSmelt(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        // R1-I7: smelt 未验收——能力与分发入口全部拒绝(不是"注册但可用")。
        throw new BridgeFault(
                403,"smelt_not_available_unvalidated");
    }


    private static boolean isHarmfulFood(Item item) {
        return item==net.minecraft.item.Items.CHICKEN
                ||item==net.minecraft.item.Items.ROTTEN_FLESH
                ||item==net.minecraft.item.Items.PUFFERFISH
                ||item==net.minecraft.item.Items.SPIDER_EYE
                ||item==net.minecraft.item.Items.POISONOUS_POTATO;
    }

    private BodyBackend.Handle startContainerTransfer(
            Request request,JsonObject args,
            ServerPlayerEntity player,long startedAt) {
        // R1-I4:普通箱子/木桶双向真实事务(原生 Screen 点击,
        // 与 deposit 的 Tom's 通道并存且互不替代)。
        only(args,Set.of("x","y","z","item","count",
                "direction","source_slot"));
        BlockPos target=new BlockPos(
                integer(args,"x",-29999984,29999984),
                integer(args,"y",player.getServerWorld().getBottomY(),
                        player.getServerWorld().getBottomY()
                                +player.getServerWorld().getHeight()-1),
                integer(args,"z",-29999984,29999984));
        var state=player.getServerWorld().getBlockState(target);
        if(!(state.getBlock()
                instanceof net.minecraft.block.InventoryProvider
                ||state.hasBlockEntity()
                &&player.getServerWorld()
                        .getBlockEntity(target)
                        instanceof net.minecraft.inventory.Inventory))
            throw new BridgeFault(
                    409,"container_not_an_inventory");
        var be=player.getServerWorld().getBlockEntity(target);
        if(!(be instanceof net.minecraft.inventory.Inventory inv))
            throw new BridgeFault(
                    409,"container_not_an_inventory");
        if(player.getEyePos().distanceTo(
                target.toCenterPos())>5.5D)
            throw new BridgeFault(409,"container_too_far");
        String itemId=string(args,"item",120);
        int count=integer(args,"count",-1,64);
        if(count==0)
            throw new BridgeFault(400,"container_count_zero_noop");
        boolean withdraw="withdraw".equals(
                string(args,"direction",16));
        int sourceSlot=args.has("source_slot")
                ?integer(args,"source_slot",0,35):-1;
        int invHave=countInventory(inv);
        int playerHave=countItem(player,itemId);
        int containerHave=countInInventory(inv,itemId);
        if(!withdraw&&playerHave<=0)
            throw new BridgeFault(
                    409,"container_deposit_source_missing");
        if(withdraw&&containerHave<=0)
            throw new BridgeFault(
                    409,"container_withdraw_source_missing");
        Map<String,Object> command=new LinkedHashMap<>();
        command.put("x",target.getX());
        command.put("y",target.getY());
        command.put("z",target.getZ());
        command.put("item",itemId);
        command.put("count",count);
        command.put("direction",withdraw?"withdraw":"deposit");
        if(sourceSlot>=0)command.put("source_slot",sourceSlot);
        if(!transport.sendCommand(
                request.executionId(),"container_transfer",
                JsonOutput.encode(command)))
            throw new BridgeFault(
                    503,"real_client_command_queue_unavailable");
        final int playerBaseline=playerHave;
        final int containerBaseline=containerHave;
        final int totalSlots=inv.size();
        return ()->containerSnapshot(
                request.executionId(),startedAt,
                target,itemId,count,withdraw,
                playerBaseline,containerBaseline);
    }

    private BodyBackend.Snapshot containerSnapshot(
            String executionId,long startedAt,
            BlockPos target,String itemId,int count,boolean withdraw,
            int playerBaseline,int containerBaseline) {
        onThread();
        ServerPlayerEntity player=body.get();
        if(player==null)
            return new BodyBackend.Snapshot(
                    "outcome_unknown",0D,"real_client_body_unavailable");
        var be=player.getServerWorld().getBlockEntity(target);
        if(!(be instanceof net.minecraft.inventory.Inventory inv)) {
            transport.sendControl(executionId,"cancel",
                    "container_target_changed");
            return new BodyBackend.Snapshot(
                    "failed",0D,"container_target_changed");
        }
        var remote=transport.execution(executionId).orElse(null);
        if(remote!=null&&Set.of("failed","cancelled","outcome_unknown")
                .contains(remote.state()))
            return new BodyBackend.Snapshot(
                    remote.state(),remote.progress(),remote.reason());
        if(remote!=null
                &&"completed".equals(remote.state())) {
            int playerNow=countItem(player,itemId);
            int containerNow=countInInventory(inv,itemId);
            int playerDelta=playerNow-playerBaseline;
            int containerDelta=containerNow-containerBaseline;
            // 双向守恒:withdraw=玩家+X 容器-X;deposit 反之。
            // 完成=两侧变化等量反向 且 净转移量达到请求。
            int net=withdraw?playerDelta:containerDelta;
            boolean ok=Math.abs(playerDelta)
                    ==Math.abs(containerDelta)
                    &&net>=Math.abs(count);
            if(ok)
                return new BodyBackend.Snapshot(
                        "completed",1D,
                        "server_authoritative_container_transfer:"
                                +itemId+":"+(withdraw?"withdraw":"deposit")
                                +":player:"+playerBaseline
                                +"->"+playerNow
                                +":container:"+containerBaseline
                                +"->"+containerNow);
            return new BodyBackend.Snapshot(
                    "failed",1D,
                    "container_transfer_unproven:"
                            +"playerΔ"+playerDelta
                            +"containerΔ"+containerDelta
                            +"want"+count);
        }
        if(server.getTicks()-startedAt>20*45) {
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

    private static int countInInventory(
            Inventory inventory,String itemId) {
        int count=0;
        for(int slot=0;slot<inventory.size();slot++) {
            ItemStack stack=inventory.getStack(slot);
            if(!stack.isEmpty()
                    &&Registries.ITEM.getId(stack.getItem())
                            .toString().equals(itemId))
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
