package io.github.zoyluo.aibot.external.cognition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.BridgeFault;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.task.BlueprintSchema;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * MC-2A0 drill-down 构建器:把 EvidenceRef 展开成 bounded evidence detail(mc_inspect),
 * 以及以当前身体为中心的局部只读视图(mc_inspect_local)。
 *
 * 只扩已有 evidence,不获得远程全知(VIEW-10):
 * - structure/farm/opportunity 的 detail 全部来自 registry 只读快照与 observe 缓存;
 * - inspect_local 中心固定为当前身体,逐格走 ObservableWorldQuery.canObserveBlock 的
 *   同一严格六面射线边界——被遮挡的方块与隐藏扫描一样不可见(SEC-1/SEC-2);
 * - 严格调用侧限流:仅在显式请求时扫描,绝不进入 mc_view 的每 tick 路径(PERF-1)。
 */
public final class CognitiveInspector {
    static final int MAX_BASELINE_SAMPLE = 64;
    static final int MAX_FARM_CELL_SAMPLE = 64;
    static final int MAX_LOCAL_SAMPLE = 64;
    static final int MAX_LOCAL_ENTITIES = 64;

    private CognitiveInspector() {}

    /**
     * 构建 inspect 索引:evidence_ref -> {detail 档位 -> canonical JSON}。
     * 与场景卡片同源(observe 缓存 + registry 只读快照),因此 drill-down 永不比 view 更"新"。
     */
    public static Map<String, Map<String, String>> buildIndex(AIPlayerEntity bot, String worldId,
                                                              String dimension, JsonObject semantic) {
        Map<String, Map<String, String>> index = new LinkedHashMap<>();
        for (var structure : SemanticWorldRegistry.structureEvidences(bot)) {
            String ref = EvidenceRef.format(worldId, dimension, "structure", structure.id());
            JsonObject observed = findObserved(semantic.getAsJsonArray("structures"), structure.id());
            Map<String, String> details = new LinkedHashMap<>();
            details.put("summary", CanonicalJson.write(structureSummaryCard(structure, observed)));
            details.put("integrity", CanonicalJson.write(structureIntegrityDetail(structure, observed)));
            details.put("baseline", CanonicalJson.write(structureBaselineDetail(bot, structure, observed)));
            index.put(ref, details);
        }
        for (var farm : SemanticWorldRegistry.farmEvidences(bot)) {
            String ref = EvidenceRef.format(worldId, dimension, "farm", farm.id());
            JsonObject observed = findObserved(semantic.getAsJsonArray("farms"), farm.id());
            Map<String, String> details = new LinkedHashMap<>();
            details.put("summary", CanonicalJson.write(farmSummaryCard(farm, observed)));
            details.put("cells", CanonicalJson.write(farmCellsDetail(farm, observed)));
            index.put(ref, details);
        }
        for (JsonElement element : semantic.getAsJsonArray("resource_opportunities")) {
            JsonObject observed = element.getAsJsonObject();
            String id = stringOf(observed, "id");
            if (id == null) continue;
            Optional<SemanticWorldRegistry.OpportunitySpec> spec = SemanticWorldRegistry.opportunity(bot, id);
            if (spec.isEmpty()) continue; // 与 observe 列表短暂竞态时宁缺勿造
            String ref = EvidenceRef.format(worldId, dimension, "opportunity", id);
            Map<String, String> details = new LinkedHashMap<>();
            details.put("summary", CanonicalJson.write(opportunitySummaryCard(observed)));
            details.put("evidence", CanonicalJson.write(opportunityEvidenceDetail(observed, spec.get())));
            index.put(ref, details);
        }
        return index;
    }

    // ---- structure ----

    private static Map<String, Object> structureSummaryCard(SemanticWorldRegistry.StructureEvidence structure,
                                                            JsonObject observed) {
        Map<String, Object> card = CanonicalJson.object();
        card.put("kind", "structure");
        card.put("object_id", structure.id());
        card.put("role", structure.kind());
        card.put("knowledge", "VERIFIED_LIVE");
        card.put("freshness", "LIVE");
        Map<String, Object> summary = CanonicalJson.object();
        summary.put("baseline_cells", intOf(observed, "integrity_expected"));
        Map<String, Object> integrity = CanonicalJson.object();
        integrity.put("matched", intOf(observed, "integrity_matched"));
        integrity.put("missing", intOf(observed, "integrity_missing"));
        integrity.put("wrong", intOf(observed, "integrity_wrong"));
        integrity.put("repairable", boolOf(observed, "repairable"));
        summary.put("integrity", integrity);
        card.put("summary", summary);
        return card;
    }

    private static Map<String, Object> structureIntegrityDetail(SemanticWorldRegistry.StructureEvidence structure,
                                                                JsonObject observed) {
        Map<String, Object> detail = CanonicalJson.object();
        detail.put("object_id", structure.id());
        detail.put("source", "physical_integrity_check_over_registered_baseline");
        Map<String, Object> integrity = CanonicalJson.object();
        integrity.put("expected", intOf(observed, "integrity_expected"));
        integrity.put("matched", intOf(observed, "integrity_matched"));
        integrity.put("missing", intOf(observed, "integrity_missing"));
        integrity.put("wrong", intOf(observed, "integrity_wrong"));
        integrity.put("repairable", boolOf(observed, "repairable"));
        detail.put("integrity", integrity);
        Map<String, Object> bounds = CanonicalJson.object();
        bounds.put("min_x", structure.minX()); bounds.put("min_y", structure.minY()); bounds.put("min_z", structure.minZ());
        bounds.put("max_x", structure.maxX()); bounds.put("max_y", structure.maxY()); bounds.put("max_z", structure.maxZ());
        detail.put("bounds", bounds);
        return detail;
    }

    private static Map<String, Object> structureBaselineDetail(AIPlayerEntity bot,
                                                               SemanticWorldRegistry.StructureEvidence structure,
                                                               JsonObject observed) {
        Map<String, Object> detail = CanonicalJson.object();
        detail.put("object_id", structure.id());
        detail.put("baseline_cells", structure.cells().size());
        Map<String, Long> histogram = new TreeMap<>();
        for (var cell : structure.cells()) histogram.merge(cell.blockId(), 1L, Long::sum);
        detail.put("block_histogram", histogram);
        // missing 样本直接复用已验收的 homeRepairPlan 蓝图(missing-only 语义),不引入第二份判定。
        List<Map<String, Object>> missing = new ArrayList<>();
        boolean truncated = false;
        if ("HOME".equals(structure.kind())) {
            try {
                SemanticWorldRegistry.HomeRepairPlan plan = SemanticWorldRegistry.homeRepairPlan(bot, structure.id());
                List<BlueprintSchema.BlockPlacement> placements = new ArrayList<>(plan.blueprint().placements());
                placements.sort(Comparator.comparingInt(BlueprintSchema.BlockPlacement::dx)
                        .thenComparingInt(BlueprintSchema.BlockPlacement::dy)
                        .thenComparingInt(BlueprintSchema.BlockPlacement::dz));
                for (BlueprintSchema.BlockPlacement placement : placements) {
                    if (missing.size() >= MAX_BASELINE_SAMPLE) { truncated = true; break; }
                    Map<String, Object> cell = CanonicalJson.object();
                    cell.put("x", plan.anchor().getX() + placement.dx());
                    cell.put("y", plan.anchor().getY() + placement.dy());
                    cell.put("z", plan.anchor().getZ() + placement.dz());
                    cell.put("block", placement.blockId());
                    missing.add(cell);
                }
                detail.put("missing_count", plan.missing());
                detail.put("wrong_count", plan.wrong());
            } catch (RuntimeException planUnavailable) {
                detail.put("missing_count", intOf(observed, "integrity_missing"));
                detail.put("wrong_count", intOf(observed, "integrity_wrong"));
                detail.put("missing_sample_unavailable_reason", "home_repair_plan_unavailable");
            }
        } else {
            detail.put("missing_count", intOf(observed, "integrity_missing"));
            detail.put("wrong_count", intOf(observed, "integrity_wrong"));
        }
        detail.put("missing_sample", missing);
        detail.put("missing_sample_truncated", truncated);
        return detail;
    }

    // ---- farm ----

    private static Map<String, Object> farmSummaryCard(SemanticWorldRegistry.FarmEvidence farm, JsonObject observed) {
        Map<String, Object> card = CanonicalJson.object();
        card.put("kind", "farm");
        card.put("object_id", farm.id());
        boolean fresh = boolOf(observed, "fresh") && observed.has("mature");
        card.put("knowledge", fresh ? "VERIFIED_LIVE" : "LAST_KNOWN");
        card.put("freshness", fresh ? "LIVE" : "UNKNOWN");
        Map<String, Object> summary = CanonicalJson.object();
        summary.put("crop", stringOf(observed, "crop") == null ? farm.cropId() : stringOf(observed, "crop"));
        summary.put("registered_cells", intOf(observed, "registered_cells"));
        if (fresh) {
            summary.put("mature", intOf(observed, "mature"));
            summary.put("immature", intOf(observed, "immature"));
            summary.put("empty_farmland", intOf(observed, "empty_farmland"));
        }
        card.put("summary", summary);
        return card;
    }

    private static Map<String, Object> farmCellsDetail(SemanticWorldRegistry.FarmEvidence farm, JsonObject observed) {
        Map<String, Object> detail = CanonicalJson.object();
        detail.put("object_id", farm.id());
        detail.put("crop", stringOf(observed, "crop") == null ? farm.cropId() : stringOf(observed, "crop"));
        detail.put("center", position(farm.x(), farm.y(), farm.z()));
        detail.put("radius", farm.radius());
        detail.put("registered_cells", farm.cells().size());
        detail.put("source", "semantic_world_registry_exact_connected_mask");
        List<Map<String, Object>> cells = new ArrayList<>();
        List<SemanticWorldRegistry.CellEvidence> sorted = new ArrayList<>(farm.cells());
        sorted.sort(Comparator.comparingInt(SemanticWorldRegistry.CellEvidence::x)
                .thenComparingInt(SemanticWorldRegistry.CellEvidence::y)
                .thenComparingInt(SemanticWorldRegistry.CellEvidence::z));
        for (var cell : sorted) {
            if (cells.size() >= MAX_FARM_CELL_SAMPLE) break;
            cells.add(position(cell.x(), cell.y(), cell.z()));
        }
        detail.put("cells_sample", cells);
        detail.put("cells_sample_truncated", sorted.size() > cells.size());
        boolean fresh = boolOf(observed, "fresh") && observed.has("mature");
        if (fresh) {
            Map<String, Object> stats = CanonicalJson.object();
            stats.put("observed_cells", intOf(observed, "observed_cells"));
            stats.put("total_cells", intOf(observed, "total_cells"));
            stats.put("mature", intOf(observed, "mature"));
            stats.put("immature", intOf(observed, "immature"));
            stats.put("empty_farmland", intOf(observed, "empty_farmland"));
            stats.put("occupied_other", intOf(observed, "occupied_other"));
            detail.put("fresh_stats", stats);
        } else {
            Map<String, Object> stats = CanonicalJson.object();
            stats.put("knowledge", "UNKNOWN");
            stats.put("reason", "not_currently_verified");
            detail.put("fresh_stats", stats);
        }
        return detail;
    }

    // ---- opportunity ----

    private static Map<String, Object> opportunitySummaryCard(JsonObject observed) {
        Map<String, Object> card = CanonicalJson.object();
        card.put("kind", "opportunity");
        card.put("object_id", stringOf(observed, "id"));
        card.put("knowledge", "LAST_KNOWN");
        card.put("freshness", freshnessOf(longOf(observed, "last_seen_game_time"), -1L));
        Map<String, Object> summary = CanonicalJson.object();
        summary.put("block", stringOf(observed, "block"));
        summary.put("status", stringOf(observed, "status"));
        summary.put("required_tool", stringOf(observed, "required_tool"));
        summary.put("distance_blocks", observed.has("distance") ? Math.round(observed.get("distance").getAsDouble()) : -1);
        card.put("summary", summary);
        applyPendingPickupSemantics(card, stringOf(observed, "status"));
        return card;
    }

    /** MINED_PENDING_PICKUP 必须呈现为 recovery obligation 而非 acquired(R2.1 语义不扁平化)。 */
    static void applyPendingPickupSemantics(Map<String, Object> card, String status) {
        if (!"MINED_PENDING_PICKUP".equals(status)) return;
        card.put("recovery_obligation", true);
        Map<String, Object> acquired = CanonicalJson.object();
        acquired.put("knowledge", "UNKNOWN");
        acquired.put("reason", "inventory_delta_not_yet_verified");
        card.put("resource_acquired", acquired);
    }

    private static Map<String, Object> opportunityEvidenceDetail(JsonObject observed,
                                                                 SemanticWorldRegistry.OpportunitySpec spec) {
        Map<String, Object> detail = CanonicalJson.object();
        detail.put("object_id", spec.id());
        detail.put("block", stringOf(observed, "block"));
        detail.put("status", spec.status());
        detail.put("blocked_reason", spec.blockedReason());
        detail.put("required_tool", orEmpty(stringOf(observed, "required_tool")));
        detail.put("position", position(spec.pos().getX(), spec.pos().getY(), spec.pos().getZ()));
        detail.put("seen_from", position(spec.seenFrom().getX(), spec.seenFrom().getY(), spec.seenFrom().getZ()));
        detail.put("last_seen_game_time", longOf(observed, "last_seen_game_time"));
        detail.put("state_since_game_time", spec.stateSinceGameTime());
        detail.put("state_at", position(spec.stateAt().getX(), spec.stateAt().getY(), spec.stateAt().getZ()));
        detail.put("pickup_baseline", spec.pickupBaseline());
        detail.put("knowledge", "LAST_KNOWN");
        detail.put("freshness", freshnessOf(longOf(observed, "last_seen_game_time"), -1L));
        applyPendingPickupSemantics(detail, spec.status());
        return detail;
    }

    // ---- mc_inspect_local ----

    /**
     * 以当前身体为中心的 bounded 只读局部视图。中心不可远程指定;半径被
     * min(请求值, 16, 感知策略有效半径) 夹紧;可见性判定与 perception 完全同界。
     */
    public static String inspectLocalJson(AIPlayerEntity bot, int radius, String detail) {
        if (!CognitiveSnapshot.LOCAL_DETAILS.contains(detail)) throw new BridgeFault(400, "invalid_detail");
        if (radius < 1 || radius > 16) throw new BridgeFault(400, "radius_out_of_range_1_16");
        int policyRadius = Math.min(AIBotConfig.get().perception().radius(), 8); // 与 PerceptionCollector.collectBlocks 同界
        int effective = Math.min(radius, policyRadius);
        // 与 perception_snapshot 同一能力门:denied 决策照常进入 capability 日志(SEC-2)。
        CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "inspect_local");

        ServerWorld world = bot.getServerWorld();
        BlockPos center = bot.getBlockPos();
        Map<String, Long> histogram = new TreeMap<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        long visibleBlocks = 0;
        for (BlockPos raw : BlockPos.iterate(center.add(-effective, -effective, -effective),
                center.add(effective, effective, effective))) {
            BlockPos pos = raw.toImmutable();
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)) continue; // occluded 即不可见,绝不泄漏
            visibleBlocks++;
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            histogram.merge(id, 1L, Long::sum);
            if (samples.size() < MAX_LOCAL_SAMPLE * 2) {
                Map<String, Object> cell = CanonicalJson.object();
                cell.put("type", id);
                cell.put("x", (long) pos.getX()); cell.put("y", (long) pos.getY()); cell.put("z", (long) pos.getZ());
                cell.put("distance", Math.round(Math.sqrt(center.getSquaredDistance(pos))));
                samples.add(cell);
            }
        }
        samples.sort(Comparator.<Map<String, Object>>comparingLong(c -> (Long) c.get("distance"))
                .thenComparing(c -> (String) c.get("type"))
                .thenComparingLong(c -> (Long) c.get("x"))
                .thenComparingLong(c -> (Long) c.get("y"))
                .thenComparingLong(c -> (Long) c.get("z")));
        boolean sampleTruncated = samples.size() > MAX_LOCAL_SAMPLE;
        if (sampleTruncated) samples = new ArrayList<>(samples.subList(0, MAX_LOCAL_SAMPLE));

        List<Map<String, Object>> entities = new ArrayList<>();
        long hostiles = 0, animals = 0;
        for (Entity entity : world.getOtherEntities(bot, bot.getBoundingBox().expand(effective),
                entity -> entity instanceof LivingEntity)) {
            if (!ObservableWorldQuery.canObserveEntity(bot, entity)) continue;
            if (entity instanceof Monster) hostiles++; else animals++;
            if (entities.size() < MAX_LOCAL_ENTITIES) {
                Map<String, Object> item = CanonicalJson.object();
                item.put("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
                item.put("x", (long) Math.floor(entity.getX())); item.put("y", (long) Math.floor(entity.getY())); item.put("z", (long) Math.floor(entity.getZ()));
                item.put("distance", (long) Math.floor(bot.distanceTo(entity)));
                item.put("hostile", entity instanceof Monster);
                item.put("hp", (long) Math.floor(((LivingEntity) entity).getHealth()));
                entities.add(item);
            }
        }
        entities.sort(Comparator.<Map<String, Object>>comparingLong(e -> (Long) e.get("distance"))
                .thenComparing(e -> (String) e.get("type")));

        Map<String, Object> out = CanonicalJson.object();
        out.put("schema", "mc.local_view.v0");
        out.put("center", position(center.getX(), center.getY(), center.getZ()));
        out.put("radius_requested", (long) radius);
        out.put("radius_effective", (long) effective);
        out.put("perception_policy_radius", (long) policyRadius);
        out.put("detail", detail);
        Map<String, Object> blocks = CanonicalJson.object();
        blocks.put("visible_blocks", visibleBlocks);
        blocks.put("histogram", histogram);
        if ("blocks".equals(detail) || "all".equals(detail)) {
            blocks.put("sample", samples);
            blocks.put("sample_truncated", sampleTruncated);
        }
        out.put("blocks", blocks);
        Map<String, Object> entitySection = CanonicalJson.object();
        entitySection.put("visible_entities", (long) entities.size());
        entitySection.put("hostile_count", hostiles);
        entitySection.put("animal_count", animals);
        if ("entities".equals(detail) || "all".equals(detail)) entitySection.put("items", entities);
        out.put("entities", entitySection);
        out.put("observation_boundary", "same_strict_survival_raycast_as_perception_no_hidden_scan");
        String json = CanonicalJson.write(out);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > CognitiveSnapshot.LOCAL_MAX_BYTES)
            throw new BridgeFault(500, "inspect_local_exceeds_hard_limit");
        return json;
    }

    // ---- helpers ----

    static String freshnessOf(long lastSeenGameTime, long nowGameTime) {
        if (lastSeenGameTime <= 0) return "UNKNOWN";
        long age = nowGameTime - lastSeenGameTime;
        if (age < 0) return "UNKNOWN";
        if (age <= 2400L) return "RECENT";
        if (age <= 24000L) return "AGING";
        return "STALE";
    }

    static Map<String, Object> position(int x, int y, int z) {
        Map<String, Object> pos = CanonicalJson.object();
        pos.put("x", (long) x); pos.put("y", (long) y); pos.put("z", (long) z);
        return pos;
    }

    static JsonObject findObserved(JsonArray array, String id) {
        if (array != null) for (JsonElement element : array) {
            JsonObject candidate = element.getAsJsonObject();
            if (id.equals(stringOf(candidate, "id"))) return candidate;
        }
        return new JsonObject();
    }

    static String stringOf(JsonObject object, String key) {
        if (object == null || !object.has(key)) return null;
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    static int intOf(JsonObject object, String key) {
        if (object == null || !object.has(key)) return 0;
        try { return object.get(key).getAsInt(); } catch (RuntimeException malformed) { return 0; }
    }

    static long longOf(JsonObject object, String key) {
        if (object == null || !object.has(key)) return 0L;
        try { return object.get(key).getAsLong(); } catch (RuntimeException malformed) { return 0L; }
    }

    static boolean boolOf(JsonObject object, String key) {
        if (object == null || !object.has(key)) return false;
        try { return object.get(key).getAsBoolean(); } catch (RuntimeException malformed) { return false; }
    }
}
