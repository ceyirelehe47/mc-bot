package io.github.zoyluo.aibot.external.cognition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.BridgeJournal;
import io.github.zoyluo.aibot.external.BridgeFault;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MC-2A0 认知视图构建器:把既有 authority(Minecraft 物理状态 / SemanticWorldRegistry /
 * PerceptionCollector 同界观察 / TaskManager / durable journal 的只读尾部)组装成
 * mc.cognitive_view.v0 的 compact read model。
 *
 * 硬边界:
 * - 只读,不拥有任何第二份可变真相(VIEW-1/2);registry 与世界仍是唯一 authority;
 * - scene 只含离散语义状态与先验离散桶,时钟/游标噪声只在 meta(VIEW-5);
 * - 默认 compact + bounded,截断必须显式且确定(VIEW-3/11);
 * - 不输出任何战略判断(quality/should_* 一律禁止,VIEW-12);
 * - 只在 server 线程被 BodyBackend 调用(与 observeJson 同一约束)。
 */
public final class CognitiveViewBuilder {
    public static final int MAX_STRUCTURE_CARDS = 16;
    public static final int MAX_FARM_CARDS = 16;
    public static final int MAX_OPPORTUNITY_CARDS = 24;
    public static final int MAX_EVENT_ITEMS = 12;

    /** 每个机会状态固定展示优先级(仅排序,不是调度决策)。 */
    private static final List<String> OPPORTUNITY_STATUS_ORDER =
            List.of("MINED_PENDING_PICKUP", "ACTIONABLE", "BLOCKED", "UNREACHABLE");

    private CognitiveViewBuilder() {}

    /**
     * @param semanticOrNull 调用方缓存的 registry.observe 快照;传 null 则现取
     *                       (GameTest 直调路径)。两种来源数据完全一致。
     * @param journal        只读事件尾部来源;传 null 时 events 段显式 UNAVAILABLE_THIS_SLICE。
     */
    public static CognitiveSnapshot.Snapshot build(AIPlayerEntity bot, BridgeJournal journal, JsonObject semanticOrNull) {
        JsonObject semantic = semanticOrNull == null ? SemanticWorldRegistry.observe(bot) : semanticOrNull;
        ServerWorld world = bot.getServerWorld();
        String worldId = SemanticWorldRegistry.worldId();
        String dimension = world.getRegistryKey().getValue().toString();
        long gameTime = world.getTime();
        long dayTime = world.getTimeOfDay() % 24000L;

        Map<String, Object> scene = CanonicalJson.object();
        scene.put("world", worldSection(worldId, dimension));
        scene.put("self", selfSection(bot));
        scene.put("environment", environmentSection(bot, world, dayTime));

        List<Map<String, Object>> uncertainty = new ArrayList<>();
        Map<String, Object> semanticObjects = semanticObjectsSection(bot, worldId, dimension, semantic, gameTime, uncertainty);
        scene.put("semantic_objects", semanticObjects);
        scene.put("execution", executionSection(bot));
        scene.put("recent_significant_events", recentEventsSection(journal));
        uncertainty.sort(Comparator.comparing((Map<String, Object> u) -> (String) u.get("scope_ref"))
                .thenComparing(u -> (String) u.get("field"))
                .thenComparing(u -> (String) u.get("reason")));
        scene.put("uncertainty", uncertainty);

        String sceneJson = CanonicalJson.write(scene);
        if (sceneJson.getBytes(StandardCharsets.UTF_8).length > CognitiveSnapshot.VIEW_MAX_BYTES)
            throw new BridgeFault(500, "cognitive_view_exceeds_hard_limit");
        // MC-2A0.1 LAZY-1:快照只携带轻量 EvidenceDescriptor 句柄,绝不预计算
        // baseline/cells/evidence detail——那些由 mc_inspect 按需 materialize。
        Map<String, CognitiveSnapshot.EvidenceDescriptor> index = descriptorIndex(worldId, dimension, semantic);
        return new CognitiveSnapshot.Snapshot(sceneJson, sha256(sceneJson), gameTime, index);
    }

    /** ref -> 轻量身份句柄;纯 semantic 快照遍历,零世界读、零 detail 展开。 */
    private static Map<String, CognitiveSnapshot.EvidenceDescriptor> descriptorIndex(String worldId, String dimension,
                                                                                     JsonObject semantic) {
        Map<String, CognitiveSnapshot.EvidenceDescriptor> index = new LinkedHashMap<>();
        JsonArray structures = semantic.getAsJsonArray("structures");
        if (structures != null) for (JsonElement element : structures) {
            String id = CognitiveInspector.stringOf(element.getAsJsonObject(), "id");
            if (id == null) continue;
            String kind = orEmpty(CognitiveInspector.stringOf(element.getAsJsonObject(), "kind"));
            String ref = EvidenceRef.format(worldId, dimension, "structure", id);
            index.put(ref, new CognitiveSnapshot.EvidenceDescriptor(ref, "structure", id, kind));
        }
        JsonArray farms = semantic.getAsJsonArray("farms");
        if (farms != null) for (JsonElement element : farms) {
            String id = CognitiveInspector.stringOf(element.getAsJsonObject(), "id");
            if (id == null) continue;
            String ref = EvidenceRef.format(worldId, dimension, "farm", id);
            index.put(ref, new CognitiveSnapshot.EvidenceDescriptor(ref, "farm", id, "farm"));
        }
        JsonArray opportunities = semantic.getAsJsonArray("resource_opportunities");
        if (opportunities != null) for (JsonElement element : opportunities) {
            String id = CognitiveInspector.stringOf(element.getAsJsonObject(), "id");
            if (id == null) continue;
            String ref = EvidenceRef.format(worldId, dimension, "opportunity", id);
            index.put(ref, new CognitiveSnapshot.EvidenceDescriptor(ref, "opportunity", id, "resource_opportunity"));
        }
        return index;
    }

    // ---- scene.world / scene.self ----

    private static Map<String, Object> worldSection(String worldId, String dimension) {
        Map<String, Object> world = CanonicalJson.object();
        world.put("world_id", worldId);
        world.put("dimension", dimension);
        return world;
    }

    private static Map<String, Object> selfSection(AIPlayerEntity bot) {
        Map<String, Object> self = CanonicalJson.object();
        self.put("body_id", bot.getUuid().toString());
        self.put("name", bot.getGameProfile().getName());
        BlockPos feet = bot.getBlockPos();
        self.put("block_position", CognitiveInspector.position(feet.getX(), feet.getY(), feet.getZ()));
        self.put("health", (double) bot.getHealth());
        self.put("food", (long) bot.getHungerManager().getFoodLevel());
        Map<String, Long> inventory = new TreeMap<>();
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty()) inventory.merge(Registries.ITEM.getId(stack.getItem()).toString(),
                    (long) stack.getCount(), Long::sum);
        }
        self.put("inventory", inventory);
        return self;
    }

    // ---- scene.environment ----

    private static Map<String, Object> environmentSection(AIPlayerEntity bot, ServerWorld world, long dayTime) {
        Map<String, Object> environment = CanonicalJson.object();
        environment.put("day_phase", dayPhase(dayTime));
        environment.put("weather", world.isThundering() ? "THUNDER" : world.isRaining() ? "RAIN" : "CLEAR");
        environment.put("local_light", (long) world.getLightLevel(bot.getBlockPos()));
        environment.put("nearby", nearbySummary(bot));
        return environment;
    }

    private static String dayPhase(long dayTime) {
        if (dayTime < 1000) return "MORNING";
        if (dayTime < 11000) return "DAY";
        if (dayTime < 13000) return "DUSK";
        if (dayTime < 23000) return "NIGHT";
        return "DAWN";
    }

    /** 实体可见性走与 perception 完全相同的 canObserveEntity 边界;查不到不伪造 0。 */
    private static Map<String, Object> nearbySummary(AIPlayerEntity bot) {
        Map<String, Object> nearby = CanonicalJson.object();
        int radius = Math.min(AIBotConfig.get().perception().radius(), 16);
        long hostiles = 0, animals = 0, observed = 0;
        for (Entity entity : bot.getServerWorld().getOtherEntities(bot,
                bot.getBoundingBox().expand(radius), entity -> entity instanceof LivingEntity)) {
            if (!ObservableWorldQuery.canObserveEntity(bot, entity)) continue;
            observed++;
            if (entity instanceof Monster) hostiles++; else animals++;
        }
        nearby.put("hostile_count", hostiles);
        nearby.put("animal_count", animals);
        nearby.put("observed_entities", observed);
        return nearby;
    }

    // ---- scene.semantic_objects ----

    private static Map<String, Object> semanticObjectsSection(AIPlayerEntity bot, String worldId, String dimension,
                                                              JsonObject semantic, long gameTime,
                                                              List<Map<String, Object>> uncertainty) {
        Map<String, Object> section = CanonicalJson.object();

        // structures: 按 object_id 确定排序;成员与顺序以 semantic 快照为准(frozen 友好)。
        // MC-2A0.1 P0-1:durable facts(id/role/baseline 计数)与 current integrity 显式分层;
        // 当前完整性只来自 StructureKnowledge 的合法逐格验证——粗筛外绝不扫描(BOUND-2),
        // 非 LIVE 时不确定绝不伪装成确定事实(BOUND-4)。绝不透传 observe 的远程 integrity。
        Map<String, SemanticWorldRegistry.StructureEvidence> evidenceById = new LinkedHashMap<>();
        for (SemanticWorldRegistry.StructureEvidence evidence : SemanticWorldRegistry.structureEvidences(bot))
            evidenceById.put(evidence.id(), evidence);
        List<Map<String, Object>> structureCards = new ArrayList<>();
        JsonArray structures = semantic.getAsJsonArray("structures");
        if (structures != null) {
            List<JsonElement> sorted = new ArrayList<>();
            for (JsonElement element : structures) sorted.add(element);
            sorted.sort(Comparator.comparing(e -> orEmpty(CognitiveInspector.stringOf(e.getAsJsonObject(), "id"))));
            long tick = bot.getServer().getTicks();
            for (JsonElement element : sorted) {
                if (structureCards.size() >= MAX_STRUCTURE_CARDS) break;
                JsonObject observed = element.getAsJsonObject();
                String id = CognitiveInspector.stringOf(observed, "id");
                if (id == null) continue;
                String role = orEmpty(CognitiveInspector.stringOf(observed, "kind"));
                String ref = EvidenceRef.format(worldId, dimension, "structure", id);
                SemanticWorldRegistry.StructureEvidence evidence = evidenceById.get(id);
                StructureKnowledge.Assessment assessment = evidence == null
                        ? StructureKnowledge.Assessment.unknown("not_currently_verifiable")
                        : StructureKnowledge.assess(bot, evidence, gameTime, tick);
                Map<String, Object> card = CanonicalJson.object();
                card.put("evidence_ref", ref);
                card.put("kind", "structure");
                card.put("object_id", id);
                card.put("role", role);
                card.put("knowledge", assessment.knowledge());
                card.put("freshness", assessment.freshness());
                Map<String, Object> summary = CanonicalJson.object();
                // baseline_cells 是 durable 注册事实(基线快照计数),不需要当前可见。
                summary.put("baseline_cells", evidence != null ? (long) evidence.cells().size()
                        : (long) CognitiveInspector.intOf(observed, "snapshot_cells"));
                summary.put("current_integrity", CognitiveInspector.currentIntegrityOf(assessment));
                card.put("summary", summary);
                structureCards.add(card);
                if (!"VERIFIED_LIVE".equals(assessment.knowledge())) {
                    Map<String, Object> entry = CanonicalJson.object();
                    entry.put("scope_ref", ref);
                    entry.put("field", "current_integrity");
                    entry.put("reason", "not_currently_verifiable");
                    uncertainty.add(entry);
                }
            }
        }
        section.put("structures", collection(structureCards, sizeOf(structures)));

        // farms: 按 object_id 确定排序;不可验证的 live 统计进 uncertainty
        List<Map<String, Object>> farmCards = new ArrayList<>();
        JsonArray farms = semantic.getAsJsonArray("farms");
        if (farms != null) {
            List<JsonElement> sorted = new ArrayList<>();
            for (JsonElement element : farms) sorted.add(element);
            sorted.sort(Comparator.comparing(e -> orEmpty(CognitiveInspector.stringOf(e.getAsJsonObject(), "id"))));
            for (JsonElement element : sorted) {
                if (farmCards.size() >= MAX_FARM_CARDS) break;
                JsonObject observed = element.getAsJsonObject();
                String id = CognitiveInspector.stringOf(observed, "id");
                if (id == null) continue;
                String ref = EvidenceRef.format(worldId, dimension, "farm", id);
                boolean fresh = CognitiveInspector.boolOf(observed, "fresh") && observed.has("mature");
                Map<String, Object> card = CanonicalJson.object();
                card.put("evidence_ref", ref);
                card.put("kind", "farm");
                card.put("object_id", id);
                card.put("knowledge", fresh ? "VERIFIED_LIVE" : "LAST_KNOWN");
                card.put("freshness", fresh ? "LIVE" : "UNKNOWN");
                Map<String, Object> summary = CanonicalJson.object();
                summary.put("crop", orEmpty(CognitiveInspector.stringOf(observed, "crop")));
                summary.put("registered_cells", (long) CognitiveInspector.intOf(observed, "registered_cells"));
                if (fresh) {
                    summary.put("mature", (long) CognitiveInspector.intOf(observed, "mature"));
                    summary.put("immature", (long) CognitiveInspector.intOf(observed, "immature"));
                    summary.put("empty_farmland", (long) CognitiveInspector.intOf(observed, "empty_farmland"));
                }
                card.put("summary", summary);
                farmCards.add(card);
                if (!fresh) {
                    Map<String, Object> entry = CanonicalJson.object();
                    entry.put("scope_ref", ref);
                    entry.put("field", "live_crop_counts");
                    entry.put("reason", "not_currently_verified");
                    uncertainty.add(entry);
                }
            }
        }
        section.put("farms", collection(farmCards, sizeOf(farms)));

        // opportunities: 状态优先级 -> 距离 -> ref
        List<JsonObject> opportunities = new ArrayList<>();
        JsonArray opportunityArray = semantic.getAsJsonArray("resource_opportunities");
        if (opportunityArray != null) for (JsonElement element : opportunityArray)
            opportunities.add(element.getAsJsonObject());
        opportunities.sort(Comparator
                .comparingInt((JsonObject o) -> statusRank(CognitiveInspector.stringOf(o, "status")))
                .thenComparingDouble(o -> CognitiveInspector.stringOf(o, "id") == null ? Double.MAX_VALUE
                        : distanceOf(o))
                .thenComparing(o -> orEmpty(CognitiveInspector.stringOf(o, "id"))));
        List<Map<String, Object>> opportunityCards = new ArrayList<>();
        for (JsonObject observed : opportunities) {
            if (opportunityCards.size() >= MAX_OPPORTUNITY_CARDS) break;
            String id = CognitiveInspector.stringOf(observed, "id");
            if (id == null) continue;
            String ref = EvidenceRef.format(worldId, dimension, "opportunity", id);
            String status = orEmpty(CognitiveInspector.stringOf(observed, "status"));
            String freshness = CognitiveInspector.freshnessOf(
                    CognitiveInspector.longOf(observed, "last_seen_game_time"), gameTime);
            Map<String, Object> card = CanonicalJson.object();
            card.put("evidence_ref", ref);
            card.put("kind", "opportunity");
            card.put("object_id", id);
            card.put("knowledge", "LAST_KNOWN");
            card.put("freshness", freshness);
            Map<String, Object> summary = CanonicalJson.object();
            summary.put("block", orEmpty(CognitiveInspector.stringOf(observed, "block")));
            summary.put("status", status);
            summary.put("required_tool", orEmpty(CognitiveInspector.stringOf(observed, "required_tool")));
            summary.put("distance_blocks", (long) Math.round(distanceOf(observed)));
            card.put("summary", summary);
            CognitiveInspector.applyPendingPickupSemantics(card, status);
            opportunityCards.add(card);
            // 未验证的块态与未销账的拾取都以显式不确定性呈现,绝不写成确定事实(VIEW-4)。
            if ("STALE".equals(freshness) || "UNKNOWN".equals(freshness) || "UNREACHABLE".equals(status)) {
                Map<String, Object> entry = CanonicalJson.object();
                entry.put("scope_ref", ref);
                entry.put("field", "current_block_state");
                entry.put("reason", "last_seen_not_currently_observable");
                uncertainty.add(entry);
            }
            if ("MINED_PENDING_PICKUP".equals(status)) {
                Map<String, Object> entry = CanonicalJson.object();
                entry.put("scope_ref", ref);
                entry.put("field", "resource_acquired");
                entry.put("reason", "inventory_delta_not_yet_verified");
                uncertainty.add(entry);
            }
        }
        section.put("resource_opportunities", collection(opportunityCards, opportunities.size()));
        return section;
    }

    private static double distanceOf(JsonObject opportunity) {
        return opportunity.has("distance") ? opportunity.get("distance").getAsDouble() : Double.MAX_VALUE;
    }

    /** 未知状态一律排在已知四态之后,避免新状态意外抢占展示优先级。 */
    private static int statusRank(String status) {
        int rank = OPPORTUNITY_STATUS_ORDER.indexOf(status == null ? "" : status);
        return rank < 0 ? OPPORTUNITY_STATUS_ORDER.size() : rank;
    }

    private static Map<String, Object> collection(List<Map<String, Object>> items, int total) {
        Map<String, Object> collection = CanonicalJson.object();
        collection.put("items", items);
        collection.put("total", (long) total);
        collection.put("truncated", items.size() < total);
        collection.put("omitted_count", (long) (total - items.size()));
        return collection;
    }

    // ---- scene.execution ----

    private static Map<String, Object> executionSection(AIPlayerEntity bot) {
        TaskStatus status = TaskManager.INSTANCE.status(bot);
        boolean active = TaskManager.INSTANCE.getActive(bot).isPresent();
        Map<String, Object> execution = CanonicalJson.object();
        if (!active) execution.put("state", "IDLE");
        else if (status.state() == TaskState.PAUSED) execution.put("state", "PAUSED");
        else execution.put("state", "RUNNING");
        // MC-2A0.1 EXEC-1:IDLE 时 TaskManager.status 仍可能残留上一个任务的名字——
        // 没有 active execution 就绝不能把陈旧名字当 current_task 呈现,置 null。
        execution.put("current_task", active ? status.name() : null);
        execution.put("progress_bucket", progressBucket(active, status.progress()));
        execution.put("safety_active",
                TaskManager.INSTANCE.activeOrigin(bot).map(origin -> origin.safety()).orElse(false));
        execution.put("user_paused", TaskManager.INSTANCE.isUserPaused(bot));
        execution.put("paused_depth", (long) TaskManager.INSTANCE.pausedDepth(bot));
        return execution;
    }

    /** elapsed tick 不进 scene;只保留离散进度桶(VIEW-5 的"先离散"规则)。 */
    private static long progressBucket(boolean active, double progress) {
        if (!active) return 100L;
        double bounded = Math.max(0D, Math.min(1D, progress));
        return (long) Math.floor(bounded * 4D) * 25L;
    }

    // ---- scene.recent_significant_events ----

    /** journal 只读尾部:replay() 是 non-consuming 快照,不推进任何 DSH 游标(VIEW-13)。 */
    private static Map<String, Object> recentEventsSection(BridgeJournal journal) {
        Map<String, Object> section = CanonicalJson.object();
        if (journal == null) {
            section.put("availability", "UNAVAILABLE_THIS_SLICE");
            section.put("items", List.of());
            section.put("truncated", false);
            return section;
        }
        List<Map<String, Object>> significant = new ArrayList<>();
        for (BridgeJournal.Frame frame : journal.replay()) {
            String kind = frame.fields().getOrDefault("kind", "");
            boolean keep = switch (kind) {
                case "death", "damage", "respawn", "survival_alert", "resource_opportunity_actionable",
                        "resource_opportunity_stale", "control_acquired", "control_lost", "body_changed" -> true;
                case "execution" -> List.of("completed", "failed", "cancelled", "outcome_unknown")
                        .contains(frame.fields().getOrDefault("state", ""));
                default -> false;
            };
            if (!keep) continue;
            Map<String, Object> item = CanonicalJson.object();
            item.put("kind", kind);
            item.put("sequence", frame.sequence());
            String executionId = frame.fields().get("execution_id");
            if (executionId != null && !executionId.isBlank()) item.put("execution_id", executionId);
            significant.add(item);
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = significant.size() - 1; i >= 0 && recent.size() < MAX_EVENT_ITEMS; i--) recent.add(significant.get(i));
        List<Map<String, Object>> items = new ArrayList<>(recent);
        java.util.Collections.reverse(items); // 时间正序输出
        section.put("availability", "AVAILABLE");
        section.put("items", items);
        section.put("truncated", significant.size() > items.size());
        return section;
    }

    // ---- hash ----

    static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int sizeOf(JsonArray array) {
        return array == null ? 0 : array.size();
    }
}
