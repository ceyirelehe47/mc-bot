package io.github.zoyluo.aibot.external;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.task.BlueprintSchema;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * World-local operational spatial state for the external Minecraft body.
 *
 * Physical truth remains in the current Minecraft save. This registry stores semantic/desired
 * state only and is never Iris conversational/long-term memory. R2 introduces a stable per-save
 * world_id, dimension-scoped object keys, exact registered farm cells, deferred resource
 * opportunities, and a conservative HOME desired-state baseline.
 */
public final class SemanticWorldRegistry {
    private static final int VERSION = 2;
    private static final int MAX_STRUCTURES = 64;
    private static final int MAX_FARMS = 64;
    private static final int MAX_FARM_RADIUS = 16;
    private static final int MAX_FARM_CELLS = 512;
    private static final int MAX_OPPORTUNITIES = 256;
    private static final int MAX_SNAPSHOT_CELLS = 4096;
    private static final int LIVE_SUMMARY_DISTANCE = 32;
    private static final int INTEGRITY_CACHE_TICKS = 20;
    private static final Gson GSON = new Gson();

    private static final Map<String, Structure> STRUCTURES = new LinkedHashMap<>();
    private static final Map<String, Farm> FARMS = new LinkedHashMap<>();
    private static final Map<String, ResourceOpportunity> OPPORTUNITIES = new LinkedHashMap<>();
    private static final Map<String, CachedIntegrity> INTEGRITY = new LinkedHashMap<>();

    private static MinecraftServer server;
    private static Path file;
    private static Path worldIdFile;
    private static String worldId = "";
    private static ExecutorService writer;
    private static CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);
    private static volatile String persistenceFault = "";

    private SemanticWorldRegistry() {}

    public static void start(MinecraftServer minecraftServer, String botName) throws IOException {
        if (!minecraftServer.isOnThread()) throw new IllegalStateException("semantic_registry_start_off_server_thread");
        stopQuietly();
        server = minecraftServer;
        Path dir = minecraftServer.getSavePath(WorldSavePath.ROOT).resolve("aibot");
        Files.createDirectories(dir);
        worldIdFile = dir.resolve("world-id");
        worldId = loadOrCreateWorldId(worldIdFile);
        file = dir.resolve("external-semantics-" + botName.toLowerCase(Locale.ROOT) + ".json");
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aibot-semantic-world-writer");
            t.setDaemon(true);
            return t;
        });
        STRUCTURES.clear(); FARMS.clear(); OPPORTUNITIES.clear(); INTEGRITY.clear(); persistenceFault = "";
        boolean migrated = false;
        if (Files.exists(file)) migrated = load(Files.readString(file, StandardCharsets.UTF_8));
        if (migrated) persistAsync();
    }

    public static void stop() {
        if (writer == null) { clearRuntime(); return; }
        try {
            lastWrite.get(2, TimeUnit.SECONDS);
        } catch (Exception failure) {
            persistenceFault = "semantic_registry_flush_failed:" + failure.getClass().getSimpleName();
        } finally {
            writer.shutdown();
            try { writer.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            clearRuntime();
        }
    }

    private static void stopQuietly() {
        try { stop(); } catch (RuntimeException ignored) { clearRuntime(); }
    }

    private static void clearRuntime() {
        server = null; file = null; worldIdFile = null; worldId = ""; writer = null;
        lastWrite = CompletableFuture.completedFuture(null);
        STRUCTURES.clear(); FARMS.clear(); OPPORTUNITIES.clear(); INTEGRITY.clear();
    }

    public record Registration(String payload, CompletableFuture<Void> persisted) {}
    public record FarmSpec(String id, BlockPos center, int radius, Block crop, Item seed) {}
    public record OpportunitySpec(String id, String dimension, BlockPos pos, Block block,
                                  BlockPos seenFrom, String status, String blockedReason) {}
    public record HomeRepairPlan(String id, BlockPos anchor, BlueprintSchema blueprint,
                                 int expected, int missing, int wrong) {}

    private record Cell(int x, int y, int z) {
        BlockPos pos() { return new BlockPos(x, y, z); }
        static Cell of(BlockPos pos) { return new Cell(pos.getX(), pos.getY(), pos.getZ()); }
    }
    private record SnapshotCell(int x, int y, int z, String blockId) {
        BlockPos pos() { return new BlockPos(x, y, z); }
    }
    private record Structure(String id, String kind, String dimension,
                             int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                             List<SnapshotCell> snapshot) {
        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
        BlockPos anchor() { return new BlockPos(minX, minY, minZ); }
    }
    private record Farm(String id, String dimension, int x, int y, int z, int radius,
                        String cropId, List<Cell> cells) {
        boolean contains(BlockPos pos) {
            return cells.stream().anyMatch(cell -> cell.x == pos.getX() && cell.y == pos.getY() && cell.z == pos.getZ());
        }
    }
    private record ResourceOpportunity(String id, String dimension, int x, int y, int z,
                                       String blockId, int seenX, int seenY, int seenZ,
                                       String status, String blockedReason, String requiredTool,
                                       long lastSeenGameTime) {
        BlockPos pos() { return new BlockPos(x, y, z); }
        BlockPos seenFrom() { return new BlockPos(seenX, seenY, seenZ); }
    }
    private record Integrity(int expected, int matched, int missing, int wrong) {}
    private record CachedIntegrity(int tick, Integrity value) {}
    private record FarmCandidate(BlockPos center, Block crop, List<Cell> cells) {}

    public static String worldId() {
        if (worldId.isBlank()) throw new IllegalStateException("semantic_registry_not_started");
        return worldId;
    }

    public static Registration registerHome(AIPlayerEntity bot, String rawId, int radius, int below, int above) {
        requireReady(bot);
        String id = id(rawId, "home");
        String dim = dimension(bot);
        String key = scoped(dim, id);
        if (!STRUCTURES.containsKey(key) && STRUCTURES.size() >= MAX_STRUCTURES) throw new IllegalStateException("structure_registry_full");
        BlockPos center = bot.getBlockPos();
        Structure structure = new Structure(id, "HOME", dim,
                center.getX() - radius, center.getY() - below, center.getZ() - radius,
                center.getX() + radius, center.getY() + above, center.getZ() + radius,
                List.of());
        STRUCTURES.put(key, structure); INTEGRITY.remove(key);
        CompletableFuture<Void> persisted = persistAsync();
        JsonObject result = structureJson(bot, structure);
        result.addProperty("registered", true);
        result.addProperty("world_id", worldId);
        result.addProperty("dimension", dim);
        result.addProperty("note", "protection_only_call_mc_capture_home_for_desired_baseline");
        return new Registration(GSON.toJson(result), persisted);
    }

    public static Registration captureHome(AIPlayerEntity bot, String rawId) {
        requireReady(bot);
        String id = id(rawId, "home");
        String dim = dimension(bot);
        String key = scoped(dim, id);
        Structure current = STRUCTURES.get(key);
        if (current == null || !"HOME".equals(current.kind)) throw new IllegalStateException("home_not_registered_in_current_dimension");
        List<SnapshotCell> snapshot = new ArrayList<>();
        ServerWorld world = bot.getServerWorld();
        for (BlockPos raw : BlockPos.iterate(
                new BlockPos(current.minX, current.minY, current.minZ),
                new BlockPos(current.maxX, current.maxY, current.maxZ))) {
            BlockState state = world.getBlockState(raw);
            if (state.isAir() || !world.getFluidState(raw).isEmpty() || OreScan.isOreBlock(state.getBlock())) continue;
            String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
            if ("minecraft:air".equals(blockId)) continue;
            snapshot.add(new SnapshotCell(raw.getX(), raw.getY(), raw.getZ(), blockId));
            if (snapshot.size() > MAX_SNAPSHOT_CELLS) throw new IllegalStateException("home_snapshot_too_large");
        }
        if (snapshot.isEmpty()) throw new IllegalStateException("home_snapshot_empty");
        Structure captured = new Structure(current.id, current.kind, current.dimension,
                current.minX, current.minY, current.minZ, current.maxX, current.maxY, current.maxZ,
                List.copyOf(snapshot));
        STRUCTURES.put(key, captured); INTEGRITY.remove(key);
        CompletableFuture<Void> persisted = persistAsync();
        JsonObject result = structureJson(bot, captured);
        result.addProperty("captured", true);
        result.addProperty("snapshot_cells", snapshot.size());
        result.addProperty("repair_scope", "missing_expected_block_ids_only_no_extra_block_deletion_no_block_entity_data");
        return new Registration(GSON.toJson(result), persisted);
    }

    public static Registration registerFarm(AIPlayerEntity bot, String rawId, int radius, String cropName) {
        requireReady(bot);
        String id = id(rawId, "farm");
        String dim = dimension(bot);
        String key = scoped(dim, id);
        if (!FARMS.containsKey(key) && FARMS.size() >= MAX_FARMS) throw new IllegalStateException("farm_registry_full");
        int boundedRadius = Math.max(1, Math.min(MAX_FARM_RADIUS, radius));
        Block explicitCrop = cropName == null || cropName.isBlank() ? null : FarmAction.cropSpec(cropName).crop();
        FarmCandidate candidate = findFarmCandidate(bot, boundedRadius, explicitCrop)
                .orElseThrow(() -> new IllegalStateException("no_observed_farmland_or_supported_crop"));
        Block crop = explicitCrop != null ? explicitCrop : candidate.crop();
        if (crop == null || !FarmAction.isSupportedCrop(crop)) throw new IllegalStateException("farm_crop_unknown_register_with_crop");
        if (candidate.cells().isEmpty()) throw new IllegalStateException("no_observed_connected_farmland_cells");
        Farm farm = new Farm(id, dim, candidate.center().getX(), candidate.center().getY(), candidate.center().getZ(), boundedRadius,
                Registries.BLOCK.getId(crop).toString(), List.copyOf(candidate.cells()));
        FARMS.put(key, farm);
        CompletableFuture<Void> persisted = persistAsync();
        JsonObject result = farmJson(bot, farm);
        result.addProperty("registered", true);
        result.addProperty("world_id", worldId);
        result.addProperty("dimension", dim);
        return new Registration(GSON.toJson(result), persisted);
    }

    public static Optional<FarmSpec> farm(AIPlayerEntity bot, String rawId) {
        requireReady(bot);
        Farm farm = FARMS.get(scoped(dimension(bot), id(rawId, "farm")));
        if (farm == null || farm.cells.isEmpty()) return Optional.empty();
        Identifier identifier = Identifier.tryParse(farm.cropId);
        Block crop = identifier == null ? null : Registries.BLOCK.getOptionalValue(identifier).orElse(null);
        if (crop == null || !FarmAction.isSupportedCrop(crop)) return Optional.empty();
        return Optional.of(new FarmSpec(farm.id, new BlockPos(farm.x, farm.y, farm.z), farm.radius, crop, FarmAction.seedFor(crop)));
    }

    /**
     * FarmTask observation/mutation gate. Registered external farms use an exact cell mask and may
     * inspect a thin crop when the crop cell itself is line-of-sight visible; legacy non-reserved
     * tasks retain the upstream block-face rule.
     */
    public static boolean farmSurveyCellObservable(AIPlayerEntity bot, BlockPos areaCenter, int radius, Block crop, BlockPos ground) {
        if (bot == null || ground == null) return false;
        if (!ExternalBodyAccess.reserved(bot) || server == null) {
            return ObservableWorldQuery.canObserveBlock(bot, ground)
                    || ObservableWorldQuery.canObserveBlock(bot, ground.up());
        }
        Farm match = null;
        String dim = dimension(bot);
        for (Farm farm : FARMS.values()) {
            if (farm.dimension.equals(dim) && farm.x == areaCenter.getX() && farm.y == areaCenter.getY()
                    && farm.z == areaCenter.getZ() && farm.radius == radius
                    && crop != null && Registries.BLOCK.getId(crop).toString().equals(farm.cropId)) {
                match = farm; break;
            }
        }
        if (match == null) {
            return ObservableWorldQuery.canObserveBlock(bot, ground)
                    || ObservableWorldQuery.canObserveBlock(bot, ground.up());
        }
        if (!match.contains(ground)) return false;
        return ObservableWorldQuery.canObserveBlock(bot, ground)
                || ObservableWorldQuery.canObserveCell(bot, ground.up());
    }

    public static String protectionReason(AIPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null || server == null) return null;
        String dim = dimension(bot);
        for (Structure structure : STRUCTURES.values()) {
            if (structure.dimension.equals(dim) && structure.contains(pos)) return "protected_structure:" + structure.id;
        }
        for (Farm farm : FARMS.values()) {
            if (!farm.dimension.equals(dim)) continue;
            if (farm.contains(pos)) return "registered_farm:" + farm.id;
            BlockPos below = pos.down();
            if (farm.contains(below)) return "registered_farm:" + farm.id;
        }
        return null;
    }

    /** Called only for a block already admitted by PerceptionCollector's strict observation gate. */
    public static void observeVisibleBlock(AIPlayerEntity bot, BlockPos pos, BlockState state) {
        if (server == null || bot == null || pos == null || state == null || !ExternalBodyAccess.reserved(bot)) return;
        String dim = dimension(bot);
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        boolean ore = OreScan.isOreBlock(state.getBlock());
        List<String> remove = new ArrayList<>();
        for (var entry : OPPORTUNITIES.entrySet()) {
            ResourceOpportunity opportunity = entry.getValue();
            if (opportunity.dimension.equals(dim) && opportunity.x == pos.getX()
                    && opportunity.y == pos.getY() && opportunity.z == pos.getZ()
                    && (!ore || !opportunity.blockId.equals(blockId))) remove.add(entry.getKey());
        }
        boolean dirty = false;
        for (String key : remove) { OPPORTUNITIES.remove(key); dirty = true; }
        if (!ore) { if (dirty) persistAsync(); return; }

        String id = opportunityId(dim, pos, blockId);
        String key = scoped(dim, id);
        boolean actionable = ToolTier.canHarvestWithInventory(bot, state);
        String status = actionable ? "ACTIONABLE" : "BLOCKED";
        String reason = actionable ? "" : "insufficient_tool";
        String required = ToolTier.requiredPickaxeItemId(state.getBlock());
        ResourceOpportunity prior = OPPORTUNITIES.get(key);
        BlockPos seenFrom = prior == null ? bot.getBlockPos() : prior.seenFrom();
        ResourceOpportunity next = new ResourceOpportunity(id, dim, pos.getX(), pos.getY(), pos.getZ(), blockId,
                seenFrom.getX(), seenFrom.getY(), seenFrom.getZ(),
                status, reason, required, bot.getServerWorld().getTime());
        if (prior == null) {
            evictOpportunityIfNeeded(); OPPORTUNITIES.put(key, next); dirty = true;
            if ("ACTIONABLE".equals(next.status)) {
                ExternalBodyRuntime.resourceOpportunityActionable(bot, next.id, next.blockId, next.pos(), next.seenFrom());
            }
        } else {
            OPPORTUNITIES.put(key, next);
            dirty = !prior.status.equals(next.status) || !prior.blockId.equals(next.blockId)
                    || !prior.requiredTool.equals(next.requiredTool);
            if ("BLOCKED".equals(prior.status) && "ACTIONABLE".equals(next.status)) {
                ExternalBodyRuntime.resourceOpportunityActionable(bot, next.id, next.blockId, next.pos(), next.seenFrom());
            }
        }
        if (dirty) persistAsync();
    }

    public static Optional<OpportunitySpec> opportunity(AIPlayerEntity bot, String rawId) {
        requireReady(bot);
        String id = id(rawId, "opportunity");
        ResourceOpportunity opportunity = OPPORTUNITIES.get(scoped(dimension(bot), id));
        if (opportunity == null) return Optional.empty();
        Identifier blockIdentifier = Identifier.tryParse(opportunity.blockId);
        Block block = blockIdentifier == null ? null : Registries.BLOCK.getOptionalValue(blockIdentifier).orElse(null);
        if (block == null || !OreScan.isOreBlock(block)) return Optional.empty();
        return Optional.of(new OpportunitySpec(opportunity.id, opportunity.dimension, opportunity.pos(), block,
                opportunity.seenFrom(), opportunity.status, opportunity.blockedReason));
    }

    public static void markOpportunityConsumed(AIPlayerEntity bot, String rawId) {
        if (server == null || bot == null) return;
        String key = scoped(dimension(bot), id(rawId, "opportunity"));
        if (OPPORTUNITIES.remove(key) != null) persistAsync();
    }

    public static HomeRepairPlan homeRepairPlan(AIPlayerEntity bot, String rawId) {
        requireReady(bot);
        String id = id(rawId, "home");
        Structure structure = STRUCTURES.get(scoped(dimension(bot), id));
        if (structure == null || !"HOME".equals(structure.kind)) throw new IllegalStateException("home_not_registered_in_current_dimension");
        if (structure.snapshot.isEmpty()) throw new IllegalStateException("home_baseline_not_captured");
        List<BlueprintSchema.BlockPlacement> missing = new ArrayList<>();
        int wrong = 0;
        ServerWorld world = bot.getServerWorld();
        BlockPos anchor = structure.anchor();
        for (SnapshotCell cell : structure.snapshot) {
            BlockPos pos = cell.pos();
            Identifier expectedId = Identifier.tryParse(cell.blockId);
            Block expected = expectedId == null ? null : Registries.BLOCK.getOptionalValue(expectedId).orElse(null);
            if (expected == null || expected == Blocks.AIR) continue;
            BlockState actual = world.getBlockState(pos);
            if (actual.isOf(expected)) continue;
            if (actual.isAir() || actual.isReplaceable()) {
                missing.add(new BlueprintSchema.BlockPlacement(
                        pos.getX() - anchor.getX(), pos.getY() - anchor.getY(), pos.getZ() - anchor.getZ(), cell.blockId));
            } else {
                wrong++;
            }
        }
        int width = structure.maxX - structure.minX + 1;
        int height = structure.maxY - structure.minY + 1;
        int depth = structure.maxZ - structure.minZ + 1;
        BlueprintSchema blueprint = new BlueprintSchema("home_repair:" + id, width, height, depth, List.copyOf(missing), List.of());
        return new HomeRepairPlan(id, anchor, blueprint, structure.snapshot.size(), missing.size(), wrong);
    }

    public static JsonObject observe(AIPlayerEntity bot) {
        requireReady(bot);
        refreshOpportunityCapabilities(bot);
        JsonObject out = new JsonObject();
        out.addProperty("schema", "mc_spatial_semantics_v2");
        out.addProperty("world_id", worldId);
        out.addProperty("dimension", dimension(bot));
        out.addProperty("persistence_fault", persistenceFault);

        JsonArray structures = new JsonArray();
        for (Structure structure : STRUCTURES.values()) if (structure.dimension.equals(dimension(bot))) structures.add(structureJson(bot, structure));
        out.add("structures", structures);
        JsonArray farms = new JsonArray();
        for (Farm farm : FARMS.values()) if (farm.dimension.equals(dimension(bot))) farms.add(farmJson(bot, farm));
        out.add("farms", farms);
        findFarmCandidate(bot, 8, null).ifPresent(candidate -> out.add("nearby_farm_candidate", candidateJson(bot, candidate, 8)));

        JsonArray opportunities = new JsonArray();
        OPPORTUNITIES.values().stream()
                .filter(opportunity -> opportunity.dimension.equals(dimension(bot)))
                .sorted(Comparator.comparingDouble(opportunity -> opportunity.pos().getSquaredDistance(bot.getBlockPos())))
                .limit(32)
                .forEach(opportunity -> opportunities.add(opportunityJson(bot, opportunity)));
        out.add("resource_opportunities", opportunities);
        long otherDimensions = OPPORTUNITIES.values().stream().filter(opportunity -> !opportunity.dimension.equals(dimension(bot))).count();
        out.addProperty("resource_opportunities_other_dimensions", otherDimensions);

        out.addProperty("destructive_policy", "physical_mining_gate_must_enforce_registered_structure_and_farm_cells; safety_origin_can_override");
        out.addProperty("tree_policy", "natural_tree_cluster_is_leased_to_one_active_gather_after_initial_proof");
        out.addProperty("repair_policy", "missing_expected_block_ids_only; never_delete_extra_blocks; no_block_entity_or_inventory_restore");
        return out;
    }

    private static JsonObject structureJson(AIPlayerEntity bot, Structure structure) {
        JsonObject o = new JsonObject();
        o.addProperty("id", structure.id); o.addProperty("kind", structure.kind); o.addProperty("protected", true);
        o.addProperty("inside", structure.contains(bot.getBlockPos()));
        JsonObject bounds = new JsonObject();
        bounds.addProperty("min_x", structure.minX); bounds.addProperty("min_y", structure.minY); bounds.addProperty("min_z", structure.minZ);
        bounds.addProperty("max_x", structure.maxX); bounds.addProperty("max_y", structure.maxY); bounds.addProperty("max_z", structure.maxZ);
        o.add("bounds", bounds);
        o.addProperty("snapshot_cells", structure.snapshot.size());
        if (!structure.snapshot.isEmpty()) {
            Integrity integrity = integrity(bot, structure);
            o.addProperty("integrity_expected", integrity.expected);
            o.addProperty("integrity_matched", integrity.matched);
            o.addProperty("integrity_missing", integrity.missing);
            o.addProperty("integrity_wrong", integrity.wrong);
            o.addProperty("integrity", integrity.expected == 0 ? 1.0D
                    : Math.round((double) integrity.matched / integrity.expected * 1000.0D) / 1000.0D);
            o.addProperty("repairable", integrity.missing > 0);
        }
        return o;
    }

    private static Integrity integrity(AIPlayerEntity bot, Structure structure) {
        String key = scoped(structure.dimension, structure.id);
        int tick = bot.getServer().getTicks();
        CachedIntegrity cached = INTEGRITY.get(key);
        if (cached != null && tick >= cached.tick && tick - cached.tick < INTEGRITY_CACHE_TICKS) return cached.value;
        int matched = 0, missing = 0, wrong = 0;
        ServerWorld world = bot.getServerWorld();
        for (SnapshotCell cell : structure.snapshot) {
            Identifier id = Identifier.tryParse(cell.blockId);
            Block expected = id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
            if (expected == null) { wrong++; continue; }
            BlockState actual = world.getBlockState(cell.pos());
            if (actual.isOf(expected)) matched++;
            else if (actual.isAir() || actual.isReplaceable()) missing++;
            else wrong++;
        }
        Integrity value = new Integrity(structure.snapshot.size(), matched, missing, wrong);
        INTEGRITY.put(key, new CachedIntegrity(tick, value));
        return value;
    }

    private static JsonObject farmJson(AIPlayerEntity bot, Farm farm) {
        JsonObject o = new JsonObject();
        o.addProperty("id", farm.id); o.addProperty("crop", farm.cropId); o.addProperty("radius", farm.radius);
        JsonObject center = new JsonObject(); center.addProperty("x", farm.x); center.addProperty("y", farm.y); center.addProperty("z", farm.z); o.add("center", center);
        o.addProperty("registered_cells", farm.cells.size());
        double distance = Math.sqrt(bot.getBlockPos().getSquaredDistance(new BlockPos(farm.x, farm.y, farm.z)));
        o.addProperty("distance", Math.round(distance * 10.0) / 10.0);
        if (farm.cells.isEmpty()) {
            o.addProperty("fresh", false); o.addProperty("needs_reregister", true);
        } else if (distance <= LIVE_SUMMARY_DISTANCE) addFarmStats(o, bot, farm);
        else o.addProperty("fresh", false);
        return o;
    }

    private static JsonObject candidateJson(AIPlayerEntity bot, FarmCandidate candidate, int radius) {
        JsonObject o = new JsonObject();
        o.addProperty("registered", false); o.addProperty("radius", radius);
        JsonObject center = new JsonObject(); center.addProperty("x", candidate.center().getX()); center.addProperty("y", candidate.center().getY()); center.addProperty("z", candidate.center().getZ()); o.add("center", center);
        String cropId = candidate.crop() == null ? "" : Registries.BLOCK.getId(candidate.crop()).toString();
        o.addProperty("crop", cropId);
        if (!cropId.isBlank()) addObservedFarmStats(o, bot, candidate.cells(), cropId);
        return o;
    }

    private static void addFarmStats(JsonObject o, AIPlayerEntity bot, Farm farm) {
        addObservedFarmStats(o, bot, farm.cells, farm.cropId);
    }

    private static void addObservedFarmStats(JsonObject o, AIPlayerEntity bot, List<Cell> cells, String cropId) {
        Identifier identifier = Identifier.tryParse(cropId);
        Block crop = identifier == null ? null : Registries.BLOCK.getOptionalValue(identifier).orElse(null);
        if (crop == null || !FarmAction.isSupportedCrop(crop)) { o.addProperty("fresh", false); return; }
        ServerWorld world = bot.getServerWorld();
        int observed = 0, farmland = 0, mature = 0, immature = 0, empty = 0, occupiedOther = 0;
        for (Cell cell : cells) {
            BlockPos ground = cell.pos();
            if (!ObservableWorldQuery.canObserveBlock(bot, ground)
                    && !ObservableWorldQuery.canObserveCell(bot, ground.up())) continue;
            observed++;
            if (!world.getBlockState(ground).isOf(Blocks.FARMLAND)) continue;
            farmland++;
            BlockPos cropPos = ground.up();
            if (world.getBlockState(cropPos).isOf(crop)) {
                if (FarmAction.isMature(world, cropPos)) mature++; else immature++;
            } else if (world.getBlockState(cropPos).isAir()) empty++;
            else occupiedOther++;
        }
        o.addProperty("fresh", observed == cells.size()); o.addProperty("observed_cells", observed); o.addProperty("total_cells", cells.size());
        o.addProperty("farmland", farmland); o.addProperty("mature", mature);
        o.addProperty("immature", immature); o.addProperty("empty_farmland", empty); o.addProperty("occupied_other", occupiedOther);
        o.addProperty("needs_tending", mature > 0 || empty > 0);
    }

    private static JsonObject opportunityJson(AIPlayerEntity bot, ResourceOpportunity opportunity) {
        JsonObject o = new JsonObject();
        o.addProperty("id", opportunity.id); o.addProperty("block", opportunity.blockId);
        o.addProperty("status", opportunity.status); o.addProperty("blocked_reason", opportunity.blockedReason);
        o.addProperty("required_tool", opportunity.requiredTool); o.addProperty("last_seen_game_time", opportunity.lastSeenGameTime);
        JsonObject pos = new JsonObject(); pos.addProperty("x", opportunity.x); pos.addProperty("y", opportunity.y); pos.addProperty("z", opportunity.z); o.add("position", pos);
        JsonObject from = new JsonObject(); from.addProperty("x", opportunity.seenX); from.addProperty("y", opportunity.seenY); from.addProperty("z", opportunity.seenZ); o.add("seen_from", from);
        o.addProperty("distance", Math.round(Math.sqrt(opportunity.pos().getSquaredDistance(bot.getBlockPos())) * 10.0D) / 10.0D);
        return o;
    }

    private static Optional<FarmCandidate> findFarmCandidate(AIPlayerEntity bot, int radius, Block desiredCrop) {
        BlockPos origin = bot.getBlockPos();
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos raw : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            BlockPos pos = raw.toImmutable();
            Block block = bot.getServerWorld().getBlockState(pos).getBlock();
            if (!FarmAction.isSupportedCrop(block) && block != Blocks.FARMLAND) continue;
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)
                    && !ObservableWorldQuery.canObserveCell(bot, pos.up())) continue;
            positions.add(pos);
        }
        positions.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(origin)));
        for (BlockPos pos : positions) {
            Block block = bot.getServerWorld().getBlockState(pos).getBlock();
            BlockPos ground;
            Block crop;
            if (FarmAction.isSupportedCrop(block)) {
                if (desiredCrop != null && block != desiredCrop) continue;
                ground = bot.getServerWorld().getBlockState(pos.down()).isOf(Blocks.FARMLAND) ? pos.down() : pos;
                crop = block;
            } else if (block == Blocks.FARMLAND) {
                Block above = bot.getServerWorld().getBlockState(pos.up()).getBlock();
                crop = desiredCrop != null ? desiredCrop : (FarmAction.isSupportedCrop(above) ? above : null);
                ground = pos;
            } else continue;
            if (crop == null) continue;
            List<Cell> cells = collectObservedConnectedFarmland(bot, ground, radius);
            if (!cells.isEmpty()) return Optional.of(new FarmCandidate(ground.toImmutable(), crop, cells));
        }
        return Optional.empty();
    }

    private static List<Cell> collectObservedConnectedFarmland(AIPlayerEntity bot, BlockPos seed, int radius) {
        ServerWorld world = bot.getServerWorld();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        List<Cell> cells = new ArrayList<>();
        open.add(seed.toImmutable());
        while (!open.isEmpty() && cells.size() < MAX_FARM_CELLS) {
            BlockPos pos = open.removeFirst();
            if (!seen.add(pos.asLong())) continue;
            if (Math.abs(pos.getX() - seed.getX()) > radius || Math.abs(pos.getZ() - seed.getZ()) > radius || Math.abs(pos.getY() - seed.getY()) > 1) continue;
            if (!ObservableWorldQuery.canObserveBlock(bot, pos) && !ObservableWorldQuery.canObserveCell(bot, pos.up())) continue;
            if (!world.getBlockState(pos).isOf(Blocks.FARMLAND)) continue;
            cells.add(Cell.of(pos));
            for (Direction direction : Direction.Type.HORIZONTAL) open.addLast(pos.offset(direction));
        }
        cells.sort(Comparator.comparingInt(Cell::y).thenComparingInt(Cell::x).thenComparingInt(Cell::z));
        return cells;
    }

    private static void refreshOpportunityCapabilities(AIPlayerEntity bot) {
        String dim = dimension(bot);
        boolean dirty = false;
        List<ResourceOpportunity> transitions = new ArrayList<>();
        for (var entry : new ArrayList<>(OPPORTUNITIES.entrySet())) {
            ResourceOpportunity opportunity = entry.getValue();
            if (!opportunity.dimension.equals(dim)) continue;
            Identifier id = Identifier.tryParse(opportunity.blockId);
            Block block = id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
            if (block == null || !OreScan.isOreBlock(block)) continue;
            boolean actionable = ToolTier.canHarvestWithInventory(bot, block.getDefaultState());
            String nextStatus = actionable ? "ACTIONABLE" : "BLOCKED";
            String nextReason = actionable ? "" : "insufficient_tool";
            if (nextStatus.equals(opportunity.status) && nextReason.equals(opportunity.blockedReason)) continue;
            ResourceOpportunity next = new ResourceOpportunity(opportunity.id, opportunity.dimension,
                    opportunity.x, opportunity.y, opportunity.z, opportunity.blockId,
                    opportunity.seenX, opportunity.seenY, opportunity.seenZ,
                    nextStatus, nextReason, ToolTier.requiredPickaxeItemId(block), opportunity.lastSeenGameTime);
            OPPORTUNITIES.put(entry.getKey(), next); dirty = true;
            if ("BLOCKED".equals(opportunity.status) && "ACTIONABLE".equals(nextStatus)) transitions.add(next);
        }
        if (dirty) persistAsync();
        for (ResourceOpportunity next : transitions) {
            ExternalBodyRuntime.resourceOpportunityActionable(bot, next.id, next.blockId, next.pos(), next.seenFrom());
        }
    }

    private static void evictOpportunityIfNeeded() {
        while (OPPORTUNITIES.size() >= MAX_OPPORTUNITIES) {
            String oldest = OPPORTUNITIES.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().lastSeenGameTime))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest == null) break;
            OPPORTUNITIES.remove(oldest);
        }
    }

    private static String opportunityId(String dimension, BlockPos pos, String blockId) {
        String material = worldId + "\n" + dimension + "\n" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\n" + blockId;
        return "ore_" + UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private static void requireReady(AIPlayerEntity bot) {
        if (server == null || file == null || writer == null || worldId.isBlank()) throw new IllegalStateException("semantic_registry_not_started");
        if (!server.isOnThread()) throw new IllegalStateException("semantic_registry_off_server_thread");
        if (bot == null) throw new IllegalArgumentException("bot_required");
        if (!persistenceFault.isBlank()) throw new IllegalStateException("semantic_registry_failed_closed:" + persistenceFault);
    }

    private static CompletableFuture<Void> persistAsync() {
        if (writer == null || file == null) return CompletableFuture.failedFuture(new IllegalStateException("semantic_registry_not_started"));
        String snapshot = encode(); Path target = file;
        CompletableFuture<Void> next = lastWrite.handle((ignored, priorFailure) -> null).thenRunAsync(() -> {
            try { writeAtomic(target, snapshot); }
            catch (IOException e) { persistenceFault = "write_failed:" + e.getClass().getSimpleName(); throw new CompletionException(e); }
        }, writer);
        lastWrite = next;
        return next;
    }

    private static String loadOrCreateWorldId(Path target) throws IOException {
        if (Files.exists(target)) {
            String value = Files.readString(target, StandardCharsets.UTF_8).trim();
            try { return UUID.fromString(value).toString(); }
            catch (IllegalArgumentException invalid) { throw new IOException("semantic_world_id_invalid", invalid); }
        }
        String value = UUID.randomUUID().toString();
        writeAtomic(target, value + "\n");
        return value;
    }

    private static void writeAtomic(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String encode() {
        JsonObject root = new JsonObject(); root.addProperty("version", VERSION); root.addProperty("world_id", worldId);
        JsonArray structures = new JsonArray();
        for (Structure s : STRUCTURES.values()) {
            JsonObject o = new JsonObject(); o.addProperty("id", s.id); o.addProperty("kind", s.kind); o.addProperty("dimension", s.dimension);
            o.addProperty("min_x", s.minX); o.addProperty("min_y", s.minY); o.addProperty("min_z", s.minZ);
            o.addProperty("max_x", s.maxX); o.addProperty("max_y", s.maxY); o.addProperty("max_z", s.maxZ);
            JsonArray snapshot = new JsonArray();
            for (SnapshotCell cell : s.snapshot) {
                JsonObject c = new JsonObject(); c.addProperty("x", cell.x); c.addProperty("y", cell.y); c.addProperty("z", cell.z); c.addProperty("block", cell.blockId); snapshot.add(c);
            }
            o.add("snapshot", snapshot); structures.add(o);
        }
        root.add("structures", structures);
        JsonArray farms = new JsonArray();
        for (Farm f : FARMS.values()) {
            JsonObject o = new JsonObject(); o.addProperty("id", f.id); o.addProperty("dimension", f.dimension); o.addProperty("x", f.x); o.addProperty("y", f.y); o.addProperty("z", f.z);
            o.addProperty("radius", f.radius); o.addProperty("crop", f.cropId);
            JsonArray cells = new JsonArray();
            for (Cell cell : f.cells) { JsonObject c = new JsonObject(); c.addProperty("x", cell.x); c.addProperty("y", cell.y); c.addProperty("z", cell.z); cells.add(c); }
            o.add("cells", cells); farms.add(o);
        }
        root.add("farms", farms);
        JsonArray opportunities = new JsonArray();
        for (ResourceOpportunity opportunity : OPPORTUNITIES.values()) {
            JsonObject o = new JsonObject(); o.addProperty("id", opportunity.id); o.addProperty("dimension", opportunity.dimension);
            o.addProperty("x", opportunity.x); o.addProperty("y", opportunity.y); o.addProperty("z", opportunity.z); o.addProperty("block", opportunity.blockId);
            o.addProperty("seen_x", opportunity.seenX); o.addProperty("seen_y", opportunity.seenY); o.addProperty("seen_z", opportunity.seenZ);
            o.addProperty("status", opportunity.status); o.addProperty("blocked_reason", opportunity.blockedReason); o.addProperty("required_tool", opportunity.requiredTool);
            o.addProperty("last_seen_game_time", opportunity.lastSeenGameTime); opportunities.add(o);
        }
        root.add("resource_opportunities", opportunities);
        return GSON.toJson(root);
    }

    /** @return true when a v1 file was accepted and should be rewritten as v2. */
    private static boolean load(String text) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("root_not_object");
            JsonObject root = parsed.getAsJsonObject();
            int version = root.has("version") ? root.get("version").getAsInt() : -1;
            if (version != 1 && version != VERSION) throw new IllegalArgumentException("version");
            if (version == VERSION) {
                String storedWorld = root.has("world_id") ? root.get("world_id").getAsString() : "";
                if (!worldId.equals(storedWorld)) throw new IllegalArgumentException("world_id_mismatch");
            }
            JsonArray structures = root.has("structures") ? root.getAsJsonArray("structures") : new JsonArray();
            JsonArray farms = root.has("farms") ? root.getAsJsonArray("farms") : new JsonArray();
            JsonArray opportunities = version == VERSION && root.has("resource_opportunities")
                    ? root.getAsJsonArray("resource_opportunities") : new JsonArray();
            if (structures.size() > MAX_STRUCTURES || farms.size() > MAX_FARMS || opportunities.size() > MAX_OPPORTUNITIES) throw new IllegalArgumentException("capacity");
            for (JsonElement element : structures) {
                JsonObject o = element.getAsJsonObject();
                String dim = o.get("dimension").getAsString(); String id = id(o.get("id").getAsString(), "structure");
                List<SnapshotCell> snapshot = new ArrayList<>();
                if (version == VERSION && o.has("snapshot")) for (JsonElement c0 : o.getAsJsonArray("snapshot")) {
                    JsonObject c = c0.getAsJsonObject(); snapshot.add(new SnapshotCell(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt(), c.get("block").getAsString()));
                    if (snapshot.size() > MAX_SNAPSHOT_CELLS) throw new IllegalArgumentException("snapshot_capacity");
                }
                Structure s = new Structure(id, o.get("kind").getAsString(), dim,
                        o.get("min_x").getAsInt(), o.get("min_y").getAsInt(), o.get("min_z").getAsInt(),
                        o.get("max_x").getAsInt(), o.get("max_y").getAsInt(), o.get("max_z").getAsInt(), List.copyOf(snapshot));
                if (s.minX > s.maxX || s.minY > s.maxY || s.minZ > s.maxZ) throw new IllegalArgumentException("bounds");
                STRUCTURES.put(scoped(dim, id), s);
            }
            for (JsonElement element : farms) {
                JsonObject o = element.getAsJsonObject(); int radius = o.get("radius").getAsInt();
                if (radius < 1 || radius > MAX_FARM_RADIUS) throw new IllegalArgumentException("farm_radius");
                String dim = o.get("dimension").getAsString(); String id = id(o.get("id").getAsString(), "farm");
                List<Cell> cells = new ArrayList<>();
                if (version == VERSION && o.has("cells")) for (JsonElement c0 : o.getAsJsonArray("cells")) {
                    JsonObject c = c0.getAsJsonObject(); cells.add(new Cell(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt()));
                    if (cells.size() > MAX_FARM_CELLS) throw new IllegalArgumentException("farm_cell_capacity");
                }
                Farm f = new Farm(id, dim, o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), radius, o.get("crop").getAsString(), List.copyOf(cells));
                Identifier cropId = Identifier.tryParse(f.cropId); Block crop = cropId == null ? null : Registries.BLOCK.getOptionalValue(cropId).orElse(null);
                if (crop == null || !FarmAction.isSupportedCrop(crop)) throw new IllegalArgumentException("farm_crop");
                FARMS.put(scoped(dim, id), f);
            }
            for (JsonElement element : opportunities) {
                JsonObject o = element.getAsJsonObject();
                ResourceOpportunity r = new ResourceOpportunity(
                        id(o.get("id").getAsString(), "opportunity"), o.get("dimension").getAsString(),
                        o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("block").getAsString(),
                        o.get("seen_x").getAsInt(), o.get("seen_y").getAsInt(), o.get("seen_z").getAsInt(),
                        o.get("status").getAsString(), o.get("blocked_reason").getAsString(), o.get("required_tool").getAsString(),
                        o.get("last_seen_game_time").getAsLong());
                Identifier blockId = Identifier.tryParse(r.blockId); Block block = blockId == null ? null : Registries.BLOCK.getOptionalValue(blockId).orElse(null);
                if (block == null || !OreScan.isOreBlock(block)) throw new IllegalArgumentException("opportunity_block");
                OPPORTUNITIES.put(scoped(r.dimension, r.id), r);
            }
            return version == 1;
        } catch (RuntimeException invalid) {
            STRUCTURES.clear(); FARMS.clear(); OPPORTUNITIES.clear(); INTEGRITY.clear();
            throw new IOException("semantic_registry_invalid", invalid);
        }
    }

    private static String id(String value, String fallback) {
        String id = value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("invalid_semantic_id");
        return id;
    }

    private static String scoped(String dimension, String id) { return dimension + "\u0000" + id; }
    private static String dimension(AIPlayerEntity bot) { return bot.getServerWorld().getRegistryKey().getValue().toString(); }
}
