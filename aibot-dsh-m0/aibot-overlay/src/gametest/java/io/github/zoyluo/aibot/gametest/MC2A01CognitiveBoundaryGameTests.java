package io.github.zoyluo.aibot.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.ExternalBodyAccess;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.external.cognition.CognitiveInspector;
import io.github.zoyluo.aibot.external.cognition.CognitiveViewBuilder;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import java.util.Set;

/**
 * MC-2A0.1 cognitive evidence boundary required cases.
 *
 * Covers what only a real registry/world can prove:
 * - remote structure current-integrity is NEVER server-scanned and never claims
 *   VERIFIED_LIVE / LIVE freshness (BOUND-2..4), while durable registration facts
 *   stay known; returning to the verification envelope re-admits fresh changes
 *   into LIVE (STR-1..3);
 * - a fully observable nearby structure CAN legitimately claim VERIFIED_LIVE with
 *   real matched/missing counts (proof-before-read over the registered baseline);
 * - opportunity freshness is identical across view card / inspect summary /
 *   inspect evidence and uses the snapshot game time (FRESH-1);
 * - IDLE clears current_task to explicit null instead of a stale task name (EXEC-1).
 *
 * Same environment contract as the MC2A0 batch: run with AIBOT_EXTERNAL_BOT=Mc1caBot
 * and a valid bridge token; fake players are not world-ticked (R2.1 lesson) so no
 * test relies on bot walking — distance changes are direct teleports.
 */
public final class MC2A01CognitiveBoundaryGameTests implements FabricGameTest {
    private static final String BOT = "Mc2a01Bot";

    /**
     * 1 (STR-1/STR-3): near → LIVE; far + out-of-envelope damage → still known but
     * LAST_KNOWN with the OLD verified numbers (the remote change must NOT leak as
     * current missing/wrong); return + cache expiry → the damage enters LIVE.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a01", tickLimit = 300)
    public void mc2a01RemoteStructureNeverClaimsLiveIntegrity(TestContext context) {
        Fixture f = home(context, "mc2a01_remote");
        var near = CognitiveViewBuilder.build(f.bot, null, null);
        JsonObject card = structureCard(near.sceneJson(), "mc2a01_remote");
        require(context, card != null, "structure card must exist");
        require(context, "VERIFIED_LIVE".equals(card.get("knowledge").getAsString()),
                "inside the envelope a fully observable home must be VERIFIED_LIVE: " + card);
        JsonObject integrity = card.getAsJsonObject("summary").getAsJsonObject("current_integrity");
        require(context, "VERIFIED_LIVE".equals(integrity.get("knowledge").getAsString()), "integrity must be live");
        require(context, integrity.get("missing").getAsLong() == 0, "freshly captured home must have 0 missing");
        long verifiedTick = f.bot.getServer().getTicks();

        // Damage one baseline cell (a wall block), then move the bot far OUTSIDE the
        // verification envelope before the next view — the cognition path must not read it now.
        BlockPos damaged = f.start.add(0, 1, 3);
        set(f.bot, damaged, Blocks.AIR);
        teleport(f.bot, f.start.add(60, 0, 60));
        var far = CognitiveViewBuilder.build(f.bot, null, null);
        JsonObject farCard = structureCard(far.sceneJson(), "mc2a01_remote");
        require(context, farCard != null, "durable identity must keep the structure known");
        require(context, !"VERIFIED_LIVE".equals(farCard.get("knowledge").getAsString()),
                "remote structure must never claim VERIFIED_LIVE: " + farCard);
        require(context, !"LIVE".equals(farCard.get("freshness").getAsString()),
                "remote structure freshness must never be LIVE");
        JsonObject farIntegrity = farCard.getAsJsonObject("summary").getAsJsonObject("current_integrity");
        require(context, "LAST_KNOWN".equals(farIntegrity.get("knowledge").getAsString()),
                "a prior legal verification must surface as LAST_KNOWN, not UNKNOWN: " + farIntegrity);
        require(context, farIntegrity.get("missing").getAsLong() == 0,
                "the remote damage must NOT leak as current missing/wrong (stays the old verified number)");
        require(context, farCard.getAsJsonObject("summary").get("baseline_cells").getAsLong() > 0,
                "durable baseline_cells fact must remain known");

        // Return inside the envelope; wait past the verify-cache window so the next
        // build re-verifies and the damage legitimately enters LIVE.
        teleport(f.bot, f.start);
        context.runAtEveryTick(() -> {
            if (f.bot.getServer().getTicks() - verifiedTick < 21) return; // VERIFY_CACHE_TICKS=20 + margin
            var back = CognitiveViewBuilder.build(f.bot, null, null);
            JsonObject backCard = structureCard(back.sceneJson(), "mc2a01_remote");
            require(context, backCard != null, "card must exist after return");
            require(context, "VERIFIED_LIVE".equals(backCard.get("knowledge").getAsString()),
                    "back inside the envelope the home must re-verify LIVE: " + backCard);
            JsonObject backIntegrity = backCard.getAsJsonObject("summary").getAsJsonObject("current_integrity");
            require(context, backIntegrity.get("missing").getAsLong() == 1,
                    "the damaged cell must now be a visible missing (was hidden while remote): " + backIntegrity);
            finish(context, f);
        });
    }

    /** 2 (STR-2 positive path): a nearby fully observable structure carries REAL counts. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a01", tickLimit = 100)
    public void mc2a01NearObservableStructureCanClaimLiveIntegrity(TestContext context) {
        Fixture f = home(context, "mc2a01_near");
        set(f.bot, f.start.add(0, 1, 3), Blocks.AIR); // damage the pillar top before the first build
        var snapshot = CognitiveViewBuilder.build(f.bot, null, null);
        JsonObject card = structureCard(snapshot.sceneJson(), "mc2a01_near");
        require(context, card != null, "card must exist");
        require(context, "VERIFIED_LIVE".equals(card.get("knowledge").getAsString()),
                "near + observable must be VERIFIED_LIVE: " + card);
        JsonObject integrity = card.getAsJsonObject("summary").getAsJsonObject("current_integrity");
        require(context, integrity.get("missing").getAsLong() == 1, "damage must be a real current missing");
        require(context, integrity.get("wrong").getAsLong() == 0, "no wrong cells in this fixture");
        // inspect integrity detail agrees with the view card (same assessment source)
        String ref = card.get("evidence_ref").getAsString();
        String detail = CognitiveInspector.materialize(f.bot, null, snapshot.gameTime(), ref, "integrity");
        JsonObject detailJson = JsonParser.parseString(detail).getAsJsonObject();
        require(context, detailJson.get("missing").getAsLong() == 1, "inspect detail must agree with view card");
        finish(context, f);
    }

    /**
     * 3 (FRESH-1): opportunity freshness is identical across view card / inspect
     * summary / inspect evidence and is a real bucket (RECENT), never the MC-2A0
     * hardcoded UNKNOWN. observeVisibleBlock only registers for the reserved body,
     * so this runs in the mc1caR21 batch with the Mc1caBot name.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caR21", tickLimit = 200)
    public void mc2a01OpportunityInspectFreshnessMatchesView(TestContext context) {
        Fixture f = ground(context, "mc2a01_fresh", "Mc1caBot");
        BlockPos ore = f.start.add(0, 1, 3);
        set(f.bot, ore, Blocks.IRON_ORE);
        InventoryAction.giveItem(f.bot, new ItemStack(Items.IRON_PICKAXE, 1));
        SemanticWorldRegistry.observeVisibleBlock(f.bot, ore, f.bot.getServerWorld().getBlockState(ore));
        String id = opportunityAt(context, f.bot, ore, "ACTIONABLE");

        var snapshot = CognitiveViewBuilder.build(f.bot, null, null);
        JsonObject card = findCard(snapshot.sceneJson(), id);
        String viewFreshness = card.get("freshness").getAsString();
        String ref = card.get("evidence_ref").getAsString();
        JsonObject summary = JsonParser.parseString(
                CognitiveInspector.materialize(f.bot, null, snapshot.gameTime(), ref, "summary")).getAsJsonObject();
        JsonObject evidence = JsonParser.parseString(
                CognitiveInspector.materialize(f.bot, null, snapshot.gameTime(), ref, "evidence")).getAsJsonObject();
        require(context, "RECENT".equals(viewFreshness),
                "just-observed opportunity must be RECENT, not the old hardcoded UNKNOWN: " + viewFreshness);
        require(context, viewFreshness.equals(summary.get("freshness").getAsString()),
                "view card and inspect summary freshness must match");
        require(context, viewFreshness.equals(evidence.get("freshness").getAsString()),
                "view card and inspect evidence freshness must match");
        finish(context, f);
    }

    /** 4 (EXEC-1): IDLE implies current_task=null; RUNNING keeps the real task name. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a01", tickLimit = 100)
    public void mc2a01IdleViewClearsCurrentTask(TestContext context) {
        Fixture f = ground(context, "mc2a01_idle");
        Task task = new MoveTask(f.bot, f.start.add(40, 0, 0)); // fake players never walk: stays RUNNING
        ExternalBodyAccess.dispatch(() -> {
            TaskManager.INSTANCE.assign(f.bot, task, TaskOrigin.of(TaskOrigin.Kind.LLM_TOOL, "mc2a01_test"));
            return null;
        });
        context.runAtEveryTick(() -> {
            if (task.state().toString().equals("PENDING")) task.tick(f.bot);
            if (TaskManager.INSTANCE.getActive(f.bot).map(t -> t == task).orElse(false)) {
                var running = CognitiveViewBuilder.build(f.bot, null, null);
                JsonObject execution = JsonParser.parseString(running.sceneJson()).getAsJsonObject()
                        .getAsJsonObject("execution");
                require(context, "RUNNING".equals(execution.get("state").getAsString()), "must be RUNNING");
                require(context, execution.get("current_task") != null && !execution.get("current_task").isJsonNull(),
                        "RUNNING must carry the real task name");
                TaskManager.INSTANCE.resetToIdle(f.bot); // status(bot) may still hold the stale name — EXEC-1 forbids leaking it
                var idle = CognitiveViewBuilder.build(f.bot, null, null);
                JsonObject idleExecution = JsonParser.parseString(idle.sceneJson()).getAsJsonObject()
                        .getAsJsonObject("execution");
                require(context, "IDLE".equals(idleExecution.get("state").getAsString()), "must be IDLE after reset");
                require(context, idleExecution.has("current_task") && idleExecution.get("current_task").isJsonNull(),
                        "IDLE must render current_task as explicit null, never a stale task name: " + idleExecution);
                finish(context, f);
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(AIPlayerEntity bot, BlockPos start) {}

    /**
     * Flat stone platform + a HOME whose baseline is exactly a two-block stone pillar
     * three blocks in front of the bot (registered with radius 3 / below 0 / above 2,
     * so the platform floor stays outside the bounds and only the pillar is captured).
     * The pillar faces the bot head-on with no corner columns, so both baseline cells
     * pass the strict six-face raycast proof — the geometry that legitimately earns
     * VERIFIED_LIVE. (Wider walls have diagonal corner cells whose face-centre rays are
     * genuinely not resolvable under strict survival — exactly why the v0.1 policy
     * must stay conservative for anything bigger.)
     */
    private static Fixture home(TestContext context, String homeId) {
        Fixture f = ground(context, homeId);
        set(f.bot, f.start.add(0, 0, 3), Blocks.STONE);
        set(f.bot, f.start.add(0, 1, 3), Blocks.STONE);
        SemanticWorldRegistry.registerHome(f.bot, homeId, 3, 0, 2).persisted().join();
        SemanticWorldRegistry.captureHome(f.bot, homeId).persisted().join();
        return f;
    }

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

    private static void teleport(AIPlayerEntity bot, BlockPos feet) {
        bot.teleport(bot.getServerWorld(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, Set.of(), 0, 0, true);
    }

    private static JsonObject structureCard(String sceneJson, String objectId) {
        for (var element : JsonParser.parseString(sceneJson).getAsJsonObject()
                .getAsJsonObject("semantic_objects").getAsJsonObject("structures").getAsJsonArray("items")) {
            JsonObject card = element.getAsJsonObject();
            if (objectId.equals(card.get("object_id").getAsString())) return card;
        }
        return null;
    }

    private static JsonObject findCard(String sceneJson, String opportunityId) {
        for (var element : JsonParser.parseString(sceneJson).getAsJsonObject()
                .getAsJsonObject("semantic_objects").getAsJsonObject("resource_opportunities").getAsJsonArray("items")) {
            JsonObject card = element.getAsJsonObject();
            if (opportunityId.equals(card.get("object_id").getAsString())) return card;
        }
        return null;
    }

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
        context.throwGameTestException("fixture ore opportunity not found at " + ore);
        return null;
    }

    private static void set(AIPlayerEntity bot, BlockPos pos, Block block) {
        bot.getServerWorld().setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) context.throwGameTestException(message);
    }

    private static void finish(TestContext context, Fixture f) {
        TaskManager.INSTANCE.resetToIdle(f.bot);
        AIPlayerManager.INSTANCE.despawn(f.bot.getServer(), f.bot.getGameProfile().getName());
        context.complete();
    }
}
