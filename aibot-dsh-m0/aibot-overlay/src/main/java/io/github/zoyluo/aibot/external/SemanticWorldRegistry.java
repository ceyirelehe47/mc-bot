package io.github.zoyluo.aibot.external;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Small Body-domain semantic registry. It is world-local operational state, NOT Iris memory.
 * M1C-A deliberately supports protected structures and bounded farm regions only.
 */
public final class SemanticWorldRegistry {
    private static final int VERSION = 1;
    private static final int MAX_STRUCTURES = 32;
    private static final int MAX_FARMS = 32;
    private static final int MAX_FARM_RADIUS = 16;
    private static final int LIVE_SUMMARY_DISTANCE = 32;
    private static final Gson GSON = new Gson();
    private static final Map<String, Structure> STRUCTURES = new LinkedHashMap<>();
    private static final Map<String, Farm> FARMS = new LinkedHashMap<>();

    private static MinecraftServer server;
    private static Path file;
    private static ExecutorService writer;
    private static CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);
    private static volatile String persistenceFault = "";

    private SemanticWorldRegistry() {}

    public static void start(MinecraftServer minecraftServer, String botName) throws IOException {
        if (!minecraftServer.isOnThread()) throw new IllegalStateException("semantic_registry_start_off_server_thread");
        stopQuietly();
        server = minecraftServer;
        file = minecraftServer.getSavePath(WorldSavePath.ROOT)
                .resolve("aibot/external-semantics-" + botName.toLowerCase(Locale.ROOT) + ".json");
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aibot-semantic-world-writer");
            t.setDaemon(true);
            return t;
        });
        STRUCTURES.clear(); FARMS.clear(); persistenceFault = "";
        if (Files.exists(file)) load(Files.readString(file, StandardCharsets.UTF_8));
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
        server = null; file = null; writer = null; lastWrite = CompletableFuture.completedFuture(null);
        STRUCTURES.clear(); FARMS.clear();
    }

    public record Registration(String payload, CompletableFuture<Void> persisted) {}
    public record FarmSpec(String id, BlockPos center, int radius, Block crop, Item seed) {}

    public static Registration registerHome(AIPlayerEntity bot, String rawId, int radius, int below, int above) {
        requireReady(bot);
        String id = id(rawId, "home");
        if (!STRUCTURES.containsKey(id) && STRUCTURES.size() >= MAX_STRUCTURES) throw new IllegalStateException("structure_registry_full");
        BlockPos center = bot.getBlockPos();
        Structure structure = new Structure(id, "HOME", dimension(bot),
                center.getX() - radius, center.getY() - below, center.getZ() - radius,
                center.getX() + radius, center.getY() + above, center.getZ() + radius);
        STRUCTURES.put(id, structure);
        CompletableFuture<Void> persisted = persistAsync();
        JsonObject result = structureJson(bot, structure);
        result.addProperty("registered", true);
        result.addProperty("note", "protection_only_no_repair_blueprint");
        return new Registration(GSON.toJson(result), persisted);
    }

    public static Registration registerFarm(AIPlayerEntity bot, String rawId, int radius, String cropName) {
        requireReady(bot);
        String id = id(rawId, "farm");
        if (!FARMS.containsKey(id) && FARMS.size() >= MAX_FARMS) throw new IllegalStateException("farm_registry_full");
        int boundedRadius = Math.max(1, Math.min(MAX_FARM_RADIUS, radius));
        Block explicitCrop = cropName == null || cropName.isBlank() ? null : FarmAction.cropSpec(cropName).crop();
        FarmCandidate candidate = findFarmCandidate(bot, boundedRadius, explicitCrop)
                .orElseThrow(() -> new IllegalStateException("no_observed_farmland_or_supported_crop"));
        Block crop = explicitCrop != null ? explicitCrop : candidate.crop();
        if (crop == null || !FarmAction.isSupportedCrop(crop)) throw new IllegalStateException("farm_crop_unknown_register_with_crop");
        Farm farm = new Farm(id, dimension(bot), candidate.center().getX(), candidate.center().getY(), candidate.center().getZ(), boundedRadius,
                Registries.BLOCK.getId(crop).toString());
        FARMS.put(id, farm);
        CompletableFuture<Void> persisted = persistAsync();
        JsonObject result = farmJson(bot, farm);
        result.addProperty("registered", true);
        return new Registration(GSON.toJson(result), persisted);
    }

    public static Optional<FarmSpec> farm(AIPlayerEntity bot, String rawId) {
        requireReady(bot);
        Farm farm = FARMS.get(id(rawId, "farm"));
        if (farm == null || !farm.dimension.equals(dimension(bot))) return Optional.empty();
        Identifier identifier = Identifier.tryParse(farm.cropId);
        Block crop = identifier == null ? null : Registries.BLOCK.getOptionalValue(identifier).orElse(null);
        if (crop == null || !FarmAction.isSupportedCrop(crop)) return Optional.empty();
        return Optional.of(new FarmSpec(farm.id, new BlockPos(farm.x, farm.y, farm.z), farm.radius, crop, FarmAction.seedFor(crop)));
    }

    public static String protectionReason(AIPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null || server == null) return null;
        String dimension = dimension(bot);
        for (Structure structure : STRUCTURES.values()) {
            if (structure.dimension.equals(dimension) && structure.contains(pos)) return "protected_structure:" + structure.id;
        }
        for (Farm farm : FARMS.values()) {
            if (!farm.dimension.equals(dimension) || !farm.inHorizontalBounds(pos)) continue;
            ServerWorld world = bot.getServerWorld();
            if (world.getBlockState(pos).isOf(Blocks.FARMLAND)
                    || (world.getBlockState(pos.down()).isOf(Blocks.FARMLAND)
                    && FarmAction.isSupportedCrop(world.getBlockState(pos).getBlock()))) {
                return "registered_farm:" + farm.id;
            }
        }
        return null;
    }

    public static JsonObject observe(AIPlayerEntity bot) {
        requireReady(bot);
        JsonObject out = new JsonObject();
        out.addProperty("schema", "mc_semantic_world_v1");
        out.addProperty("persistence_fault", persistenceFault);
        JsonArray structures = new JsonArray();
        for (Structure structure : STRUCTURES.values()) if (structure.dimension.equals(dimension(bot))) structures.add(structureJson(bot, structure));
        out.add("structures", structures);
        JsonArray farms = new JsonArray();
        for (Farm farm : FARMS.values()) if (farm.dimension.equals(dimension(bot))) farms.add(farmJson(bot, farm));
        out.add("farms", farms);
        findFarmCandidate(bot, 8, null).ifPresent(candidate -> out.add("nearby_farm_candidate", candidateJson(bot, candidate, 8)));
        out.addProperty("destructive_policy", "registered_structure_and_farm_cells_fail_closed; safety_origin_can_override");
        out.addProperty("tree_policy", "only_logs_with_root+leaf+cluster_evidence_are_resource_candidates");
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
        return o;
    }

    private static JsonObject farmJson(AIPlayerEntity bot, Farm farm) {
        JsonObject o = new JsonObject();
        o.addProperty("id", farm.id); o.addProperty("crop", farm.cropId); o.addProperty("radius", farm.radius);
        JsonObject center = new JsonObject(); center.addProperty("x", farm.x); center.addProperty("y", farm.y); center.addProperty("z", farm.z); o.add("center", center);
        double distance = Math.sqrt(bot.getBlockPos().getSquaredDistance(new BlockPos(farm.x, farm.y, farm.z)));
        o.addProperty("distance", Math.round(distance * 10.0) / 10.0);
        if (distance <= LIVE_SUMMARY_DISTANCE) addFarmStats(o, bot, farm.x, farm.y, farm.z, farm.radius, farm.cropId);
        else o.addProperty("fresh", false);
        return o;
    }

    private static JsonObject candidateJson(AIPlayerEntity bot, FarmCandidate candidate, int radius) {
        JsonObject o = new JsonObject();
        o.addProperty("registered", false); o.addProperty("radius", radius);
        JsonObject center = new JsonObject(); center.addProperty("x", candidate.center().getX()); center.addProperty("y", candidate.center().getY()); center.addProperty("z", candidate.center().getZ()); o.add("center", center);
        String cropId = candidate.crop() == null ? "" : Registries.BLOCK.getId(candidate.crop()).toString();
        o.addProperty("crop", cropId);
        if (!cropId.isBlank()) addFarmStats(o, bot, candidate.center().getX(), candidate.center().getY(), candidate.center().getZ(), radius, cropId);
        return o;
    }

    private static void addFarmStats(JsonObject o, AIPlayerEntity bot, int x, int y, int z, int radius, String cropId) {
        Identifier identifier = Identifier.tryParse(cropId);
        Block crop = identifier == null ? null : Registries.BLOCK.getOptionalValue(identifier).orElse(null);
        if (crop == null || !FarmAction.isSupportedCrop(crop)) { o.addProperty("fresh", false); return; }
        ServerWorld world = bot.getServerWorld();
        int farmland = 0, mature = 0, immature = 0, empty = 0, occupiedOther = 0;
        for (BlockPos ground : BlockPos.iterate(new BlockPos(x - radius, y - 1, z - radius), new BlockPos(x + radius, y + 1, z + radius))) {
            if (!world.getBlockState(ground).isOf(Blocks.FARMLAND)) continue;
            farmland++;
            BlockPos cropPos = ground.up();
            if (world.getBlockState(cropPos).isOf(crop)) {
                if (FarmAction.isMature(world, cropPos)) mature++; else immature++;
            } else if (world.getBlockState(cropPos).isAir()) empty++;
            else occupiedOther++;
        }
        o.addProperty("fresh", true); o.addProperty("farmland", farmland); o.addProperty("mature", mature);
        o.addProperty("immature", immature); o.addProperty("empty_farmland", empty); o.addProperty("occupied_other", occupiedOther);
        o.addProperty("needs_tending", mature > 0 || empty > 0);
    }

    private record FarmCandidate(BlockPos center, Block crop) {}

    private static Optional<FarmCandidate> findFarmCandidate(AIPlayerEntity bot, int radius, Block desiredCrop) {
        BlockPos origin = bot.getBlockPos();
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos raw : BlockPos.iterate(origin.add(-radius, -2, -radius), origin.add(radius, 2, radius))) {
            BlockPos pos = raw.toImmutable();
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)
                    && !ObservableWorldQuery.canObserveBlock(bot, pos.up())) continue;
            Block block = bot.getServerWorld().getBlockState(pos).getBlock();
            if (FarmAction.isSupportedCrop(block) || block == Blocks.FARMLAND) positions.add(pos);
        }
        positions.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(origin)));
        for (BlockPos pos : positions) {
            Block block = bot.getServerWorld().getBlockState(pos).getBlock();
            if (FarmAction.isSupportedCrop(block)) {
                if (desiredCrop != null && block != desiredCrop) continue;
                BlockPos ground = bot.getServerWorld().getBlockState(pos.down()).isOf(Blocks.FARMLAND) ? pos.down() : pos;
                return Optional.of(new FarmCandidate(ground.toImmutable(), block));
            }
            if (block == Blocks.FARMLAND) {
                Block above = bot.getServerWorld().getBlockState(pos.up()).getBlock();
                if (desiredCrop != null) return Optional.of(new FarmCandidate(pos, desiredCrop));
                if (FarmAction.isSupportedCrop(above)) return Optional.of(new FarmCandidate(pos, above));
            }
        }
        return Optional.empty();
    }

    private static void requireReady(AIPlayerEntity bot) {
        if (server == null || file == null || writer == null) throw new IllegalStateException("semantic_registry_not_started");
        if (!server.isOnThread()) throw new IllegalStateException("semantic_registry_off_server_thread");
        if (bot == null) throw new IllegalArgumentException("bot_required");
        if (!persistenceFault.isBlank()) throw new IllegalStateException("semantic_registry_failed_closed:" + persistenceFault);
    }

    private static CompletableFuture<Void> persistAsync() {
        String snapshot = encode(); Path target = file;
        CompletableFuture<Void> next = lastWrite.handle((ignored, priorFailure) -> null).thenRunAsync(() -> {
            try { writeAtomic(target, snapshot); }
            catch (IOException e) { persistenceFault = "write_failed:" + e.getClass().getSimpleName(); throw new CompletionException(e); }
        }, writer);
        lastWrite = next;
        return next;
    }

    private static void writeAtomic(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String encode() {
        JsonObject root = new JsonObject(); root.addProperty("version", VERSION);
        JsonArray structures = new JsonArray();
        for (Structure s : STRUCTURES.values()) {
            JsonObject o = new JsonObject(); o.addProperty("id", s.id); o.addProperty("kind", s.kind); o.addProperty("dimension", s.dimension);
            o.addProperty("min_x", s.minX); o.addProperty("min_y", s.minY); o.addProperty("min_z", s.minZ);
            o.addProperty("max_x", s.maxX); o.addProperty("max_y", s.maxY); o.addProperty("max_z", s.maxZ); structures.add(o);
        }
        root.add("structures", structures);
        JsonArray farms = new JsonArray();
        for (Farm f : FARMS.values()) {
            JsonObject o = new JsonObject(); o.addProperty("id", f.id); o.addProperty("dimension", f.dimension); o.addProperty("x", f.x); o.addProperty("y", f.y); o.addProperty("z", f.z);
            o.addProperty("radius", f.radius); o.addProperty("crop", f.cropId); farms.add(o);
        }
        root.add("farms", farms); return GSON.toJson(root);
    }

    private static void load(String text) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("root_not_object");
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("version") || root.get("version").getAsInt() != VERSION) throw new IllegalArgumentException("version");
            JsonArray structures = root.has("structures") ? root.getAsJsonArray("structures") : new JsonArray();
            JsonArray farms = root.has("farms") ? root.getAsJsonArray("farms") : new JsonArray();
            if (structures.size() > MAX_STRUCTURES || farms.size() > MAX_FARMS) throw new IllegalArgumentException("capacity");
            for (JsonElement element : structures) {
                JsonObject o = element.getAsJsonObject();
                Structure s = new Structure(id(o.get("id").getAsString(), "structure"), o.get("kind").getAsString(), o.get("dimension").getAsString(),
                        o.get("min_x").getAsInt(), o.get("min_y").getAsInt(), o.get("min_z").getAsInt(),
                        o.get("max_x").getAsInt(), o.get("max_y").getAsInt(), o.get("max_z").getAsInt());
                if (s.minX > s.maxX || s.minY > s.maxY || s.minZ > s.maxZ) throw new IllegalArgumentException("bounds");
                STRUCTURES.put(s.id, s);
            }
            for (JsonElement element : farms) {
                JsonObject o = element.getAsJsonObject(); int radius = o.get("radius").getAsInt();
                if (radius < 1 || radius > MAX_FARM_RADIUS) throw new IllegalArgumentException("farm_radius");
                Farm f = new Farm(id(o.get("id").getAsString(), "farm"), o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), radius, o.get("crop").getAsString());
                Identifier cropId = Identifier.tryParse(f.cropId); Block crop = cropId == null ? null : Registries.BLOCK.getOptionalValue(cropId).orElse(null);
                if (crop == null || !FarmAction.isSupportedCrop(crop)) throw new IllegalArgumentException("farm_crop");
                FARMS.put(f.id, f);
            }
        } catch (RuntimeException invalid) {
            STRUCTURES.clear(); FARMS.clear();
            throw new IOException("semantic_registry_invalid", invalid);
        }
    }

    private static String id(String value, String fallback) {
        String id = value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("invalid_semantic_id");
        return id;
    }

    private static String dimension(AIPlayerEntity bot) { return bot.getServerWorld().getRegistryKey().getValue().toString(); }

    private record Structure(String id, String kind, String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(BlockPos pos) { return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY && pos.getZ() >= minZ && pos.getZ() <= maxZ; }
    }
    private record Farm(String id, String dimension, int x, int y, int z, int radius, String cropId) {
        boolean inHorizontalBounds(BlockPos pos) { return Math.abs(pos.getX() - x) <= radius && Math.abs(pos.getZ() - z) <= radius && Math.abs(pos.getY() - y) <= 2; }
    }
}
