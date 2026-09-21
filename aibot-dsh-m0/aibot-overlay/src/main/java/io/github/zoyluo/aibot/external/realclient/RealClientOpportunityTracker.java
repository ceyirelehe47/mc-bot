package io.github.zoyluo.aibot.external.realclient;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.external.BridgeJournal;
import io.github.zoyluo.aibot.external.JsonOutput;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.mining.OreScan;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable incarnation tracker for opportunities actually pointed at by Bob's real client. */
public final class RealClientOpportunityTracker {
    private static final int MAX_ACTIVE=1024;
    /** Coherent frame freshness: a sample older than this is never validated. */
    public static final long FRAME_FRESH_MS=2000L;
    /** Sensor position is a coherence hint only: it must stay near the authoritative body. */
    public static final double POSITION_TOLERANCE=2.0D;
    /** Server-side ray reconstruction range; client crosshair reach is strictly smaller. */
    public static final double VALIDATION_RANGE=7D;

    public record Opportunity(
            String id,String worldId,String dimension,BlockPos pos,String blockId,
            BlockPos seenFrom,String expectedItem,String requiredTool,long lastSeenGameTime) {}

    private final BridgeJournal journal;
    private final LinkedHashMap<String,Opportunity> active=new LinkedHashMap<>();
    private String lastProcessedGameSession="";
    private long lastProcessedFrameSeq=-1L;

    public RealClientOpportunityTracker(BridgeJournal journal) {
        this.journal=journal;
        replay();
    }

    public synchronized Optional<Opportunity> observe(
            ServerPlayerEntity player,RealClientServerTransport.SensorSnapshot sensor) {
        if(sensor==null) {
            diagReject(player,sensor,null,null,"sensor_null");
            return Optional.empty();
        }
        // MC-RCF-1-R2 R02:全向感知只发现候选,持久机会出生只走本方法
        // 末端的准入核心(RealClientOpportunityAdmission)。旧 sweepRegister
        // 旁路(校验前按接收新鲜度直接注册全向 raycast 结果)已删除——
        // 它让未被当前准星指向的方块进入持久机会池。合法链路是:
        // 导航到可站可及位置 → 正常转向 → 该姿态之后的新客户端帧 →
        // 服务端重建 ray 精确命中准星格 → 出生/刷新。
        BlockPos pos=new BlockPos(sensor.crosshairX(),sensor.crosshairY(),sensor.crosshairZ());
        Vec3d framePos=new Vec3d(sensor.x(),sensor.y(),sensor.z());
        double positionDrift=player.getPos().distanceTo(framePos);
        double squaredDistance=player.squaredDistanceTo(Vec3d.ofCenter(pos));
        long frameAgeMs=System.currentTimeMillis()-sensor.receivedAtMs();
        boolean rayAtCrosshair=false;
        String actualId="";
        boolean eligible=false;
        if(sensor.crosshairPresent()
                &&sensor.gameSession()!=null && !sensor.gameSession().isBlank()
                &&frameAgeMs<=FRAME_FRESH_MS
                &&positionDrift<=POSITION_TOLERANCE
                &&squaredDistance<=49D) {
            // Reconstruct the world ray from the AUTHORITATIVE server eye using
            // the LOOK DIRECTION of the very same client frame. Never compare
            // against a later server pose snapshot.
            Vec3d eye=player.getEyePos();
            Vec3d direction=Vec3d.fromPolar(sensor.pitch(),sensor.yaw());
            HitResult serverRay=player.getServerWorld().raycast(new RaycastContext(
                    eye,eye.add(direction.multiply(VALIDATION_RANGE)),
                    RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE,player));
            if(serverRay instanceof BlockHitResult blockHit
                    &&blockHit.getBlockPos().equals(pos)) {
                rayAtCrosshair=true;
                BlockState state=player.getServerWorld().getBlockState(pos);
                actualId=Registries.BLOCK.getId(state.getBlock()).toString();
                eligible=OreScan.isOreBlock(state.getBlock())
                        ||state.isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                        ||isOrdinaryDiggable(state,player,pos);
            }
        }
        RealClientOpportunityAdmission.Decision decision=
                RealClientOpportunityAdmission.evaluate(
                        new RealClientOpportunityAdmission.Facts(
                                sensor.crosshairPresent(),
                                sensor.gameSession(),
                                lastProcessedGameSession,
                                lastProcessedFrameSeq,
                                sensor.frameSeq(),
                                frameAgeMs,
                                FRAME_FRESH_MS,
                                positionDrift,
                                POSITION_TOLERANCE,
                                squaredDistance,
                                49D,
                                rayAtCrosshair,
                                sensor.crosshairBlock()!=null
                                        &&sensor.crosshairBlock().equals(actualId),
                                eligible));
        if(!decision.admit()) {
            if(!decision.duplicateFrame())
                diagReject(player,sensor,pos,null,decision.refusal());
            return Optional.empty();
        }
        lastProcessedGameSession=sensor.gameSession();
        lastProcessedFrameSeq=sensor.frameSeq();
        Opportunity existing=findAt(player.getServerWorld().getRegistryKey()
                .getValue().toString(),pos,actualId).orElse(null);
        Opportunity primary;
        if(existing!=null) {
            Opportunity refreshed=new Opportunity(
                    existing.id(),existing.worldId(),existing.dimension(),existing.pos(),
                    existing.blockId(),player.getBlockPos(),existing.expectedItem(),
                    existing.requiredTool(),player.getServerWorld().getTime());
            active.put(key(existing.dimension(),existing.id()),refreshed);
            primary=refreshed;
        } else {
            primary=register(player,
                    player.getServerWorld().getRegistryKey().getValue().toString(),
                    pos,actualId,
                    player.getServerWorld().getBlockState(pos),sensor);
        }
        return Optional.of(primary);
    }

    private Opportunity register(
            ServerPlayerEntity player,String dimension,
            BlockPos pos,String actualId,BlockState state,
            RealClientServerTransport.SensorSnapshot sensor) {
        // R2:有界缓存——容量满时按插入序淘汰最旧条目并写 durable 回执,
        // 绝不静默丢新 birth,也不无限扩容 MAX_ACTIVE。
        if(active.size()>=MAX_ACTIVE)
            evictOldest("capacity_evict");
        String id=newId(dimension,pos,actualId);
        Opportunity opportunity=new Opportunity(
                id,SemanticWorldRegistry.worldId(),dimension,pos,actualId,player.getBlockPos(),
                expectedItem(state.getBlock()),requiredTool(state.getBlock()),
                player.getServerWorld().getTime());
        appendBirth(opportunity);
        active.put(key(dimension,id),opportunity);
        return opportunity;
    }


    public synchronized Optional<Opportunity> opportunity(
            ServerPlayerEntity player,String id) {
        String dimension=player.getServerWorld().getRegistryKey().getValue().toString();
        return Optional.ofNullable(active.get(key(dimension,id)));
    }

    public synchronized List<Opportunity> opportunities(ServerPlayerEntity player) {
        String dimension=player.getServerWorld().getRegistryKey().getValue().toString();
        // MC-RCF-1-R2 R02:惰性剔除必须带 durable stale 回执——journal 重放
        // 的旧条目在方块已变后退出列表,同时把失效事实持久化,崩溃前后
        // 结果一致;不得静默 removeIf 丢状态(审查点名)。
        List<Opportunity> changed=new ArrayList<>();
        for(Opportunity o:active.values()) {
            if(!dimension.equals(o.dimension()))
                continue;
            BlockState s=player.getServerWorld().getBlockState(o.pos());
            String now=Registries.BLOCK.getId(s.getBlock()).toString();
            if(!now.equals(o.blockId()))
                changed.add(o);
        }
        for(Opportunity o:changed) {
            io.github.zoyluo.aibot.AIBotMod.LOGGER.info(
                    "AIBot opportunity evict-lazy id={} pos={} was={}",
                    o.id(),o.pos(),o.blockId());
            active.remove(key(o.dimension(),o.id()));
            markStale("",o,"block_changed_evict");
        }
        return active.values().stream()
                .filter(o->dimension.equals(o.dimension()))
                .sorted(Comparator.comparing(Opportunity::id))
                .toList();
    }

    public synchronized boolean markConsumed(
            String executionId,Opportunity opportunity,ServerPlayerEntity player,int baseline,int current) {
        Map<String,String> fields=resolutionFields(
                "resource_opportunity_consumed",executionId,opportunity);
        fields.put("payload",JsonOutput.encode(Map.of(
                "resolution","inventory_gain_proven",
                "expected_item",opportunity.expectedItem(),
                "baseline",baseline,"current",current,
                "x",opportunity.pos().getX(),"y",opportunity.pos().getY(),
                "z",opportunity.pos().getZ())));
        journal.append(fields);
        active.remove(key(opportunity.dimension(),opportunity.id()));
        return true;
    }

    public synchronized boolean markStale(
            String executionId,Opportunity opportunity,String reason) {
        Map<String,String> fields=resolutionFields(
                "resource_opportunity_stale",executionId,opportunity);
        fields.put("payload",JsonOutput.encode(Map.of(
                "reason",reason,
                "x",opportunity.pos().getX(),"y",opportunity.pos().getY(),
                "z",opportunity.pos().getZ())));
        journal.append(fields);
        active.remove(key(opportunity.dimension(),opportunity.id()));
        return true;
    }

    public synchronized JsonObject materialize(Opportunity opportunity,long gameTime) {
        JsonObject result=new JsonObject();
        result.addProperty("object_id",opportunity.id());
        result.addProperty("block",opportunity.blockId());
        result.addProperty("expected_item",opportunity.expectedItem());
        result.addProperty("required_tool",opportunity.requiredTool());
        result.addProperty("x",opportunity.pos().getX());
        result.addProperty("y",opportunity.pos().getY());
        result.addProperty("z",opportunity.pos().getZ());
        long age=Math.max(0L,gameTime-opportunity.lastSeenGameTime());
        result.addProperty("age_ticks",age);
        result.addProperty("freshness",age<=40?"LIVE":age<=2400?"RECENT":"STALE");
        result.addProperty("sensor","client_crosshair_server_validated");
        return result;
    }

    private void replay() {
        for(BridgeJournal.Frame frame:journal.replay()) {
            Map<String,String> fields=frame.fields();
            String kind=fields.get("kind");
            if("real_client_opportunity_birth".equals(kind)) {
                Opportunity birth=parseBirth(fields);
                if(!SemanticWorldRegistry.worldId().equals(birth.worldId()))
                    throw new IllegalStateException("real_client_birth_world_scope_mismatch");
                String key=key(birth.dimension(),birth.id());
                Opportunity prior=active.get(key);
                if(prior!=null && !prior.equals(birth))
                    throw new IllegalStateException("conflicting_real_client_opportunity_birth");
                if(prior==null && active.size()>=MAX_ACTIVE)
                    evictOldest("replay_capacity_evict");
                if(prior==null)
                    active.put(key,birth);
            } else if("resource_opportunity_consumed".equals(kind)
                    || "resource_opportunity_stale".equals(kind)) {
                String dimension=fields.get("dimension");
                String id=fields.get("opportunity_id");
                if(dimension!=null && id!=null)active.remove(key(dimension,id));
            }
        }
    }

    /** R2:容量淘汰唯一出口——按插入序移除最旧条目并写 durable 回执。
     * journal 完整保留 birth 历史;重放按同一规则推导出同一内存态。*/
    private void evictOldest(String reason) {
        if(active.isEmpty())return;
        Iterator<Opportunity> oldest=active.values().iterator();
        Opportunity evicted=oldest.next();
        oldest.remove();
        io.github.zoyluo.aibot.AIBotMod.LOGGER.warn(
                "AIBot opportunity capacity-evict id={} reason={}",
                evicted.id(),reason);
        markStale("",evicted,reason);
    }
    private void appendBirth(Opportunity opportunity) {
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("kind","real_client_opportunity_birth");
        fields.put("execution_id","");
        fields.put("world_id",opportunity.worldId());
        fields.put("dimension",opportunity.dimension());
        fields.put("opportunity_id",opportunity.id());
        fields.put("block_id",opportunity.blockId());
        fields.put("x",Integer.toString(opportunity.pos().getX()));
        fields.put("y",Integer.toString(opportunity.pos().getY()));
        fields.put("z",Integer.toString(opportunity.pos().getZ()));
        fields.put("seen_x",Integer.toString(opportunity.seenFrom().getX()));
        fields.put("seen_y",Integer.toString(opportunity.seenFrom().getY()));
        fields.put("seen_z",Integer.toString(opportunity.seenFrom().getZ()));
        fields.put("expected_item",opportunity.expectedItem());
        fields.put("required_tool",opportunity.requiredTool());
        fields.put("last_seen_game_time",Long.toString(opportunity.lastSeenGameTime()));
        fields.put("payload",JsonOutput.encode(Map.of(
                "sensor","client_crosshair_server_validated")));
        journal.append(fields);
        Map<String,String> actionable=new LinkedHashMap<>();
        actionable.put("kind","resource_opportunity_actionable");
        actionable.put("execution_id","");
        actionable.put("opportunity_id",opportunity.id());
        actionable.put("world_id",opportunity.worldId());
        actionable.put("dimension",opportunity.dimension());
        actionable.put("payload",JsonOutput.encode(Map.of(
                "opportunity_id",opportunity.id(),
                "block",opportunity.blockId(),
                "x",opportunity.pos().getX(),
                "y",opportunity.pos().getY(),
                "z",opportunity.pos().getZ(),
                "sensor","client_crosshair_server_validated")));
        journal.append(actionable);
    }

    private static Map<String,String> resolutionFields(
            String kind,String executionId,Opportunity opportunity) {
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("kind",kind);
        fields.put("execution_id",executionId==null?"":executionId);
        fields.put("opportunity_id",opportunity.id());
        fields.put("world_id",opportunity.worldId());
        fields.put("dimension",opportunity.dimension());
        return fields;
    }

    private static String lastDiagReason="";
    private static long lastDiagMs;

    /** LIVE 诊断:传感器验证失败时低频汇报中间值,定位 real-client 传感器链路。 */
    private static void diagReject(ServerPlayerEntity player,
            RealClientServerTransport.SensorSnapshot sensor,BlockPos expected,
            HitResult serverRay,String reason) {
        long now=System.currentTimeMillis();
        if(now-lastDiagMs<5000L || reason.equals(lastDiagReason) && now-lastDiagMs<30000L)return;
        lastDiagMs=now; lastDiagReason=reason;
        io.github.zoyluo.aibot.AIBotMod.LOGGER.info(
                "AIBot real-client sensor diag reject={} sensorPresent={} expected={} ray={} "
                        +"serverYaw={} serverPitch={} sensorYaw={} sensorPitch={} sensorCross={} "
                        +"frameAgeMs={} frameSeq={} gameSession={} dist={}",
                reason,sensor==null?"no-sensor":sensor.crosshairPresent(),
                expected,
                serverRay==null?"n/a":serverRay instanceof BlockHitResult b?b.getBlockPos():serverRay.getType(),
                player.getYaw(),player.getPitch(),
                sensor==null?"n/a":sensor.yaw(),sensor==null?"n/a":sensor.pitch(),
                sensor==null?"n/a":sensor.crosshairX()+","+sensor.crosshairY()+","+sensor.crosshairZ(),
                sensor==null?"n/a":System.currentTimeMillis()-sensor.receivedAtMs(),
                sensor==null?"n/a":sensor.frameSeq(),
                sensor==null?"n/a":sensor.gameSession(),
                expected==null?"n/a":String.format("%.2f",Math.sqrt(player.squaredDistanceTo(Vec3d.ofCenter(expected)))));
    }

    private Optional<Opportunity> findAt(String dimension,BlockPos pos,String blockId) {
        return active.values().stream().filter(o->o.dimension().equals(dimension)
                && o.pos().equals(pos) && o.blockId().equals(blockId)).findFirst();
    }

    private static Opportunity parseBirth(Map<String,String> fields) {
        return new Opportunity(
                required(fields,"opportunity_id"),required(fields,"world_id"),
                required(fields,"dimension"),
                new BlockPos(integer(fields,"x"),integer(fields,"y"),integer(fields,"z")),
                required(fields,"block_id"),
                new BlockPos(integer(fields,"seen_x"),integer(fields,"seen_y"),integer(fields,"seen_z")),
                required(fields,"expected_item"),required(fields,"required_tool"),
                Long.parseLong(required(fields,"last_seen_game_time")));
    }

    private static String newId(String dimension,BlockPos pos,String blockId) {
        String material=SemanticWorldRegistry.worldId()+"\n"+dimension+"\n"+pos.toShortString()+"\n"+blockId;
        String location=UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-","").substring(0,12);
        String incarnation=UUID.randomUUID().toString().replace("-","").substring(0,16);
        return "rcore_"+location+"_"+incarnation;
    }

    /** 普通可挖方块(泥土/沙/石头等):挖洞过夜与地形施工的基础。
     * 石头等需工具方块同样放行,工具检查交给执行驱动的热键栏选择
     * (有镐则挖,无镐 409 required_tool_missing)。 */
    private static boolean isOrdinaryDiggable(
            BlockState state,ServerPlayerEntity player,BlockPos pos) {
        if(state.isAir())return false;
        // 超常见无价值方块不进机会池(会挤爆视图 40 槽,实测石头被泥土顶掉)
        if(state.isOf(net.minecraft.block.Blocks.GRASS_BLOCK)
                ||state.isOf(net.minecraft.block.Blocks.SHORT_GRASS)
                ||state.isOf(net.minecraft.block.Blocks.DIRT_PATH)
                ||state.isOf(net.minecraft.block.Blocks.SAND))
            return false;
        return state.getHardness(player.getServerWorld(),pos)>=0F;
    }

    private static String expectedItem(Block block) {
        if(block==net.minecraft.block.Blocks.IRON_ORE || block==net.minecraft.block.Blocks.DEEPSLATE_IRON_ORE)
            return Registries.ITEM.getId(Items.RAW_IRON).toString();
        if(block==net.minecraft.block.Blocks.GOLD_ORE || block==net.minecraft.block.Blocks.DEEPSLATE_GOLD_ORE)
            return Registries.ITEM.getId(Items.RAW_GOLD).toString();
        if(block==net.minecraft.block.Blocks.COPPER_ORE || block==net.minecraft.block.Blocks.DEEPSLATE_COPPER_ORE)
            return Registries.ITEM.getId(Items.RAW_COPPER).toString();
        if(block==net.minecraft.block.Blocks.DIAMOND_ORE || block==net.minecraft.block.Blocks.DEEPSLATE_DIAMOND_ORE)
            return Registries.ITEM.getId(Items.DIAMOND).toString();
        if(block==net.minecraft.block.Blocks.EMERALD_ORE || block==net.minecraft.block.Blocks.DEEPSLATE_EMERALD_ORE)
            return Registries.ITEM.getId(Items.EMERALD).toString();
        if(block==net.minecraft.block.Blocks.COAL_ORE || block==net.minecraft.block.Blocks.DEEPSLATE_COAL_ORE)
            return Registries.ITEM.getId(Items.COAL).toString();
        if(block==net.minecraft.block.Blocks.LAPIS_ORE || block==net.minecraft.block.Blocks.DEEPSLATE_LAPIS_ORE)
            return Registries.ITEM.getId(Items.LAPIS_LAZULI).toString();
        if(block==net.minecraft.block.Blocks.REDSTONE_ORE || block==net.minecraft.block.Blocks.DEEPSLATE_REDSTONE_ORE)
            return Registries.ITEM.getId(Items.REDSTONE).toString();
        if(block==net.minecraft.block.Blocks.NETHER_QUARTZ_ORE)
            return Registries.ITEM.getId(Items.QUARTZ).toString();
        if(block==net.minecraft.block.Blocks.NETHER_GOLD_ORE)
            return Registries.ITEM.getId(Items.GOLD_NUGGET).toString();
        // MC-RCF-1 G4: 挖掘掉落与方块本体不同——石类必须按真实掉落计,
        // 否则 mine 终验 countItem(stone) 永远为 0(实测)。
        if(block==net.minecraft.block.Blocks.STONE)
            return "minecraft:cobblestone";
        if(block==net.minecraft.block.Blocks.DEEPSLATE)
            return "minecraft:cobbled_deepslate";
        if(block==net.minecraft.block.Blocks.GRANITE
                ||block==net.minecraft.block.Blocks.DIORITE
                ||block==net.minecraft.block.Blocks.ANDESITE
                ||block==net.minecraft.block.Blocks.TUFF
                ||block==net.minecraft.block.Blocks.CALCITE)
            return Registries.ITEM.getId(block.asItem()).toString();
        Item item=block.asItem();
        if(item==Items.AIR)return "minecraft:air";
        return Registries.ITEM.getId(item).toString();
    }

    private static String requiredTool(Block block) {
        String id=Registries.BLOCK.getId(block).toString();
        if(id.contains("diamond") || id.contains("emerald") || id.contains("gold")
                || id.contains("redstone"))return "minecraft:iron_pickaxe";
        return "minecraft:stone_pickaxe";
    }

    private static String key(String dimension,String id) {
        return dimension+"\u0000"+id;
    }

    private static String required(Map<String,String> fields,String key) {
        String value=fields.get(key);
        if(value==null || value.isBlank())
            throw new IllegalStateException("real_client_birth_missing_"+key);
        return value;
    }

    private static int integer(Map<String,String> fields,String key) {
        return Integer.parseInt(required(fields,key));
    }
}
