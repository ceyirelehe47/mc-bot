package io.github.zoyluo.aibot.external.realclient;

import io.github.zoyluo.aibot.external.cognition.CanonicalJson;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-authoritative 360-degree semantic awareness for the normal-player Real Client body.
 *
 * <p>This is perception only. It never creates an actionable resource opportunity and never starts
 * a physical mutation. Mining, attacking and GUI interaction still require the existing directional
 * client crosshair / Screen authority path.</p>
 */
public final class RealClientOmnidirectionalPerception {
    public static final int ENTITY_VIEW_LIMIT=20;
    public static final int BLOCK_VIEW_LIMIT=16;
    public static final int ENTITY_LOCAL_LIMIT=48;
    public static final int BLOCK_LOCAL_LIMIT=48;
    public static final int ENTITY_MEMORY_CAPACITY=128;
    public static final int BLOCK_MEMORY_CAPACITY=128;
    public static final int ENTITY_CANDIDATE_LIMIT=96;
    public static final int BLOCK_CANDIDATE_LIMIT=128;

    private static final Set<String> HAZARD_BLOCKS=Set.of(
            "minecraft:lava","minecraft:fire","minecraft:soul_fire",
            "minecraft:cactus","minecraft:sweet_berry_bush",
            "minecraft:powder_snow","minecraft:magma_block");
    private static final Set<String> INTERACTABLE_BLOCKS=Set.of(
            "minecraft:crafting_table","minecraft:anvil",
            "minecraft:chipped_anvil","minecraft:damaged_anvil",
            "minecraft:enchanting_table","minecraft:brewing_stand",
            "minecraft:stonecutter","minecraft:loom",
            "minecraft:smithing_table","minecraft:grindstone",
            "minecraft:cartography_table","minecraft:fletching_table",
            "minecraft:jukebox","minecraft:lectern","minecraft:lever",
            "minecraft:respawn_anchor");

    public enum Mode {
        OMNI_SEMANTIC("omni_semantic",360),
        STRICT_PLAYER_FOV("strict_player_fov",110);

        private final String wireName;
        private final int coverageDegrees;

        Mode(String wireName,int coverageDegrees) {
            this.wireName=wireName;
            this.coverageDegrees=coverageDegrees;
        }

        public String wireName() { return wireName; }
        public int coverageDegrees() { return coverageDegrees; }

        public static Mode parse(String raw) {
            String normalized=raw==null?"":raw.trim().toLowerCase(Locale.ROOT);
            return switch(normalized) {
                case "omni_semantic","omni","360" -> OMNI_SEMANTIC;
                case "strict_player_fov","strict","fov" -> STRICT_PLAYER_FOV;
                default -> throw new IllegalArgumentException(
                        "AIBOT_REAL_CLIENT_PERCEPTION_MODE_invalid");
            };
        }
    }

    public record Config(
            Mode mode,int entityRadius,int blockRadius,int memoryTicks,
            int entityScanTicks,int blockScanTicks) {
        public Config {
            if(mode==null)throw new IllegalArgumentException("perception_mode_required");
            if(entityRadius<4 || entityRadius>48)
                throw new IllegalArgumentException("perception_entity_radius_out_of_range_4_48");
            if(blockRadius<2 || blockRadius>32)
                throw new IllegalArgumentException("perception_block_radius_out_of_range_2_32");
            if(memoryTicks<20 || memoryTicks>1200)
                throw new IllegalArgumentException("perception_memory_ticks_out_of_range_20_1200");
            if(entityScanTicks<1 || entityScanTicks>200)
                throw new IllegalArgumentException("perception_entity_scan_ticks_out_of_range_1_200");
            if(blockScanTicks<1 || blockScanTicks>200)
                throw new IllegalArgumentException("perception_block_scan_ticks_out_of_range_1_200");
        }

        public static Config fromEnvironment() {
            return new Config(
                    Mode.parse(System.getenv().getOrDefault(
                            "AIBOT_REAL_CLIENT_PERCEPTION_MODE","omni_semantic")),
                    integerEnv("AIBOT_REAL_CLIENT_PERCEPTION_RADIUS",16),
                    integerEnv("AIBOT_REAL_CLIENT_PERCEPTION_BLOCK_RADIUS",8),
                    integerEnv("AIBOT_REAL_CLIENT_PERCEPTION_MEMORY_TICKS",200),
                    10,20);
        }

        private static int integerEnv(String name,int fallback) {
            String raw=System.getenv(name);
            if(raw==null || raw.isBlank())return fallback;
            try {
                return Integer.parseInt(raw.trim());
            } catch(NumberFormatException invalid) {
                throw new IllegalArgumentException(name+"_must_be_integer",invalid);
            }
        }
    }

    public record EntityObservation(
            String objectId,String typeId,String category,String displayName,
            String itemId,int itemCount,
            int x,int y,int z,double distanceBlocks,long bearingDegrees,
            String sector,String verticalRelation,String knowledge,
            String freshness,String source,boolean lineOfSight,long ageTicks) {}

    public record BlockObservation(
            String objectId,String blockId,String category,
            int x,int y,int z,double distanceBlocks,long bearingDegrees,
            String sector,String verticalRelation,String knowledge,
            String freshness,String source,boolean lineOfSight,long ageTicks) {}

    public record Snapshot(
            Config config,long scanServerTick,
            List<EntityObservation> entities,long entityTotal,
            boolean entityTruncated,long entityOmittedCount,
            List<BlockObservation> blocks,long blockTotal,
            boolean blockTruncated,long blockOmittedCount) {

        public Map<String,Object> summaryWire() {
            long visibleEntities=entities.stream().filter(EntityObservation::lineOfSight).count();
            long rememberedEntities=entities.size()-visibleEntities;
            long visibleBlocks=blocks.stream().filter(BlockObservation::lineOfSight).count();
            long rememberedBlocks=blocks.size()-visibleBlocks;
            Map<String,Object> out=CanonicalJson.object();
            out.put("mode",config.mode().wireName());
            out.put("coverage_degrees",(long)config.mode().coverageDegrees());
            out.put("entity_radius",(long)config.entityRadius());
            out.put("block_radius",(long)config.blockRadius());
            out.put("scan_server_tick",scanServerTick);
            out.put("visible_entity_count",visibleEntities);
            out.put("remembered_entity_count",rememberedEntities);
            out.put("visible_block_count",visibleBlocks);
            out.put("remembered_block_count",rememberedBlocks);
            return out;
        }

        public Map<String,Object> sceneWire() {
            Map<String,Object> out=summaryWire();
            out.put("entities",collection(
                    entityWires(entities,ENTITY_VIEW_LIMIT),
                    entityTotal,entityTruncated,
                    Math.max(entityOmittedCount,
                            Math.max(0L,entityTotal-ENTITY_VIEW_LIMIT))));
            out.put("visible_blocks",collection(
                    blockWires(blocks,BLOCK_VIEW_LIMIT),
                    blockTotal,blockTruncated,
                    Math.max(blockOmittedCount,
                            Math.max(0L,blockTotal-BLOCK_VIEW_LIMIT))));
            out.put("limitations",List.of(
                    "current_state_requires_server_line_of_sight",
                    "occluded_objects_are_last_known_memory_only",
                    "awareness_does_not_authorize_mutation",
                    "precise_actions_require_directional_client_crosshair",
                    "candidate_evaluation_is_bounded_and_may_omit_unseen_objects"));
            return out;
        }

        public Map<String,Object> localWire(int radius,String detail) {
            boolean includeEntities="entities".equals(detail) || "all".equals(detail);
            boolean includeBlocks="blocks".equals(detail) || "all".equals(detail);
            List<EntityObservation> localEntities=entities.stream()
                    .filter(item->item.distanceBlocks()<=radius)
                    .limit(ENTITY_LOCAL_LIMIT).toList();
            List<BlockObservation> localBlocks=blocks.stream()
                    .filter(item->item.distanceBlocks()<=radius)
                    .limit(BLOCK_LOCAL_LIMIT).toList();
            Map<String,Object> out=CanonicalJson.object();
            out.put("perception",summaryWire());
            out.put("entities",includeEntities?entityWires(
                    localEntities,ENTITY_LOCAL_LIMIT):List.of());
            out.put("blocks",includeBlocks?blockWires(
                    localBlocks,BLOCK_LOCAL_LIMIT):List.of());
            return out;
        }

        private static Map<String,Object> collection(
                List<Map<String,Object>> items,long total,
                boolean truncated,long omitted) {
            Map<String,Object> out=CanonicalJson.object();
            out.put("items",items);
            out.put("total",total);
            out.put("truncated",truncated || omitted>0);
            out.put("omitted_count",Math.max(0L,omitted));
            return out;
        }
    }

    private static final class EntityMemory {
        final String objectId,typeId,category,displayName;
        final String itemId;
        final int itemCount;
        Vec3d position;
        long lastSeenTick;
        boolean currentVisible;

        EntityMemory(
                String objectId,String typeId,String category,String displayName,
                String itemId,int itemCount,Vec3d position,long lastSeenTick) {
            this.objectId=objectId;
            this.typeId=typeId;
            this.category=category;
            this.displayName=displayName;
            this.itemId=itemId;
            this.itemCount=itemCount;
            this.position=position;
            this.lastSeenTick=lastSeenTick;
            this.currentVisible=true;
        }
    }

    private static final class BlockMemory {
        final String objectId,blockId,category;
        final BlockPos position;
        long lastSeenTick;
        boolean currentVisible;

        BlockMemory(
                String objectId,String blockId,String category,
                BlockPos position,long lastSeenTick) {
            this.objectId=objectId;
            this.blockId=blockId;
            this.category=category;
            this.position=position.toImmutable();
            this.lastSeenTick=lastSeenTick;
            this.currentVisible=true;
        }
    }

    private record BlockCandidate(
            BlockPos pos,String blockId,String category,double squaredDistance) {}

    private final Config config;
    private final Map<String,EntityMemory> entityMemory=new LinkedHashMap<>();
    private final Map<String,BlockMemory> blockMemory=new LinkedHashMap<>();

    private String boundSession="";
    private String boundDimension="";
    private long lastEntityScan=Long.MIN_VALUE;
    private long lastBlockScan=Long.MIN_VALUE;
    private long lastScanTick=-1L;

    public RealClientOmnidirectionalPerception() {
        this(Config.fromEnvironment());
    }

    public RealClientOmnidirectionalPerception(Config config) {
        this.config=config;
    }

    public Config config() { return config; }

    public void tick(ServerPlayerEntity player,String gameSession) {
        tickAt(player,gameSession,player.getServer().getTicks());
    }

    /** Deterministic clock entry used by GameTest; production calls {@link #tick}. */
    public void tickAt(ServerPlayerEntity player,String gameSession,long serverTick) {
        String dimension=player.getServerWorld().getRegistryKey().getValue().toString();
        if(!gameSession.equals(boundSession) || !dimension.equals(boundDimension)) {
            reset(gameSession,dimension);
        }
        if(lastEntityScan==Long.MIN_VALUE
                || serverTick<lastEntityScan
                || serverTick-lastEntityScan>=config.entityScanTicks()) {
            scanEntities(player,serverTick);
            lastEntityScan=serverTick;
        }
        if(lastBlockScan==Long.MIN_VALUE
                || serverTick<lastBlockScan
                || serverTick-lastBlockScan>=config.blockScanTicks()) {
            scanBlocks(player,serverTick);
            lastBlockScan=serverTick;
        }
        prune(serverTick);
        lastScanTick=serverTick;
    }

    public Snapshot snapshot(ServerPlayerEntity player) {
        long tick=lastScanTick<0?player.getServer().getTicks():lastScanTick;
        List<EntityObservation> entities=entityMemory.values().stream()
                .map(memory->entityObservation(player,memory,tick))
                .sorted(entityComparator())
                .limit(ENTITY_LOCAL_LIMIT)
                .toList();
        List<BlockObservation> blocks=blockMemory.values().stream()
                .map(memory->blockObservation(player,memory,tick))
                .sorted(blockComparator())
                .limit(BLOCK_LOCAL_LIMIT)
                .toList();
        long entityTotal=entityMemory.size();
        long blockTotal=blockMemory.size();
        return new Snapshot(
                config,tick,entities,entityTotal,
                entityTotal>entities.size(),
                Math.max(0L,entityTotal-entities.size()),
                blocks,blockTotal,
                blockTotal>blocks.size(),
                Math.max(0L,blockTotal-blocks.size()));
    }

    public void clear() {
        reset("","");
    }

    private void reset(String session,String dimension) {
        entityMemory.clear();
        blockMemory.clear();
        boundSession=session==null?"":session;
        boundDimension=dimension==null?"":dimension;
        lastEntityScan=Long.MIN_VALUE;
        lastBlockScan=Long.MIN_VALUE;
        lastScanTick=-1L;
    }

    private void scanEntities(ServerPlayerEntity player,long tick) {
        entityMemory.values().forEach(memory->memory.currentVisible=false);
        ServerWorld world=player.getServerWorld();
        double radius=config.entityRadius();
        List<Entity> candidates=new ArrayList<>(world.getOtherEntities(
                player,player.getBoundingBox().expand(radius),
                entity->entity.isAlive()
                        && !(entity instanceof ServerPlayerEntity other
                        && other.isSpectator())
                        && withinEntityRadius(player,entity,radius)));
        candidates.sort(Comparator
                .comparingDouble((Entity entity)->entity.squaredDistanceTo(player))
                .thenComparing(entity->entity.getUuid().toString()));
        int evaluated=0;
        for(Entity entity:candidates) {
            if(evaluated++>=ENTITY_CANDIDATE_LIMIT)break;
            Vec3d target=entityCenter(entity);
            if(!withinMode(player,target) || !visibleEntity(player,entity))continue;
            String id=entity.getUuid().toString();
            String itemId="";
            int itemCount=0;
            if(entity instanceof ItemEntity itemEntity) {
                ItemStack stack=itemEntity.getStack();
                if(!stack.isEmpty()) {
                    itemId=Registries.ITEM.getId(stack.getItem()).toString();
                    itemCount=stack.getCount();
                }
            }
            entityMemory.put(id,new EntityMemory(
                    id,
                    Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
                    entityCategory(entity),
                    truncate(entity.getName().getString(),96),
                    itemId,itemCount,target,tick));
        }
        trimEntityMemory();
    }

    private void scanBlocks(ServerPlayerEntity player,long tick) {
        blockMemory.values().forEach(memory->memory.currentVisible=false);
        ServerWorld world=player.getServerWorld();
        BlockPos center=player.getBlockPos();
        int radius=config.blockRadius();
        int vertical=Math.min(radius,6);
        List<BlockCandidate> candidates=new ArrayList<>();
        for(int dx=-radius;dx<=radius;dx++) {
            for(int dy=-vertical;dy<=vertical;dy++) {
                for(int dz=-radius;dz<=radius;dz++) {
                    if(dx*dx+dy*dy+dz*dz>radius*radius)continue;
                    BlockPos pos=center.add(dx,dy,dz);
                    BlockState state=world.getBlockState(pos);
                    if(state.isAir())continue;
                    String blockId=Registries.BLOCK.getId(state.getBlock()).toString();
                    String category=blockCategory(world,pos,blockId);
                    if(category==null)continue;
                    candidates.add(new BlockCandidate(
                            pos.toImmutable(),blockId,category,
                            pos.toCenterPos().squaredDistanceTo(player.getEyePos())));
                }
            }
        }
        candidates.sort(Comparator
                .comparingDouble(BlockCandidate::squaredDistance)
                .thenComparing(candidate->positionKey(candidate.pos())));
        int evaluated=0;
        for(BlockCandidate candidate:candidates) {
            if(evaluated++>=BLOCK_CANDIDATE_LIMIT)break;
            Vec3d target=candidate.pos().toCenterPos();
            if(!withinMode(player,target)
                    || !visibleBlock(player,candidate.pos()))continue;
            String key=positionKey(candidate.pos());
            blockMemory.put(key,new BlockMemory(
                    key,candidate.blockId(),candidate.category(),
                    candidate.pos(),tick));
        }
        trimBlockMemory();
    }

    private void prune(long tick) {
        entityMemory.entrySet().removeIf(entry->
                tick-entry.getValue().lastSeenTick>config.memoryTicks());
        blockMemory.entrySet().removeIf(entry->
                tick-entry.getValue().lastSeenTick>config.memoryTicks());
    }

    private void trimEntityMemory() {
        if(entityMemory.size()<=ENTITY_MEMORY_CAPACITY)return;
        List<EntityMemory> ordered=new ArrayList<>(entityMemory.values());
        ordered.sort(Comparator
                .comparingLong((EntityMemory memory)->memory.lastSeenTick)
                .thenComparing(memory->memory.objectId));
        int remove=entityMemory.size()-ENTITY_MEMORY_CAPACITY;
        for(int i=0;i<remove;i++)entityMemory.remove(ordered.get(i).objectId);
    }

    private void trimBlockMemory() {
        if(blockMemory.size()<=BLOCK_MEMORY_CAPACITY)return;
        List<BlockMemory> ordered=new ArrayList<>(blockMemory.values());
        ordered.sort(Comparator
                .comparingLong((BlockMemory memory)->memory.lastSeenTick)
                .thenComparing(memory->memory.objectId));
        int remove=blockMemory.size()-BLOCK_MEMORY_CAPACITY;
        for(int i=0;i<remove;i++)blockMemory.remove(ordered.get(i).objectId);
    }

    private static boolean withinEntityRadius(
            ServerPlayerEntity player,Entity entity,double radius) {
        Vec3d target=entityCenter(entity);
        return target.squaredDistanceTo(player.getPos())<=radius*radius;
    }

    private boolean visibleEntity(ServerPlayerEntity player,Entity entity) {
        Vec3d base=entity.getPos();
        List<Vec3d> samples=List.of(
                entityCenter(entity),
                entity.getEyePos(),
                base.add(0D,.1D,0D));
        for(Vec3d sample:samples)
            if(unobstructed(
                    player.getServerWorld(),player.getEyePos(),sample,player))
                return true;
        return false;
    }

    private boolean visibleBlock(ServerPlayerEntity player,BlockPos pos) {
        Vec3d center=pos.toCenterPos();
        List<Vec3d> samples=new ArrayList<>();
        samples.add(center);
        for(Direction direction:Direction.values()) {
            samples.add(center.add(
                    direction.getOffsetX()*.48D,
                    direction.getOffsetY()*.48D,
                    direction.getOffsetZ()*.48D));
        }
        for(Vec3d sample:samples) {
            HitResult hit=player.getServerWorld().raycast(new RaycastContext(
                    player.getEyePos(),sample,
                    RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE,player));
            if(hit instanceof BlockHitResult blockHit
                    && blockHit.getBlockPos().equals(pos))
                return true;
        }
        return false;
    }

    private static boolean unobstructed(
            ServerWorld world,Vec3d start,Vec3d end,Entity context) {
        HitResult hit=world.raycast(new RaycastContext(
                start,end,RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,context));
        return hit.getType()==HitResult.Type.MISS;
    }

    private boolean withinMode(ServerPlayerEntity player,Vec3d target) {
        if(config.mode()==Mode.OMNI_SEMANTIC)return true;
        double bearing=relativeBearing(player,target);
        double pitch=targetPitch(player,target);
        double pitchDelta=MathHelper.wrapDegrees(
                (float)(pitch-player.getPitch()));
        return Math.abs(bearing)<=55D && Math.abs(pitchDelta)<=45D;
    }

    private static String entityCategory(Entity entity) {
        if(entity instanceof ServerPlayerEntity)return "PLAYER";
        if(entity instanceof Monster)return "HOSTILE";
        if(entity instanceof PassiveEntity)return "PASSIVE";
        if(entity instanceof ItemEntity)return "ITEM";
        if(entity instanceof ProjectileEntity)return "PROJECTILE";
        if(entity instanceof LivingEntity)return "LIVING";
        return "OTHER";
    }

    private static final Set<String> STONE_FAMILY=Set.of(
            "minecraft:stone","minecraft:deepslate","minecraft:granite",
            "minecraft:diorite","minecraft:andesite","minecraft:tuff",
            "minecraft:smooth_stone","minecraft:cobblestone");

    private static String blockCategory(
            ServerWorld world,BlockPos pos,String blockId) {
        if(HAZARD_BLOCKS.contains(blockId))return "HAZARD";
        if(blockId.endsWith("_ore")
                || "minecraft:ancient_debris".equals(blockId))
            return "RESOURCE_SURFACE";
        // R2 核心链目标准备:只读感知必须能发现树干/石面候选
        //(awareness_only,不可执行;执行仍需合法准星机会链)。
        if(blockId.endsWith("_log")
                ||blockId.endsWith("_wood")
                ||blockId.endsWith("_stem")
                ||blockId.endsWith("_hyphae"))
            return "RESOURCE_SURFACE";
        if(STONE_FAMILY.contains(blockId))
            return "RESOURCE_SURFACE";
        if(world.getBlockEntity(pos)!=null
                || INTERACTABLE_BLOCKS.contains(blockId)
                || blockId.endsWith("_door")
                || blockId.endsWith("_button")
                || blockId.endsWith("_pressure_plate")
                || blockId.endsWith("_lever")
                || blockId.endsWith("_bed"))
            return "INTERACTABLE";
        return null;
    }

    private static EntityObservation entityObservation(
            ServerPlayerEntity player,EntityMemory memory,long tick) {
        Relative relative=relative(player,memory.position);
        long age=Math.max(0L,tick-memory.lastSeenTick);
        return new EntityObservation(
                memory.objectId,memory.typeId,memory.category,memory.displayName,
                memory.itemId,memory.itemCount,
                MathHelper.floor(memory.position.x),
                MathHelper.floor(memory.position.y),
                MathHelper.floor(memory.position.z),
                relative.distance(),relative.bearing(),
                relative.sector(),relative.vertical(),
                memory.currentVisible?"CURRENT_VISIBLE":"LAST_KNOWN",
                memory.currentVisible?"LIVE":age<=100?"RECENT":"STALE",
                memory.currentVisible?"vision_360":"memory",
                memory.currentVisible,age);
    }

    private static BlockObservation blockObservation(
            ServerPlayerEntity player,BlockMemory memory,long tick) {
        Vec3d target=memory.position.toCenterPos();
        Relative relative=relative(player,target);
        long age=Math.max(0L,tick-memory.lastSeenTick);
        return new BlockObservation(
                memory.objectId,memory.blockId,memory.category,
                memory.position.getX(),memory.position.getY(),memory.position.getZ(),
                relative.distance(),relative.bearing(),
                relative.sector(),relative.vertical(),
                memory.currentVisible?"CURRENT_VISIBLE":"LAST_KNOWN",
                memory.currentVisible?"LIVE":age<=100?"RECENT":"STALE",
                memory.currentVisible?"vision_360":"memory",
                memory.currentVisible,age);
    }

    private record Relative(
            double distance,long bearing,String sector,String vertical) {}

    private static Relative relative(
            ServerPlayerEntity player,Vec3d target) {
        Vec3d delta=target.subtract(player.getPos());
        double distance=Math.sqrt(delta.lengthSquared());
        double bearing=relativeBearing(player,target);
        String vertical=delta.y>2D?"ABOVE":delta.y<-2D?"BELOW":"LEVEL";
        return new Relative(
                Math.round(distance*10D)/10D,
                Math.round(bearing),sector(bearing),vertical);
    }

    public static double relativeBearing(
            ServerPlayerEntity player,Vec3d target) {
        Vec3d delta=target.subtract(player.getPos());
        float targetYaw=(float)(
                MathHelper.atan2(delta.z,delta.x)*180D/Math.PI)-90F;
        return MathHelper.wrapDegrees(targetYaw-player.getHeadYaw());
    }

    private static double targetPitch(
            ServerPlayerEntity player,Vec3d target) {
        Vec3d delta=target.subtract(player.getEyePos());
        double horizontal=Math.sqrt(delta.x*delta.x+delta.z*delta.z);
        return -(MathHelper.atan2(delta.y,horizontal)*180D/Math.PI);
    }

    private static String sector(double bearing) {
        double abs=Math.abs(bearing);
        if(abs<=22.5D)return "FRONT";
        if(abs<=67.5D)return bearing>0?"FRONT_RIGHT":"FRONT_LEFT";
        if(abs<=112.5D)return bearing>0?"RIGHT":"LEFT";
        if(abs<=157.5D)return bearing>0?"BACK_RIGHT":"BACK_LEFT";
        return "BACK";
    }

    private static Vec3d entityCenter(Entity entity) {
        return entity.getPos().add(
                0D,entity.getHeight()*.5D,0D);
    }

    private static String positionKey(BlockPos pos) {
        return pos.getX()+","+pos.getY()+","+pos.getZ();
    }

    private static String truncate(String value,int max) {
        if(value==null)return "";
        return value.length()<=max?value:value.substring(0,max);
    }

    private static Comparator<EntityObservation> entityComparator() {
        return Comparator
                .comparingInt((EntityObservation item)->
                        item.lineOfSight()?0:1)
                .thenComparingInt(item->entityPriority(item.category()))
                .thenComparingDouble(EntityObservation::distanceBlocks)
                .thenComparing(EntityObservation::objectId);
    }

    private static Comparator<BlockObservation> blockComparator() {
        return Comparator
                .comparingInt((BlockObservation item)->
                        item.lineOfSight()?0:1)
                .thenComparingInt(item->blockPriority(item.category()))
                .thenComparingDouble(BlockObservation::distanceBlocks)
                .thenComparing(BlockObservation::objectId);
    }

    private static int entityPriority(String category) {
        return switch(category) {
            case "HOSTILE" -> 0;
            case "PLAYER" -> 1;
            case "PROJECTILE" -> 2;
            case "ITEM" -> 3;
            case "PASSIVE" -> 4;
            default -> 5;
        };
    }

    private static int blockPriority(String category) {
        return switch(category) {
            case "HAZARD" -> 0;
            case "INTERACTABLE" -> 1;
            case "RESOURCE_SURFACE" -> 2;
            default -> 3;
        };
    }

    private static List<Map<String,Object>> entityWires(
            List<EntityObservation> observations,int limit) {
        List<Map<String,Object>> out=new ArrayList<>();
        for(EntityObservation observation:observations) {
            if(out.size()>=limit)break;
            Map<String,Object> item=CanonicalJson.object();
            item.put("object_id",observation.objectId());
            item.put("entity_type",observation.typeId());
            item.put("category",observation.category());
            item.put("name",Map.of(
                    "text",observation.displayName(),
                    "origin","entity_name",
                    "trust","untrusted_data"));
            item.put("knowledge",observation.knowledge());
            item.put("freshness",observation.freshness());
            item.put("source",observation.source());
            item.put("line_of_sight",observation.lineOfSight());
            item.put("last_seen_ticks_ago",observation.ageTicks());
            item.put("position",Map.of(
                    "x",(long)observation.x(),
                    "y",(long)observation.y(),
                    "z",(long)observation.z()));
            item.put("relative",Map.of(
                    "distance_blocks",observation.distanceBlocks(),
                    "bearing_degrees",observation.bearingDegrees(),
                    "sector",observation.sector(),
                    "vertical",observation.verticalRelation()));
            if(!observation.itemId().isBlank()) {
                item.put("item",Map.of(
                        "id",observation.itemId(),
                        "count",(long)observation.itemCount()));
            }
            out.add(item);
        }
        return out;
    }

    private static List<Map<String,Object>> blockWires(
            List<BlockObservation> observations,int limit) {
        List<Map<String,Object>> out=new ArrayList<>();
        for(BlockObservation observation:observations) {
            if(out.size()>=limit)break;
            Map<String,Object> item=CanonicalJson.object();
            item.put("object_id",observation.objectId());
            item.put("block",observation.blockId());
            item.put("category",observation.category());
            item.put("knowledge",observation.knowledge());
            item.put("freshness",observation.freshness());
            item.put("source",observation.source());
            item.put("line_of_sight",observation.lineOfSight());
            item.put("last_seen_ticks_ago",observation.ageTicks());
            item.put("actionability","awareness_only");
            item.put("position",Map.of(
                    "x",(long)observation.x(),
                    "y",(long)observation.y(),
                    "z",(long)observation.z()));
            item.put("relative",Map.of(
                    "distance_blocks",observation.distanceBlocks(),
                    "bearing_degrees",observation.bearingDegrees(),
                    "sector",observation.sector(),
                    "vertical",observation.verticalRelation()));
            out.add(item);
        }
        return out;
    }
}
