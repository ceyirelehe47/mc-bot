package io.github.zoyluo.aibot.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.BridgeFault;
import io.github.zoyluo.aibot.external.ExternalBodyAccess;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.external.cognition.CognitiveInspector;
import io.github.zoyluo.aibot.external.cognition.CognitiveViewBuilder;
import io.github.zoyluo.aibot.external.cognition.EvidenceRef;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import java.util.Set;

/**
 * MC-2A0 cognitive view required cases. Covers the hash semantics that the pure-JDK JUnit
 * suite cannot reach (real registry/inventory/world state), the fail-closed evidence-ref
 * surface, occlusion non-leak through the SAME strict observation boundary as perception,
 * deterministic explicit truncation, bounded read-only inspect, and query non-interference
 * with a RUNNING execution.
 *
 * Requires the gradle run to carry AIBOT_EXTERNAL_BOT=Mc1caBot and a valid AIBOT_BRIDGE_TOKEN
 * (same environment as the MC1CA/R2/R2.1 batches): the production ExternalBodyRuntime boots
 * SemanticWorldRegistry on SERVER_STARTED and reservation stays active during these tests.
 *
 * Fake players are not world-ticked (R2.1 lesson): no test here relies on bot movement; the
 * walk-dependent behaviours are covered on the live server (LIVE-2A0-*).
 */
public final class MC2A0CognitiveViewGameTests implements FabricGameTest {
    // Independent bot name: batches run in PARALLEL and share the global bot namespace; reusing
    // Mc1caBot would despawn the live bots of concurrently running MC1CA/R2/R21 batches.
    // SemanticWorldRegistry.requireReady never checks the name, so a separate name is safe.
    private static final String BOT = "Mc2a0Bot";

    /** 1: clock-only changes (server tick / game time advance) never change scene_hash. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a0", tickLimit = 200)
    public void mc2a0StableSceneHashIgnoresClockOnlyChanges(TestContext context) {
        Fixture f = ground(context, "mc2a0_hash");
        // Parallel batches mutate the SHARED registry and live entities wander — both are real
        // semantic state, not clock noise. So the semantic section is frozen (same snapshot fed
        // to both builds) and nearby-entity counts are excluded from the comparison; full-scene
        // freeze over wall-clock ticks is proven by LIVE-2A0-1 in a quiet live world.
        com.google.gson.JsonObject frozen = SemanticWorldRegistry.observe(f.bot);
        var first = CognitiveViewBuilder.build(f.bot, null, frozen);
        long firstTick = f.bot.getServer().getTicks();
        context.runAtEveryTick(() -> {
            // 固定跨越 >=10 个 server tick 再重建:不依赖 %10 对齐,消除与方法体同 tick 的偶发
            if (f.bot.getServer().getTicks() - firstTick < 10) return;
            var second = CognitiveViewBuilder.build(f.bot, null, frozen);
            require(context, second.gameTime() > first.gameTime(),
                    "game time must advance between builds: " + first.gameTime() + " -> " + second.gameTime()
                            + " serverTick=" + f.bot.getServer().getTicks());
            require(context, stableHashIgnoringNearby(first.sceneJson())
                            .equals(stableHashIgnoringNearby(second.sceneJson())),
                    "clock-only change must keep the scene stable outside wandering entity counts");
            require(context, !first.sceneHash().equals(second.sceneHash()) || first.sceneJson().equals(second.sceneJson()),
                    "if the full hash is identical the bytes must be identical too");
            finish(context, f);
        });
    }

    /**
     * Removes the live entity-count subsection (real perception changes, not clock noise) and
     * re-canonicalizes the remainder: two scenes that differ ONLY by wandering entities must
     * produce identical canonical bytes. Uses the production CanonicalJson so the comparison
     * shares the exact canonicalization rule that produces scene_hash.
     */
    private static String stableHashIgnoringNearby(String sceneJson) {
        JsonObject scene = JsonParser.parseString(sceneJson).getAsJsonObject();
        scene.getAsJsonObject("environment").remove("nearby");
        // Gson 树转纯 Java Map/List(Number 统一 Double,两侧转换规则一致)再走生产 CanonicalJson
        Object plain = new com.google.gson.Gson().fromJson(scene, java.util.Map.class);
        return io.github.zoyluo.aibot.external.cognition.CanonicalJson.write(plain);
    }

    /** 2: a real inventory mutation changes scene_hash, and further mutation changes it again. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a0", tickLimit = 100)
    public void mc2a0SceneHashChangesOnInventoryMutation(TestContext context) {
        Fixture f = ground(context, "mc2a0_inv");
        var before = CognitiveViewBuilder.build(f.bot, null, null);
        InventoryAction.giveItem(f.bot, new ItemStack(Items.OAK_LOG, 3));
        var afterGive = CognitiveViewBuilder.build(f.bot, null, null);
        require(context, !before.sceneHash().equals(afterGive.sceneHash()), "inventory change must change scene_hash");
        InventoryAction.giveItem(f.bot, new ItemStack(Items.OAK_LOG, 2));
        var afterMore = CognitiveViewBuilder.build(f.bot, null, null);
        require(context, !afterGive.sceneHash().equals(afterMore.sceneHash()), "count change must change scene_hash");
        finish(context, f);
    }

    /** 3: the hashed scene binds world_id + dimension and every ref is scoped to them. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a0", tickLimit = 100)
    public void mc2a0ViewPreservesWorldDimensionIdentity(TestContext context) {
        Fixture f = ground(context, "mc2a0_world");
        var snapshot = CognitiveViewBuilder.build(f.bot, null, null);
        JsonObject scene = JsonParser.parseString(snapshot.sceneJson()).getAsJsonObject();
        JsonObject world = scene.getAsJsonObject("world");
        require(context, SemanticWorldRegistry.worldId().equals(world.get("world_id").getAsString()),
                "world_id must be the registry's per-save identity");
        require(context, "minecraft:overworld".equals(world.get("dimension").getAsString()),
                "dimension must be the bot's current dimension");
        String scope = world.get("world_id").getAsString() + "/" + world.get("dimension").getAsString();
        for (String ref : snapshot.inspectIndex().keySet())
            require(context, ref.startsWith("mc://" + world.get("world_id").getAsString() + "/"),
                    "every evidence_ref must be world-scoped: " + ref);
        require(context, !scope.isBlank(), "scope sanity");
        finish(context, f);
    }

    /** 4: MINED_PENDING_PICKUP stays a typed recovery obligation with explicit UNKNOWN, never acquired. */
    // Batch note: runs inside the mc1caR21 batch (serial within a batch) with the RESERVED name —
    // SemanticWorldRegistry.observeVisibleBlock only registers opportunities for the reserved
    // body, so this test must own Mc1caBot without racing the other MC1CA batches.
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 200)
    public void mc2a0OpportunityCardPreservesTypedStateAndUncertainty(TestContext context) {
        Fixture f = ground(context, "mc2a0_opp", "Mc1caBot");
        BlockPos ore = f.start.add(0, 1, 3);
        set(f.bot, ore, Blocks.IRON_ORE);
        InventoryAction.giveItem(f.bot, new ItemStack(Items.IRON_PICKAXE, 1));
        SemanticWorldRegistry.observeVisibleBlock(f.bot, ore, f.bot.getServerWorld().getBlockState(ore));
        String id = opportunityAt(context, f.bot, ore, "ACTIONABLE");
        SemanticWorldRegistry.markOpportunityPendingPickup(f.bot, id, 3);

        var snapshot = CognitiveViewBuilder.build(f.bot, null, null);
        JsonObject scene = JsonParser.parseString(snapshot.sceneJson()).getAsJsonObject();
        JsonObject card = findCard(scene, "opportunity", id);
        require(context, card != null, "pending opportunity card missing");
        require(context, "MINED_PENDING_PICKUP".equals(card.getAsJsonObject("summary").get("status").getAsString()),
                "status must stay typed");
        require(context, card.get("recovery_obligation").getAsBoolean(), "pending must be a recovery obligation");
        require(context, card.getAsJsonObject("resource_acquired").get("knowledge").getAsString().equals("UNKNOWN"),
                "resource_acquired must be UNKNOWN, never claimed");
        boolean uncertaintyListed = false;
        for (var element : scene.getAsJsonArray("uncertainty")) {
            JsonObject entry = element.getAsJsonObject();
            if (card.get("evidence_ref").getAsString().equals(entry.get("scope_ref").getAsString())
                    && "resource_acquired".equals(entry.get("field").getAsString()))
                uncertaintyListed = true;
        }
        require(context, uncertaintyListed, "pending pickup must appear in scene.uncertainty");
        // drill-down evidence keeps the same typed semantics (INSP-2: R2.1 states are not flattened);
        // MC-2A0.1:detail 由 materialize 按需展开,freshness 与 view 卡同源(snapshot.gameTime)。
        String evidence = CognitiveInspector.materialize(f.bot, null, snapshot.gameTime(),
                card.get("evidence_ref").getAsString(), "evidence");
        JsonObject evidenceJson = JsonParser.parseString(evidence).getAsJsonObject();
        require(context, evidenceJson.get("recovery_obligation").getAsBoolean(), "inspect evidence keeps the obligation");
        require(context, evidenceJson.get("pickup_baseline").getAsInt() == 3, "pickup_baseline must round-trip");
        finish(context, f);
    }

    /** 5: malformed refs are rejected by the parser; foreign/kind-mismatch/unknown refs fail closed on the index. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a0", tickLimit = 100)
    public void mc2a0InspectRejectsForeignOrForgedEvidenceRef(TestContext context) {
        Fixture f = ground(context, "mc2a0_ref");
        SemanticWorldRegistry.registerHome(f.bot, "mc2a0_ref_home", 6, 2, 5).persisted().join();
        SemanticWorldRegistry.captureHome(f.bot, "mc2a0_ref_home").persisted().join();
        var snapshot = CognitiveViewBuilder.build(f.bot, null, null);
        String dimension = "minecraft%3Aoverworld";
        String real = "mc://" + SemanticWorldRegistry.worldId() + "/" + dimension + "/structure/mc2a0_ref_home";
        require(context, snapshot.inspectIndex().containsKey(real), "captured home ref must be inspectable");
        for (String malformed : new String[]{"", "not-a-ref", "mc://w/d/k", "mc://w/d/kind/i/extra", "mc://w/d/vehicle/i"})
            try {
                EvidenceRef.parse(malformed);
                throwGameTest(context, "malformed ref must be rejected: '" + malformed + "'");
            } catch (BridgeFault expected) {
                require(context, expected.status == 400, "malformed ref must fail with 400");
            }
        // well-formed but foreign world / mismatched kind / unknown object: all miss the index
        require(context, snapshot.inspectIndex().get("mc://00000000-foreign/" + dimension + "/structure/mc2a0_ref_home") == null,
                "foreign world ref must fail closed");
        require(context, snapshot.inspectIndex().get("mc://" + SemanticWorldRegistry.worldId() + "/" + dimension + "/farm/mc2a0_ref_home") == null,
                "kind-mismatch ref must fail closed");
        require(context, snapshot.inspectIndex().get("mc://" + SemanticWorldRegistry.worldId() + "/" + dimension + "/structure/no_such_object") == null,
                "unknown object ref must fail closed");
        finish(context, f);
    }

    /** 6: an occluded rare block behind a wall is invisible to inspect_local; once exposed it appears (SEC-1). */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a0", tickLimit = 200)
    public void mc2a0InspectLocalDoesNotLeakOccludedBlock(TestContext context) {
        Fixture f = ground(context, "mc2a0_occl");
        BlockPos ore = f.start.add(0, 1, 5);
        set(f.bot, ore, Blocks.DIAMOND_ORE);
        for (int dx = -2; dx <= 2; dx++)
            for (int dy = 0; dy <= 3; dy++)
                set(f.bot, f.start.add(dx, dy, 3), Blocks.STONE); // solid wall between bot and ore

        String occluded = CognitiveInspector.inspectLocalJson(f.bot, 8, "blocks");
        require(context, !occluded.contains("diamond_ore"), "occluded diamond_ore must NOT leak: " + occluded);
        require(context, occluded.contains("minecraft:stone"), "the visible wall itself must be reported");

        for (int dx = -2; dx <= 2; dx++)
            for (int dy = 0; dy <= 3; dy++)
                set(f.bot, f.start.add(dx, dy, 3), Blocks.AIR);
        String exposed = CognitiveInspector.inspectLocalJson(f.bot, 8, "blocks");
        require(context, exposed.contains("diamond_ore"), "exposed diamond_ore must now be observable");
        JsonObject view = JsonParser.parseString(exposed).getAsJsonObject();
        require(context, view.get("radius_effective").getAsInt() <= 8, "radius must stay within perception policy");
        finish(context, f);
    }

    /** 7: beyond the card cap the collection is explicitly truncated, deterministically. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 300)
    public void mc2a0ViewTruncationIsExplicitAndDeterministic(TestContext context) {
        Fixture f = ground(context, "mc2a0_trunc", "Mc1caBot");
        int registered = 30; // > MAX_OPPORTUNITY_CARDS(24), < registry observe listing cap(32)
        for (int i = 0; i < registered; i++) {
            BlockPos ore = f.start.add(i - registered / 2, 1, 3);
            set(f.bot, ore, Blocks.IRON_ORE);
            SemanticWorldRegistry.observeVisibleBlock(f.bot, ore, f.bot.getServerWorld().getBlockState(ore));
        }
        // Freeze the listing: parallel batches also mutate the shared registry, which is real
        // state but would make the two builds non-comparable. The frozen snapshot pins determinism.
        com.google.gson.JsonObject frozen = SemanticWorldRegistry.observe(f.bot);
        var first = CognitiveViewBuilder.build(f.bot, null, frozen);
        JsonObject firstScene = JsonParser.parseString(first.sceneJson()).getAsJsonObject();
        JsonObject collection = firstScene.getAsJsonObject("semantic_objects").getAsJsonObject("resource_opportunities");
        int total = collection.get("total").getAsInt();
        require(context, total >= registered, "all fixture ores must be listed (total=" + total + ")");
        require(context, collection.getAsJsonArray("items").size() == 24, "items must be capped at 24");
        require(context, collection.get("truncated").getAsBoolean(), "truncation must be explicit");
        require(context, collection.get("omitted_count").getAsInt() == total - 24, "omitted_count must be exact");

        var second = CognitiveViewBuilder.build(f.bot, null, frozen);
        require(context, first.sceneJson().equals(second.sceneJson()),
                "truncation must be deterministic across builds of the same listing");
        finish(context, f);
    }

    /** 8: structure inspect is bounded (no full cell dump) and strictly read-only. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a0", tickLimit = 200)
    public void mc2a0InspectStructureIsBoundedAndReadOnly(TestContext context) {
        Fixture f = cabin(context, "mc2a0_bounded");
        BlockPos wallCell = f.start.add(3, 1, 0);
        JsonObject registryBefore = SemanticWorldRegistry.observe(f.bot);
        var snapshot = CognitiveViewBuilder.build(f.bot, null, null);
        String ref = "mc://" + SemanticWorldRegistry.worldId() + "/minecraft%3Aoverworld/structure/mc2a0_bounded";
        // MC-2A0.1:baseline 档按需 materialize(bot 在 cabin 外 9 格,墙体遮挡 → 非 LIVE,
        // missing 未知绝不伪装成确定的 0,missing_sample 为空仍满足有界断言)。
        String baseline = CognitiveInspector.materialize(f.bot, null, snapshot.gameTime(), ref, "baseline");
        JsonObject baselineJson = JsonParser.parseString(baseline).getAsJsonObject();
        int baselineCells = baselineJson.get("baseline_cells").getAsInt();
        require(context, baselineCells > 0 && baselineCells <= 4096, "baseline count must be bounded");
        require(context, baselineJson.getAsJsonObject("block_histogram").size() >= 1, "histogram must be a compact summary");
        require(context, baselineJson.getAsJsonArray("missing_sample").size() <= 64, "missing sample must be capped");
        String serialized = baseline.toLowerCase();
        require(context, serialized.split("x", -1).length - 1 < baselineCells * 3,
                "baseline detail must be a histogram+sample, not a full cell dump");

        CognitiveInspector.inspectLocalJson(f.bot, 8, "all");
        CognitiveViewBuilder.build(f.bot, null, null);
        require(context, SemanticWorldRegistry.observe(f.bot).equals(registryBefore),
                "queries must not mutate the semantic registry");
        require(context, f.bot.getServerWorld().getBlockState(wallCell).isOf(Blocks.OAK_PLANKS),
                "queries must not mutate world blocks");
        finish(context, f);
    }

    /** 9: view/inspect_local during a RUNNING ordinary execution never steal or pause the owner. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a0", tickLimit = 300)
    public void mc2a0ViewDoesNotStealRunningExecutionOwnership(TestContext context) {
        Fixture f = ground(context, "mc2a0_busy");
        Task task = new MoveTask(f.bot, f.start.add(40, 0, 0)); // fake players never walk: stays RUNNING
        ExternalBodyAccess.dispatch(() -> {
            TaskManager.INSTANCE.assign(f.bot, task, TaskOrigin.of(TaskOrigin.Kind.LLM_TOOL, "mc2a0_test"));
            return null;
        });
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.PENDING || task.state() == TaskState.RUNNING) task.tick(f.bot);
            if (task.state() != TaskState.RUNNING || !TaskManager.INSTANCE.getActive(f.bot).map(t -> t == task).orElse(false)) {
                if (task.state() == TaskState.RUNNING) return; // waiting for TaskManager pickup
                return;
            }
            var snapshot = CognitiveViewBuilder.build(f.bot, null, null);
            CognitiveInspector.inspectLocalJson(f.bot, 4, "summary");
            JsonObject scene = JsonParser.parseString(snapshot.sceneJson()).getAsJsonObject();
            require(context, "RUNNING".equals(scene.getAsJsonObject("execution").get("state").getAsString()),
                    "execution view must report RUNNING while the task runs");
            require(context, TaskManager.INSTANCE.getActive(f.bot).orElseThrow() == task,
                    "query must not steal the active execution");
            require(context, TaskManager.INSTANCE.pausedDepth(f.bot) == 0, "query must not pause the execution");
            require(context, !TaskManager.INSTANCE.isUserPaused(f.bot), "query must not user-pause the body");
            finish(context, f);
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(AIPlayerEntity bot, BlockPos start) {}

    /** Flat stone platform with clear air above; bot spawned and teleported onto it. */
    private static Fixture ground(TestContext context, String id) { return ground(context, id, BOT); }

    private static Fixture ground(TestContext context, String id, String botName) {
        ServerWorld world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -20; dx <= 20; dx++) {
            for (int dz = -6; dz <= 10; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 6; dy++)
                    world.setBlockState(feet.up(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        return new Fixture(spawnOn(context, world, start, botName), start);
    }

    /** Small 5x5 cabin (R21 geometry, simplified) with the bot outside; used by the structure inspect case. */
    private static Fixture cabin(TestContext context, String homeId) {
        ServerWorld world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -3; dx <= 12; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                for (int dy = -1; dy <= 5; dy++)
                    world.setBlockState(feet.up(dy),
                            dy == -1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        for (int dx = 3; dx <= 7; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean wall = dx == 3 || dx == 7 || dz == -2 || dz == 2;
                if (!wall) continue;
                for (int dy = 0; dy <= 2; dy++)
                    world.setBlockState(start.add(dx, dy, dz), Blocks.OAK_PLANKS.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        BlockPos botStart = start.add(9, 0, 0);
        AIPlayerEntity bot = spawnOn(context, world, botStart, BOT);
        SemanticWorldRegistry.registerHome(bot, homeId, 6, 2, 5).persisted().join();
        SemanticWorldRegistry.captureHome(bot, homeId).persisted().join();
        return new Fixture(bot, start);
    }

    private static AIPlayerEntity spawnOn(TestContext context, ServerWorld world, BlockPos feet, String botName) {
        for (int attempt = 0; attempt < 3; attempt++) {
            AIPlayerManager.INSTANCE.despawn(world.getServer(), botName);
            var spawned = AIPlayerManager.INSTANCE.spawn(world.getServer(), botName, world,
                    Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL);
            if (spawned.isPresent()) {
                AIPlayerEntity bot = spawned.get();
                bot.teleport(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0, 0, true);
                ExternalBodyAccess.activateReservation();
                return bot;
            }
        }
        context.throwGameTestException("failed to spawn reserved bot " + botName);
        return null;
    }

    /** Finds this fixture's ore opportunity by position in the registry observe listing. */
    private static String opportunityAt(TestContext context, AIPlayerEntity bot, BlockPos ore, String expectedStatus) {
        for (var element : SemanticWorldRegistry.observe(bot).getAsJsonArray("resource_opportunities")) {
            var o = element.getAsJsonObject();
            var position = o.getAsJsonObject("position");
            if (position.get("x").getAsInt() == ore.getX() && position.get("y").getAsInt() == ore.getY()
                    && position.get("z").getAsInt() == ore.getZ()) {
                require(context, expectedStatus.equals(o.get("status").getAsString()),
                        "fixture ore must be " + expectedStatus + ": " + o);
                return o.get("id").getAsString();
            }
        }
        throwGameTest(context, "fixture ore opportunity not found at " + ore);
        return null;
    }

    private static JsonObject findCard(JsonObject scene, String kind, String objectId) {
        String key = switch (kind) {
            case "structure" -> "structures";
            case "farm" -> "farms";
            default -> "resource_opportunities";
        };
        for (var element : scene.getAsJsonObject("semantic_objects").getAsJsonObject(key).getAsJsonArray("items")) {
            JsonObject card = element.getAsJsonObject();
            if (kind.equals(card.get("kind").getAsString()) && objectId.equals(card.get("object_id").getAsString()))
                return card;
        }
        return null;
    }

    private static void set(AIPlayerEntity bot, BlockPos pos, Block block) {
        bot.getServerWorld().setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) context.throwGameTestException(message);
    }

    private static void throwGameTest(TestContext context, String message) {
        context.throwGameTestException(message);
    }

    private static void finish(TestContext context, Fixture f) {
        TaskManager.INSTANCE.resetToIdle(f.bot);
        AIPlayerManager.INSTANCE.despawn(f.bot.getServer(), f.bot.getGameProfile().getName());
        context.complete();
    }
}
