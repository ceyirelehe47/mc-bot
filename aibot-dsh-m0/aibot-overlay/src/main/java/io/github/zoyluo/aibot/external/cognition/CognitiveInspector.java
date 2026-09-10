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
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MC-2A0.1 drill-down 构建器:mc_inspect 按需(on-demand)把单个 EvidenceRef 展开成
 * bounded evidence detail,以及以当前身体为中心的局部只读视图(mc_inspect_local)。
 *
 * MC-2A0.1 边界(EVID-BND-1..5):
 * - detail 绝不预计算:view 快照只带轻量 descriptor,本类的 materialize 只在显式
 *   mc_inspect 请求时展开请求的那一个 ref+detail(LAZY-1..3),绝不进入每 tick 路径;
 * - structure 的当前完整性只来自 {@link StructureKnowledge} 的合法逐格验证(proof
 *   before read + bounds 粗筛),绝不透传 registry observe 的远程全知 integrity(BOUND-2);
 * - homeRepairPlan 只允许"显式 inspect baseline + 当前可 LIVE 验证"的组合出现,
 *   周期 view 快照刷新绝不触发(LAZY-2);
 * - inspect_local 逐格先证明(canObserveBlock)后读取(getBlockState)(BOUND-1/SEC-2),
 *   与 perception 同一严格六面射线边界——被遮挡的方块与隐藏扫描一样不可见;
 * - 严格调用侧限流:所有扫描仅在显式请求时发生,由 kernel 查询队列的每 tick 预算驱动(PERF-1)。
 */
public final class CognitiveInspector {
    static final int MAX_BASELINE_SAMPLE = 64;
    static final int MAX_FARM_CELL_SAMPLE = 64;
    static final int MAX_LOCAL_SAMPLE = 64;
    static final int MAX_LOCAL_ENTITIES = 64;

    /**
     * test/live-only instrumentation(LIVE-2A01-3 证据,生产零输出):materialize 与
     * homeRepairPlan 路径计数。public 仅供实机驱动脚本与测试读取;生产代码从不打印。
     */
    public static final AtomicLong BASELINE_MATERIALIZED = new AtomicLong();
    public static final AtomicLong HOME_REPAIR_PLAN_CALLS = new AtomicLong();

    private CognitiveInspector() {}

    /**
     * MC-2A0.1 on-demand materialize:展开请求的单个 ref+detail。
     *
     * @param semanticOrNull 调用方缓存的 registry.observe 快照(server 线程);null 则现取(GameTest 直调)
     * @param gameTime       snapshot 构建时刻的 game time——freshness 与 view 卡同源,三处一致(FRESH-1)
     * @return canonical JSON 文本;ref/detail 非法一律 BridgeFault fail-closed
     */
    public static String materialize(AIPlayerEntity bot, JsonObject semanticOrNull,
                                     long gameTime, String ref, String detail) {
        EvidenceRef.Parsed parsed = EvidenceRef.parse(ref); // malformed 一律 400 fail-closed
        String level = detail == null || detail.isBlank() ? "summary" : detail;
        Set<String> allowed = CognitiveSnapshot.INSPECT_DETAILS.get(parsed.kind());
        if (allowed == null || !allowed.contains(level))
            throw new BridgeFault(400, "unsupported_detail_level:" + level);
        JsonObject semantic = semanticOrNull == null ? SemanticWorldRegistry.observe(bot) : semanticOrNull;
        long tick = bot.getServer().getTicks();
        Map<String, Object> out = switch (parsed.kind()) {
            case "structure" -> materializeStructure(bot, semantic, gameTime, tick, parsed, level);
            case "farm" -> materializeFarm(bot, semantic, parsed, level);
            default -> materializeOpportunity(bot, semantic, gameTime, parsed, level);
        };
        String json = CanonicalJson.write(out);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > CognitiveSnapshot.INSPECT_MAX_BYTES)
            throw new BridgeFault(500, "inspect_exceeds_hard_limit");
        return json;
    }

    // ---- structure ----

    private static Map<String, Object> materializeStructure(AIPlayerEntity bot, JsonObject semantic,
                                                            long gameTime, long tick,
                                                            EvidenceRef.Parsed ref, String level) {
        SemanticWorldRegistry.StructureEvidence structure = findStructure(bot, semantic, ref.objectId());
        if (structure == null) throw new BridgeFault(404, "evidence_ref_not_in_current_view:unknown_object");
        StructureKnowledge.Assessment assessment =
                StructureKnowledge.assess(bot, structure, gameTime, tick);
        return switch (level) {
            case "summary" -> structureCard(structure, assessment);
            case "integrity" -> structureIntegrityDetail(structure, assessment);
            default -> structureBaselineDetail(bot, structure, assessment);
        };
    }

    /** structure 的 view 卡与 inspect summary 是同一份判定(知识/freshness/integrity 同源)。 */
    static Map<String, Object> structureCard(SemanticWorldRegistry.StructureEvidence structure,
                                             StructureKnowledge.Assessment assessment) {
        Map<String, Object> card = CanonicalJson.object();
        card.put("kind", "structure");
        card.put("object_id", structure.id());
        card.put("role", structure.kind());
        card.put("knowledge", assessment.knowledge());
        card.put("freshness", assessment.freshness());
        Map<String, Object> summary = CanonicalJson.object();
        summary.put("baseline_cells", (long) structure.cells().size());
        summary.put("current_integrity", currentIntegrityOf(assessment));
        card.put("summary", summary);
        return card;
    }

    /**
     * durable 基线计数与 current_integrity 显式分层(BOUND-3):
     * LIVE→真实数字;LAST_KNOWN→上次合法验证的数字(明确 knowledge,绝不当当前真相);
     * UNKNOWN→只有 reason,没有数字(BOUND-4:不确定不得伪装 repairable=false 之类的确定)。
     */
    static Map<String, Object> currentIntegrityOf(StructureKnowledge.Assessment assessment) {
        Map<String, Object> integrity = CanonicalJson.object();
        StructureKnowledge.Verified verified = assessment.live();
        if (verified == null) {
            integrity.put("knowledge", assessment.knowledge());
            integrity.put("reason", assessment.unknownReason().isBlank() ? "not_currently_verifiable" : assessment.unknownReason());
            return integrity;
        }
        integrity.put("knowledge", assessment.knowledge());
        if ("LAST_KNOWN".equals(assessment.knowledge())) integrity.put("verified_game_time", verified.atGameTime());
        integrity.put("expected", verified.expected());
        integrity.put("matched", verified.matched());
        integrity.put("missing", verified.missing());
        integrity.put("wrong", verified.wrong());
        return integrity;
    }

    private static Map<String, Object> structureIntegrityDetail(SemanticWorldRegistry.StructureEvidence structure,
                                                                StructureKnowledge.Assessment assessment) {
        Map<String, Object> detail = CanonicalJson.object();
        detail.put("object_id", structure.id());
        detail.put("source", "proof_before_read_cell_verification_over_registered_baseline");
        detail.putAll(currentIntegrityOf(assessment));
        Map<String, Object> bounds = CanonicalJson.object();
        bounds.put("min_x", (long) structure.minX()); bounds.put("min_y", (long) structure.minY()); bounds.put("min_z", (long) structure.minZ());
        bounds.put("max_x", (long) structure.maxX()); bounds.put("max_y", (long) structure.maxY()); bounds.put("max_z", (long) structure.maxZ());
        detail.put("bounds", bounds);
        return detail;
    }

    private static Map<String, Object> structureBaselineDetail(AIPlayerEntity bot,
                                                               SemanticWorldRegistry.StructureEvidence structure,
                                                               StructureKnowledge.Assessment assessment) {
        BASELINE_MATERIALIZED.incrementAndGet();
        Map<String, Object> detail = CanonicalJson.object();
        detail.put("object_id", structure.id());
        detail.put("baseline_cells", (long) structure.cells().size());
        Map<String, Long> histogram = new TreeMap<>();
        for (var cell : structure.cells()) histogram.merge(cell.blockId(), 1L, Long::sum);
        detail.put("block_histogram", histogram);
        // missing 样本只在"当前可 LIVE 验证"时给出(LAZY-2/BOUND-2):homeRepairPlan 会读取全部
        // baseline cells,绝不允许在远程/不可证明时被触发;missing 样本复用已验收的 missing-only 蓝图。
        List<Map<String, Object>> missing = new ArrayList<>();
        boolean truncated = false;
        StructureKnowledge.Verified verified = assessment.live();
        if (verified != null) {
            detail.put("current_integrity", currentIntegrityOf(assessment));
            if ("HOME".equals(structure.kind())) {
                HOME_REPAIR_PLAN_CALLS.incrementAndGet();
                try {
                    SemanticWorldRegistry.HomeRepairPlan plan = SemanticWorldRegistry.homeRepairPlan(bot, structure.id());
                    List<BlueprintSchema.BlockPlacement> placements = new ArrayList<>(plan.blueprint().placements());
                    placements.sort(Comparator.comparingInt(BlueprintSchema.BlockPlacement::dx)
                            .thenComparingInt(BlueprintSchema.BlockPlacement::dy)
                            .thenComparingInt(BlueprintSchema.BlockPlacement::dz));
                    for (BlueprintSchema.BlockPlacement placement : placements) {
                        if (missing.size() >= MAX_BASELINE_SAMPLE) { truncated = true; break; }
                        Map<String, Object> cell = CanonicalJson.object();
                        cell.put("x", (long) (plan.anchor().getX() + placement.dx()));
                        cell.put("y", (long) (plan.anchor().getY() + placement.dy()));
                        cell.put("z", (long) (plan.anchor().getZ() + placement.dz()));
                        cell.put("block", placement.blockId());
                        missing.add(cell);
                    }
                    detail.put("missing_count", (long) plan.missing());
                    detail.put("wrong_count", (long) plan.wrong());
                } catch (RuntimeException planUnavailable) {
                    detail.put("missing_count", verified.missing());
                    detail.put("wrong_count", verified.wrong());
                    detail.put("missing_sample_unavailable_reason", "home_repair_plan_unavailable");
                }
            } else {
                detail.put("missing_count", verified.missing());
                detail.put("wrong_count", verified.wrong());
            }
        } else {
            Map<String, Object> unknown = CanonicalJson.object();
            unknown.put("knowledge", "UNKNOWN");
            unknown.put("reason", assessment.unknownReason().isBlank() ? "not_currently_verifiable" : assessment.unknownReason());
            detail.put("current_integrity", unknown);
            detail.put("missing_count", null);   // 不确定绝不伪装成确定的 0(BOUND-4)
            detail.put("wrong_count", null);
        }
        detail.put("missing_sample", missing);
        detail.put("missing_sample_truncated", truncated);
        return detail;
    }

    private static SemanticWorldRegistry.StructureEvidence findStructure(AIPlayerEntity bot, JsonObject semantic,
                                                                         String objectId) {
        // 成员与顺序以 semantic 快照为准(frozen 友好);registry 结构只增不删,miss 即 unknown。
        JsonArray structures = semantic.getAsJsonArray("structures");
        if (structures == null || !containsId(structures, objectId)) return null;
        for (SemanticWorldRegistry.StructureEvidence structure : SemanticWorldRegistry.structureEvidences(bot))
            if (structure.id().equals(objectId)) return structure;
        return null;
    }

    private static boolean containsId(JsonArray array, String id) {
        for (JsonElement element : array)
            if (id.equals(CognitiveInspector.stringOf(element.getAsJsonObject(), "id"))) return true;
        return false;
    }

    // ---- farm ----

    private static Map<String, Object> materializeFarm(AIPlayerEntity bot, JsonObject semantic,
                                                       EvidenceRef.Parsed ref, String level) {
        JsonObject observed = findObserved(semantic.getAsJsonArray("farms"), ref.objectId());
        if (observed.size() == 0) throw new BridgeFault(404, "evidence_ref_not_in_current_view:unknown_object");
        if (!"cells".equals(level)) return farmSummaryCard(observed);
        SemanticWorldRegistry.FarmEvidence farm = null;
        for (SemanticWorldRegistry.FarmEvidence candidate : SemanticWorldRegistry.farmEvidences(bot))
            if (candidate.id().equals(ref.objectId())) { farm = candidate; break; }
        if (farm == null) throw new BridgeFault(404, "evidence_ref_not_in_current_view:unknown_object");
        return farmCellsDetail(farm, observed);
    }

    private static Map<String, Object> farmSummaryCard(JsonObject observed) {
        Map<String, Object> card = CanonicalJson.object();
        card.put("kind", "farm");
        card.put("object_id", stringOf(observed, "id"));
        boolean fresh = boolOf(observed, "fresh") && observed.has("mature");
        card.put("knowledge", fresh ? "VERIFIED_LIVE" : "LAST_KNOWN");
        card.put("freshness", fresh ? "LIVE" : "UNKNOWN");
        Map<String, Object> summary = CanonicalJson.object();
        summary.put("crop", stringOf(observed, "crop") == null ? "" : stringOf(observed, "crop"));
        summary.put("registered_cells", (long) intOf(observed, "registered_cells"));
        if (fresh) {
            summary.put("mature", (long) intOf(observed, "mature"));
            summary.put("immature", (long) intOf(observed, "immature"));
            summary.put("empty_farmland", (long) intOf(observed, "empty_farmland"));
        }
        card.put("summary", summary);
        return card;
    }

    private static Map<String, Object> farmCellsDetail(SemanticWorldRegistry.FarmEvidence farm, JsonObject observed) {
        Map<String, Object> detail = CanonicalJson.object();
        detail.put("object_id", farm.id());
        detail.put("crop", stringOf(observed, "crop") == null ? farm.cropId() : stringOf(observed, "crop"));
        detail.put("center", position(farm.x(), farm.y(), farm.z()));
        detail.put("radius", (long) farm.radius());
        detail.put("registered_cells", (long) farm.cells().size());
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
            stats.put("observed_cells", (long) intOf(observed, "observed_cells"));
            stats.put("total_cells", (long) intOf(observed, "total_cells"));
            stats.put("mature", (long) intOf(observed, "mature"));
            stats.put("immature", (long) intOf(observed, "immature"));
            stats.put("empty_farmland", (long) intOf(observed, "empty_farmland"));
            stats.put("occupied_other", (long) intOf(observed, "occupied_other"));
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

    private static Map<String, Object> materializeOpportunity(AIPlayerEntity bot, JsonObject semantic,
                                                              long gameTime, EvidenceRef.Parsed ref, String level) {
        JsonObject observed = findObserved(semantic.getAsJsonArray("resource_opportunities"), ref.objectId());
        if (observed.size() == 0) throw new BridgeFault(404, "evidence_ref_not_in_current_view:unknown_object");
        Optional<SemanticWorldRegistry.OpportunitySpec> spec = SemanticWorldRegistry.opportunity(bot, ref.objectId());
        if (spec.isEmpty()) throw new BridgeFault(404, "evidence_ref_not_in_current_view:registry_miss");
        return "evidence".equals(level) ? opportunityEvidenceDetail(observed, spec.get(), gameTime)
                : opportunitySummaryCard(observed, gameTime);
    }

    private static Map<String, Object> opportunitySummaryCard(JsonObject observed, long gameTime) {
        Map<String, Object> card = CanonicalJson.object();
        card.put("kind", "opportunity");
        card.put("object_id", stringOf(observed, "id"));
        card.put("knowledge", "LAST_KNOWN");
        card.put("freshness", freshnessOf(longOf(observed, "last_seen_game_time"), gameTime));
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
                                                                 SemanticWorldRegistry.OpportunitySpec spec,
                                                                 long gameTime) {
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
        detail.put("pickup_baseline", (long) spec.pickupBaseline());
        detail.put("knowledge", "LAST_KNOWN");
        detail.put("freshness", freshnessOf(longOf(observed, "last_seen_game_time"), gameTime));
        applyPendingPickupSemantics(detail, spec.status());
        return detail;
    }

    // ---- mc_inspect_local ----

    /**
     * 以当前身体为中心的 bounded 只读局部视图。中心不可远程指定;半径被
     * min(请求值, 16, 感知策略有效半径) 夹紧;可见性判定与 perception 完全同界。
     *
     * BOUND-1(硬不变量):每个格子的循环顺序固定为 先证明(canObserveBlock 的严格射线)
     * 后读取(getBlockState)——输出没泄漏不等于读取边界正确,源码顺序本身就是契约。
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
            if (!ObservableWorldQuery.canObserveBlock(bot, pos)) continue; // proof BEFORE read: 遮挡即不可见
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
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
        out.put("observation_boundary", "proof_before_read_same_strict_survival_raycast_no_hidden_scan");
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
