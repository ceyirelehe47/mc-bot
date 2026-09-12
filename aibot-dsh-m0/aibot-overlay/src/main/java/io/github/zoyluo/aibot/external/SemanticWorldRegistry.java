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
    private static final int VERSION = 3;
    private static final int MAX_STRUCTURES = 64;
    private static final int MAX_FARMS = 64;
    private static final int MAX_FARM_RADIUS = 16;
    private static final int MAX_FARM_CELLS = 512;
    private static final int MAX_OPPORTUNITIES = 256;
    private static final int MAX_SNAPSHOT_CELLS = 4096;
    private static final int LIVE_SUMMARY_DISTANCE = 32;
    private static final int INTEGRITY_CACHE_TICKS = 20;
    private static final int REVALIDATE_MOVE_DISTANCE = 6;
    private static final long REVALIDATE_COOLDOWN_TICKS = 2400L;
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
                                  BlockPos seenFrom, String status, String blockedReason,
                                  long stateSinceGameTime, BlockPos stateAt, int pickupBaseline) {}
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
                                       long lastSeenGameTime,
                                       long stateSinceGameTime, int stateX, int stateY, int stateZ,
                                       int pickupBaseline) {
        BlockPos pos() { return new BlockPos(x, y, z); }
        BlockPos seenFrom() { return new BlockPos(seenX, seenY, seenZ); }
        BlockPos stateAt() { return new BlockPos(stateX, stateY, stateZ); }

        ResourceOpportunity observedAt(long now) {
            return new ResourceOpportunity(id, dimension, x, y, z, blockId, seenX, seenY, seenZ,
                    status, blockedReason, requiredTool, now, stateSinceGameTime, stateX, stateY, stateZ,
                    pickupBaseline);
        }
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

    /**
     * R2.1 reserved-body farm mutation gate: for the reserved external body, every direct farm
     * domain mutation (till/plant/harvest) must target a cell inside a REGISTERED exact connected
     * farm mask — either the farmland cell itself or the crop cell standing on one. Anything else
     * returns a typed denial and the caller must abort without touching the world. Legacy
     * non-reserved bodies keep the upstream behavior (null = unrestricted) so R2.1 cannot change
     * the internal/legacy mode (R21-11).
     */
    public static String farmMutationDenial(AIPlayerEntity bot, BlockPos cell, String kind) {
        if (bot == null || cell == null) return "farm_mutation_outside_registered_mask:" + kind + ":invalid_target";
        if (!ExternalBodyAccess.reserved(bot) || server == null) return null; // legacy mode: upstream behavior
        String dim = dimension(bot);
        for (Farm farm : FARMS.values()) {
            if (!farm.dimension.equals(dim)) continue;
            // crop cell (one above registered farmland) or the farmland cell itself
            if (farm.contains(cell) || farm.contains(cell.down())) return null;
        }
        return "farm_mutation_outside_registered_mask:" + kind + "@" + cell.getX() + "," + cell.getY() + "," + cell.getZ();
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
        List<ResourceOpportunity> staled = new ArrayList<>();
        for (var entry : OPPORTUNITIES.entrySet()) {
            ResourceOpportunity opportunity = entry.getValue();
            if (opportunity.dimension.equals(dim) && opportunity.x == pos.getX()
                    && opportunity.y == pos.getY() && opportunity.z == pos.getZ()
                    && (!ore || !opportunity.blockId.equals(blockId))
                    // R2.1: a pending-pickup opportunity is a recovery obligation (the ore was
                    // proven broken). A different block now occupying the cell must not delete it;
                    // only recovery or a stale terminal resolution may end it.
                    && !"MINED_PENDING_PICKUP".equals(opportunity.status)) {
                remove.add(entry.getKey());
                staled.add(opportunity);
            }
        }
        boolean dirty = false;
        for (String key : remove) { OPPORTUNITIES.remove(key); dirty = true; }
        // R2.1 6.3: an opportunity whose cell now holds something else was consumed by the world,
        // not by this bot. Terminalize with a typed tombstone event instead of silently dropping
        // the entry, so the loss is visible and can never read as a successful collection.
        for (ResourceOpportunity stale : staled) {
            ExternalBodyRuntime.resourceOpportunityStale(bot, stale.id, stale.blockId, stale.pos(),
                    "externally_consumed_cell_replaced_with:" + blockId);
        }
        if (!ore) { if (dirty) persistAsync(); return; }

        // `id` is an object/incarnation identity, not a forever-coordinate identity.
        // Re-observing the same still-active physical opportunity keeps the same id; once that
        // incarnation terminalizes and leaves OPPORTUNITIES, a later same-cell/same-block object
        // receives a new id so historical terminal receipts cannot poison it.
        ResourceOpportunity prior = findActiveOpportunityAt(dim, pos, blockId);
        String id = prior == null ? newOpportunityIncarnationId(dim, pos, blockId) : prior.id;
        String key = scoped(dim, id);
        BlockPos seenFrom = prior == null ? bot.getBlockPos() : prior.seenFrom();
        long now = bot.getServerWorld().getTime();
        ResourceOpportunity next;
        boolean reactivated = false;
        if (prior != null && "UNREACHABLE".equals(prior.status)) {
            // R2.1: unchanged observation keeps UNREACHABLE (no event storm). Bounded deterministic
            // revalidation — the bot moved meaningfully or enough game time passed — may restore
            // ACTIONABLE (single event, never repeatedly while conditions hold).
            if (opportunityRevalidationReady(bot, prior)) {
                boolean actionable = ToolTier.canHarvestWithInventory(bot, state);
                String status = actionable ? "ACTIONABLE" : "BLOCKED";
                String reason = actionable ? "" : "insufficient_tool";
                next = new ResourceOpportunity(id, dim, pos.getX(), pos.getY(), pos.getZ(), blockId,
                        seenFrom.getX(), seenFrom.getY(), seenFrom.getZ(), status, reason,
                        ToolTier.requiredPickaxeItemId(state.getBlock()), now, now,
                        prior.stateX, prior.stateY, prior.stateZ, prior.pickupBaseline);
                reactivated = actionable;
            } else {
                next = prior.observedAt(now); // keep UNREACHABLE, only refresh last_seen
            }
        } else if (prior != null && "MINED_PENDING_PICKUP".equals(prior.status)) {
            next = prior.observedAt(now); // recovery obligation outlives re-observation
        } else {
            boolean actionable = ToolTier.canHarvestWithInventory(bot, state);
            String status = actionable ? "ACTIONABLE" : "BLOCKED";
            String reason = actionable ? "" : "insufficient_tool";
            next = new ResourceOpportunity(id, dim, pos.getX(), pos.getY(), pos.getZ(), blockId,
                    seenFrom.getX(), seenFrom.getY(), seenFrom.getZ(), status, reason,
                    ToolTier.requiredPickaxeItemId(state.getBlock()), now, now,
                    bot.getBlockPos().getX(), bot.getBlockPos().getY(), bot.getBlockPos().getZ(), -1);
        }
        if (prior == null) {
            evictOpportunityIfNeeded(); OPPORTUNITIES.put(key, next); dirty = true;
            if ("ACTIONABLE".equals(next.status)) {
                ExternalBodyRuntime.resourceOpportunityActionable(bot, next.id, next.blockId, next.pos(), next.seenFrom());
            }
        } else {
            OPPORTUNITIES.put(key, next);
            dirty = !prior.status.equals(next.status) || !prior.blockId.equals(next.blockId)
                    || !prior.requiredTool.equals(next.requiredTool);
            if (reactivated || ("BLOCKED".equals(prior.status) && "ACTIONABLE".equals(next.status))) {
                ExternalBodyRuntime.resourceOpportunityActionable(bot, next.id, next.blockId, next.pos(), next.seenFrom());
            }
        }
        if (dirty) persistAsync();
    }

    /**
     * R2.1 bounded revalidation gate for UNREACHABLE: re-arm only when the bot has moved
     * meaningfully relative to where the no-work-pose verdict was recorded, or after a bounded
     * game-time cooldown. Standing still in front of the same geometry never re-arms it, so the
     * same unchanged observation can never storm ACTIONABLE transitions or wake DSH repeatedly.
     */
    public static boolean opportunityRevalidationReady(AIPlayerEntity bot, OpportunitySpec opportunity) {
        long now = bot.getServerWorld().getTime();
        int dx = bot.getBlockPos().getX() - opportunity.stateAt().getX();
        int dz = bot.getBlockPos().getZ() - opportunity.stateAt().getZ();
        return dx * dx + dz * dz > REVALIDATE_MOVE_DISTANCE * REVALIDATE_MOVE_DISTANCE
                || now - opportunity.stateSinceGameTime() > REVALIDATE_COOLDOWN_TICKS;
    }

    private static boolean opportunityRevalidationReady(AIPlayerEntity bot, ResourceOpportunity prior) {
        long now = bot.getServerWorld().getTime();
        int dx = bot.getBlockPos().getX() - prior.stateX;
        int dz = bot.getBlockPos().getZ() - prior.stateZ;
        return dx * dx + dz * dz > REVALIDATE_MOVE_DISTANCE * REVALIDATE_MOVE_DISTANCE
                || now - prior.stateSinceGameTime > REVALIDATE_COOLDOWN_TICKS;
    }

    // ---- MC-2A0 read-only evidence accessors (cognition view drill-down) ----
    // 只读快照拷贝,registry 仍是唯一 authority;认知视图绝不在此之上做第二次可变真相。

    public record SnapshotCellEvidence(int x, int y, int z, String blockId) {}
    public record StructureEvidence(String id, String kind, String dimension,
                                    int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                    List<SnapshotCellEvidence> cells) {}
    public record CellEvidence(int x, int y, int z) {}
    public record FarmEvidence(String id, String cropId, int x, int y, int z, int radius,
                               List<CellEvidence> cells) {}

    /** 当前维度全部已注册结构的不可变快照(含 baseline 逐格期望),按 id 稳定排序。 */
    public static List<StructureEvidence> structureEvidences(AIPlayerEntity bot) {
        requireReady(bot);
        String dim = dimension(bot);
        List<StructureEvidence> out = new ArrayList<>();
        for (Structure structure : STRUCTURES.values()) if (structure.dimension.equals(dim))
            out.add(new StructureEvidence(structure.id, structure.kind, structure.dimension,
                    structure.minX, structure.minY, structure.minZ, structure.maxX, structure.maxY, structure.maxZ,
                    structure.snapshot.stream().map(cell -> new SnapshotCellEvidence(cell.x(), cell.y(), cell.z(), cell.blockId())).toList()));
        out.sort(Comparator.comparing(StructureEvidence::id));
        return out;
    }

    /** 当前维度全部已注册农田的不可变快照(exact connected cell mask),按 id 稳定排序。 */
    public static List<FarmEvidence> farmEvidences(AIPlayerEntity bot) {
        requireReady(bot);
        String dim = dimension(bot);
        List<FarmEvidence> out = new ArrayList<>();
        for (Farm farm : FARMS.values()) if (farm.dimension.equals(dim))
            out.add(new FarmEvidence(farm.id, farm.cropId, farm.x, farm.y, farm.z, farm.radius,
                    farm.cells.stream().map(cell -> new CellEvidence(cell.x(), cell.y(), cell.z())).toList()));
        out.sort(Comparator.comparing(FarmEvidence::id));
        return out;
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
                opportunity.seenFrom(), opportunity.status, opportunity.blockedReason,
                opportunity.stateSinceGameTime, opportunity.stateAt(), opportunity.pickupBaseline));
    }

    public static boolean markOpportunityConsumed(AIPlayerEntity bot, String rawId) {
        if (server == null || bot == null) return false;
        String key = scoped(dimension(bot), id(rawId, "opportunity"));
        ResourceOpportunity prior=OPPORTUNITIES.get(key);
        if(prior==null) return false;
        // Inventory delta was already proven by the caller.  Cross the synchronous BridgeJournal
        // fsync boundary BEFORE removing the semantic entry or reporting physical Task success.
        if(!ExternalBodyRuntime.resourceOpportunityConsumed(bot,prior.id,prior.blockId,prior.pos()))
            return false;
        OPPORTUNITIES.remove(key);
        persistAsync();
        return true;
    }

    /**
     * Startup repair for the only legitimate cross-store crash window:
     * BridgeJournal terminal receipt durable, semantic async snapshot still old.
     * Both consumed and stale receipts mean the active opportunity must not resurrect.
     */
    public static int reconcileOpportunityTerminalReceipts(BridgeJournal journal) {
        if(server==null || journal==null) return 0;
        if(!server.isOnThread()) throw new IllegalStateException("semantic_registry_reconcile_off_server_thread");
        Set<String> terminal=new HashSet<>();
        for(BridgeJournal.Frame frame:journal.replay()) {
            Map<String,String> fields=frame.fields();
            String kind=fields.get("kind");
            if(!"resource_opportunity_consumed".equals(kind)
                    && !"resource_opportunity_stale".equals(kind)) continue;
            if(!worldId.equals(fields.get("world_id"))) continue;
            String dim=fields.get("dimension"), opportunityId=fields.get("opportunity_id");
            if(dim==null || opportunityId==null) continue;
            terminal.add(scoped(dim,opportunityId));
        }
        int removed=0;
        for(String key:terminal) if(OPPORTUNITIES.remove(key)!=null) removed++;
        if(removed>0) {
            try { persistAsync().join(); }
            catch(RuntimeException failure) {
                persistenceFault="terminal_receipt_reconcile_failed:"+failure.getClass().getSimpleName();
                throw new IllegalStateException("semantic_registry_failed_closed:"+persistenceFault,failure);
            }
        }
        return removed;
    }

    /**
     * R2.1: proven absence of a legal work pose demotes the opportunity to UNREACHABLE (typed
     * reason, e.g. no_reachable_work_pose) instead of leaving a zombie ACTIONABLE entry that
     * resident perception keeps refreshing. Not terminal: bounded revalidation can restore
     * ACTIONABLE once geometry or time changes. No event — this must not wake DSH.
     */
    public static void markOpportunityUnreachable(AIPlayerEntity bot, String rawId, String reason) {
        if (server == null || bot == null) return;
        String key = scoped(dimension(bot), id(rawId, "opportunity"));
        ResourceOpportunity prior = OPPORTUNITIES.get(key);
        if (prior == null) return;
        long now = bot.getServerWorld().getTime();
        BlockPos feet = bot.getBlockPos();
        OPPORTUNITIES.put(key, new ResourceOpportunity(prior.id, prior.dimension, prior.x, prior.y, prior.z,
                prior.blockId, prior.seenX, prior.seenY, prior.seenZ, "UNREACHABLE",
                reason == null || reason.isBlank() ? "no_reachable_work_pose" : reason,
                prior.requiredTool, prior.lastSeenGameTime, now, feet.getX(), feet.getY(), feet.getZ(),
                prior.pickupBaseline));
        persistAsync();
    }

    /**
     * R2.1: the ore was proven broken but the drop is not yet in inventory. The opportunity stays
     * in the registry as MINED_PENDING_PICKUP (persisted, restart-safe) with its position/block
     * as the recovery anchor. Only an inventory-delta postcondition may consume it afterwards.
     */
    public static void markOpportunityPendingPickup(AIPlayerEntity bot, String rawId, int pickupBaseline) {
        if (server == null || bot == null) return;
        String key = scoped(dimension(bot), id(rawId, "opportunity"));
        ResourceOpportunity prior = OPPORTUNITIES.get(key);
        if (prior == null) return;
        long now = bot.getServerWorld().getTime();
        BlockPos feet = bot.getBlockPos();
        // pickupBaseline is the accepted-inventory count measured when the ore break happened, so a
        // later recovery can prove the delta even when the drop was already picked up by the time
        // recovery starts (count measured at recovery start would hide that gain).
        // Negative values are the explicit "unknown baseline" sentinel (legacy registry entries):
        // the recovery then falls back to a fresh count, which can only under-claim.
        OPPORTUNITIES.put(key, new ResourceOpportunity(prior.id, prior.dimension, prior.x, prior.y, prior.z,
                prior.blockId, prior.seenX, prior.seenY, prior.seenZ, "MINED_PENDING_PICKUP",
                "pickup_recovery_pending", prior.requiredTool, prior.lastSeenGameTime, now,
                feet.getX(), feet.getY(), feet.getZ(), pickupBaseline));
        persistAsync();
    }

    /**
     * R2.1 terminal: the target vanished without the resource ever being proven into this bot's
     * inventory (externally consumed, or a pending pickup whose drop despawned). Emits a typed
     * tombstone event so the loss is visible and never reads as success.
     */
    public static void markOpportunityStale(AIPlayerEntity bot, String rawId, String reason) {
        if (server == null || bot == null) return;
        String key = scoped(dimension(bot), id(rawId, "opportunity"));
        ResourceOpportunity prior = OPPORTUNITIES.remove(key);
        if (prior == null) return;
        persistAsync();
        ExternalBodyRuntime.resourceOpportunityStale(bot, prior.id, prior.blockId, prior.pos(),
                reason == null || reason.isBlank() ? "externally_consumed_or_stale" : reason);
    }

    /**
     * R2.1: re-arm a revalidation-eligible UNREACHABLE opportunity after re-checking the tool
     * capability; returns the refreshed spec the caller should execute. Pushes the ordinary
     * actionable event exactly once per transition.
     */
    public static Optional<OpportunitySpec> reactivateOpportunity(AIPlayerEntity bot, String rawId) {
        requireReady(bot);
        String key = scoped(dimension(bot), id(rawId, "opportunity"));
        ResourceOpportunity prior = OPPORTUNITIES.get(key);
        if (prior == null || !"UNREACHABLE".equals(prior.status)) return Optional.empty();
        long now = bot.getServerWorld().getTime();
        BlockPos feet = bot.getBlockPos();
        boolean actionable = ToolTier.canHarvestWithInventory(bot, blockOf(prior.blockId).getDefaultState());
        String status = actionable ? "ACTIONABLE" : "BLOCKED";
        String reason = actionable ? "" : "insufficient_tool";
        ResourceOpportunity next = new ResourceOpportunity(prior.id, prior.dimension, prior.x, prior.y, prior.z,
                prior.blockId, prior.seenX, prior.seenY, prior.seenZ, status, reason,
                ToolTier.requiredPickaxeItemId(blockOf(prior.blockId)), prior.lastSeenGameTime,
                now, feet.getX(), feet.getY(), feet.getZ(), prior.pickupBaseline);
        OPPORTUNITIES.put(key, next);
        persistAsync();
        if (actionable) {
            ExternalBodyRuntime.resourceOpportunityActionable(bot, next.id, next.blockId, next.pos(), next.seenFrom());
        }
        return Optional.of(new OpportunitySpec(next.id, next.dimension, next.pos(), blockOf(next.blockId),
                next.seenFrom(), next.status, next.blockedReason, next.stateSinceGameTime, next.stateAt(),
                next.pickupBaseline));
    }

    private static Block blockOf(String blockId) {
        Identifier identifier = Identifier.tryParse(blockId);
        return identifier == null ? null : Registries.BLOCK.getOptionalValue(identifier).orElse(null);
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
            if (HomeBlockEquivalence.equivalent(expected, actual.getBlock())) continue; // R2.1 natural drift
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

    /**
     * MC-2A0.1F omniscient variant: structures carry authoritative CURRENT integrity, which
     * requires scanning remote baseline cells (world.getBlockState) with no observability
     * proof. Reserved for EXPLICIT operations (register/capture receipts, debug, direct test
     * helpers) — never the automatic tick/observation/cognition refresh path (AUTO-OBS-1..3).
     */
    public static JsonObject observe(AIPlayerEntity bot) {
        return observe(bot, true);
    }

    /**
     * MC-2A0.1F bounded variant for automatic paths (BridgeKernel.tick → observeJson /
     * refreshCaches / cognitiveSnapshot / CognitiveViewBuilder). Identical durable content
     * (world/dimension identity, structure descriptors + registered bounds + captured
     * baseline count, observable farm evidence, opportunity registry facts) but structure
     * cards carry NO current-integrity fields: building the periodic view must never trigger
     * a remote baseline scan (COG-AQ-1/4). Current integrity may only resurface through the
     * legal proof-before-read verification in StructureKnowledge.
     */
    public static JsonObject observeBounded(AIPlayerEntity bot) {
        return observe(bot, false);
    }

    private static JsonObject observe(AIPlayerEntity bot, boolean includeCurrentIntegrity) {
        requireReady(bot);
        refreshOpportunityCapabilities(bot);
        JsonObject out = new JsonObject();
        out.addProperty("schema", includeCurrentIntegrity
                ? "mc_spatial_semantics_v2" : "mc_spatial_semantics_v2_bounded");
        out.addProperty("world_id", worldId);
        out.addProperty("dimension", dimension(bot));
        out.addProperty("persistence_fault", persistenceFault);

        JsonArray structures = new JsonArray();
        for (Structure structure : STRUCTURES.values()) if (structure.dimension.equals(dimension(bot)))
            structures.add(includeCurrentIntegrity ? structureJson(bot, structure)
                    : structureDescriptorJson(bot, structure));
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

    /**
     * Durable structure descriptor (AUTO-OBS-2): identity/role/bounds/captured-baseline facts
     * that stay provable with zero current-world reads. This is the only structure form the
     * automatic observation path may emit.
     */
    private static JsonObject structureDescriptorJson(AIPlayerEntity bot, Structure structure) {
        JsonObject o = new JsonObject();
        o.addProperty("id", structure.id); o.addProperty("kind", structure.kind); o.addProperty("protected", true);
        o.addProperty("inside", structure.contains(bot.getBlockPos()));
        JsonObject bounds = new JsonObject();
        bounds.addProperty("min_x", structure.minX); bounds.addProperty("min_y", structure.minY); bounds.addProperty("min_z", structure.minZ);
        bounds.addProperty("max_x", structure.maxX); bounds.addProperty("max_y", structure.maxY); bounds.addProperty("max_z", structure.maxZ);
        o.add("bounds", bounds);
        o.addProperty("snapshot_cells", structure.snapshot.size());
        return o;
    }

    /** Omniscient structure card: durable descriptor + authoritative current integrity (explicit paths only). */
    private static JsonObject structureJson(AIPlayerEntity bot, Structure structure) {
        JsonObject o = structureDescriptorJson(bot, structure);
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

    /**
     * MC-2A0.1F test/live-only instrumentation (AUTO-OBS gate evidence, zero production
     * output): counts full baseline scans performed by integrity() after an INTEGRITY-cache
     * miss — every one of those scans reads remote block states without an observability
     * proof. After the bounded-observation split this may only grow on EXPLICIT omniscient
     * calls (register/capture receipts, debug, direct helpers); the automatic tick/observe/
     * view refresh path must keep it at 0 (mc2a01fAutomaticViewNeverTriggersRemoteStructureIntegrityRead).
     */
    public static final java.util.concurrent.atomic.AtomicLong INTEGRITY_RAW_READ_SCANS = new java.util.concurrent.atomic.AtomicLong();

    private static Integrity integrity(AIPlayerEntity bot, Structure structure) {
        String key = scoped(structure.dimension, structure.id);
        int tick = bot.getServer().getTicks();
        CachedIntegrity cached = INTEGRITY.get(key);
        if (cached != null && tick >= cached.tick && tick - cached.tick < INTEGRITY_CACHE_TICKS) return cached.value;
        INTEGRITY_RAW_READ_SCANS.incrementAndGet();
        int matched = 0, missing = 0, wrong = 0;
        ServerWorld world = bot.getServerWorld();
        for (SnapshotCell cell : structure.snapshot) {
            Identifier id = Identifier.tryParse(cell.blockId);
            Block expected = id == null ? null : Registries.BLOCK.getOptionalValue(id).orElse(null);
            if (expected == null) { wrong++; continue; }
            BlockState actual = world.getBlockState(cell.pos());
            if (actual.isOf(expected)) matched++;
            else if (HomeBlockEquivalence.equivalent(expected, actual.getBlock())) matched++; // R2.1 natural drift
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
            // R2.1: UNREACHABLE is geometry-proven and MINED_PENDING_PICKUP is a recovery
            // obligation; neither is a tool-capability state, so the tool re-check must not
            // silently overwrite them.
            if ("UNREACHABLE".equals(opportunity.status) || "MINED_PENDING_PICKUP".equals(opportunity.status)) continue;
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
                    nextStatus, nextReason, ToolTier.requiredPickaxeItemId(block), opportunity.lastSeenGameTime,
                    opportunity.stateSinceGameTime, opportunity.stateX, opportunity.stateY, opportunity.stateZ,
                    opportunity.pickupBaseline);
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
                    // A MINED_PENDING_PICKUP entry is a recovery obligation, never eviction bait:
                    // only ordinary (re-derivable from re-observation) entries age out.
                    .filter(entry -> !"MINED_PENDING_PICKUP".equals(entry.getValue().status))
                    .min(Comparator.comparingLong(entry -> entry.getValue().lastSeenGameTime))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest == null) break;
            OPPORTUNITIES.remove(oldest);
        }
    }

    private static ResourceOpportunity findActiveOpportunityAt(String dimension, BlockPos pos, String blockId) {
        ResourceOpportunity found = null;
        for (ResourceOpportunity opportunity : OPPORTUNITIES.values()) {
            if (!opportunity.dimension.equals(dimension)
                    || opportunity.x != pos.getX()
                    || opportunity.y != pos.getY()
                    || opportunity.z != pos.getZ()
                    || !opportunity.blockId.equals(blockId)) continue;
            if (found != null && !found.id.equals(opportunity.id)) {
                // Two live incarnations cannot occupy the same exact block cell. Treat a corrupt
                // semantic snapshot as an authority failure rather than guessing which id wins.
                throw new IllegalStateException("duplicate_active_opportunity_incarnation");
            }
            found = opportunity;
        }
        return found;
    }

    private static String newOpportunityIncarnationId(String dimension, BlockPos pos, String blockId) {
        // Keep a short deterministic location prefix for diagnostics, but the random suffix is the
        // incarnation boundary. The complete id stays inside the existing 48-char semantic-id cap
        // and is persisted in the v1/v2/v3 semantic snapshot exactly like legacy ids.
        String material = worldId + "\n" + dimension + "\n" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\n" + blockId;
        String location = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "").substring(0, 12);
        String incarnation = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "ore_" + location + "_" + incarnation;
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
            o.addProperty("last_seen_game_time", opportunity.lastSeenGameTime);
            o.addProperty("state_since_game_time", opportunity.stateSinceGameTime);
            o.addProperty("state_x", opportunity.stateX); o.addProperty("state_y", opportunity.stateY); o.addProperty("state_z", opportunity.stateZ);
            o.addProperty("pickup_baseline", opportunity.pickupBaseline);
            opportunities.add(o);
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
            if (version != 1 && version != 2 && version != VERSION) throw new IllegalArgumentException("version");
            if (version == VERSION) {
                String storedWorld = root.has("world_id") ? root.get("world_id").getAsString() : "";
                if (!worldId.equals(storedWorld)) throw new IllegalArgumentException("world_id_mismatch");
            }
            JsonArray structures = root.has("structures") ? root.getAsJsonArray("structures") : new JsonArray();
            JsonArray farms = root.has("farms") ? root.getAsJsonArray("farms") : new JsonArray();
            JsonArray opportunities = version >= 2 && root.has("resource_opportunities")
                    ? root.getAsJsonArray("resource_opportunities") : new JsonArray();
            if (structures.size() > MAX_STRUCTURES || farms.size() > MAX_FARMS || opportunities.size() > MAX_OPPORTUNITIES) throw new IllegalArgumentException("capacity");
            for (JsonElement element : structures) {
                JsonObject o = element.getAsJsonObject();
                String dim = o.get("dimension").getAsString(); String id = id(o.get("id").getAsString(), "structure");
                List<SnapshotCell> snapshot = new ArrayList<>();
                if (version >= 2 && o.has("snapshot")) for (JsonElement c0 : o.getAsJsonArray("snapshot")) {
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
                if (version >= 2 && o.has("cells")) for (JsonElement c0 : o.getAsJsonArray("cells")) {
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
                        o.get("last_seen_game_time").getAsLong(),
                        version >= VERSION && o.has("state_since_game_time") ? o.get("state_since_game_time").getAsLong() : 0L,
                        version >= VERSION && o.has("state_x") ? o.get("state_x").getAsInt() : o.get("seen_x").getAsInt(),
                        version >= VERSION && o.has("state_y") ? o.get("state_y").getAsInt() : o.get("seen_y").getAsInt(),
                        version >= VERSION && o.has("state_z") ? o.get("state_z").getAsInt() : o.get("seen_z").getAsInt(),
                        o.has("pickup_baseline") ? o.get("pickup_baseline").getAsInt() : -1);
                Identifier blockId = Identifier.tryParse(r.blockId); Block block = blockId == null ? null : Registries.BLOCK.getOptionalValue(blockId).orElse(null);
                if (block == null || !OreScan.isOreBlock(block)) throw new IllegalArgumentException("opportunity_block");
                OPPORTUNITIES.put(scoped(r.dimension, r.id), r);
            }
            return version == 1 || version == 2;
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
