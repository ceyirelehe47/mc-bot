package io.github.zoyluo.aibot.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.ExternalBodyAccess;
import io.github.zoyluo.aibot.external.MinecraftBodyBackend;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.external.cognition.CognitiveViewBuilder;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MC-2A0.1F final-closure required case (AUTO-OBS-1..3 / COG-AQ-1..4).
 *
 * The automatic observation/cognition refresh chain (BridgeKernel.tick →
 * MinecraftBodyBackend.observeJson → refreshCaches → semantic snapshot →
 * CognitiveViewBuilder) must NEVER remote-scan a registered Structure's current
 * block state — not "scan and hide it", the raw read itself must not happen.
 * This is proven with the test-only counter
 * {@link SemanticWorldRegistry#INTEGRITY_RAW_READ_SCANS}: after moving far outside
 * the verification envelope and resetting the counters, 45+ ticks of the real
 * automatic entry points (backend.observeJson + backend.cognitiveSnapshot, exactly
 * what BridgeKernel.tick drives each tick) plus a view build per tick — long
 * enough to expire the 10-tick semantic cache and the 20-tick INTEGRITY cache, so
 * any regressed omniscient path would be forced to rescan — must leave the
 * counter at exactly 0, keep the durable structure identity visible as
 * LAST_KNOWN (never VERIFIED_LIVE), and returning inside the envelope must still
 * re-earn VERIFIED_LIVE through the legal proof-before-read path (which itself
 * needs no omniscient scan).
 *
 * Same environment contract as the MC2A0/MC2A01 batches: fake players are not
 * world-ticked (R2.1 lesson) so distance changes are direct teleports.
 */
public final class MC2A01FClosureGameTests implements FabricGameTest {
    private static final String BOT = "Mc2a01fBot";

    // Independent batchId: fabric runs the tests of one batch in parallel on adjacent
    // cells ~13 blocks apart, and this fixture's platform clear spans ±20 blocks — sharing
    // the mc2a01 batch would erase the neighbouring test's HOME pillar mid-run (the exact
    // flake seen when this test reused batchId "mc2a01"). A dedicated batch runs after the
    // whole mc2a01 batch finishes, so its ground() only clears already-completed fixtures.
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc2a01f", tickLimit = 300)
    public void mc2a01fAutomaticViewNeverTriggersRemoteStructureIntegrityRead(TestContext context) {
        Fixture f = home(context, "mc2a01f_auto");
        var near = CognitiveViewBuilder.build(f.bot, null, null);
        JsonObject nearCard = structureCard(near.sceneJson(), "mc2a01f_auto");
        require(context, nearCard != null, "structure card must exist");
        require(context, "VERIFIED_LIVE".equals(nearCard.get("knowledge").getAsString()),
                "inside the envelope a fully observable home must be VERIFIED_LIVE: " + nearCard);

        // Far outside the verification envelope, reset the raw-read counter, then drive the
        // AUTOMATIC refresh entry points across enough ticks to expire both caches.
        teleport(f.bot, f.start.add(60, 0, 60));
        SemanticWorldRegistry.INTEGRITY_RAW_READ_SCANS.set(0);
        MinecraftBodyBackend backend = new MinecraftBodyBackend(f.bot.getServer(), f.bot.getGameProfile().getName());

        long startTick = f.bot.getServer().getTicks();
        AtomicLong returnedAtTick = new AtomicLong(-1);
        context.runAtEveryTick(() -> {
            // The exact methods BridgeKernel.tick drives every server tick (observation +
            // periodic cognitive snapshot). Driving them directly is equivalent to letting
            // the kernel tick: the automatic refresh logic lives inside the backend.
            if (backend.ready()) {
                backend.observeJson();
                backend.cognitiveSnapshot(null);
            }
            var snapshot = CognitiveViewBuilder.build(f.bot, null, null);
            long now = f.bot.getServer().getTicks();
            if (now - startTick < 45) return;
            if (returnedAtTick.get() < 0) {
                require(context, SemanticWorldRegistry.INTEGRITY_RAW_READ_SCANS.get() == 0,
                        "automatic tick/observe/view refresh must NEVER remote-scan structure "
                                + "integrity: " + SemanticWorldRegistry.INTEGRITY_RAW_READ_SCANS.get() + " scans happened");
                JsonObject card = structureCard(snapshot.sceneJson(), "mc2a01f_auto");
                require(context, card != null, "durable identity must keep the structure visible while remote");
                require(context, "LAST_KNOWN".equals(card.get("knowledge").getAsString()),
                        "remote knowledge must stay LAST_KNOWN, never VERIFIED_LIVE: " + card);
                require(context, !"LIVE".equals(card.get("freshness").getAsString()),
                        "remote freshness must never be LIVE: " + card);
                require(context, card.getAsJsonObject("summary").get("baseline_cells").getAsLong() > 0,
                        "durable baseline_cells fact must remain known: " + card);
                returnedAtTick.set(now);
                teleport(f.bot, f.start);
                return;
            }
            if (now - returnedAtTick.get() < 25) return; // VERIFY_CACHE_TICKS=20 + margin
            JsonObject backCard = structureCard(snapshot.sceneJson(), "mc2a01f_auto");
            require(context, backCard != null, "card must exist after return");
            require(context, "VERIFIED_LIVE".equals(backCard.get("knowledge").getAsString()),
                    "back inside the envelope the legal proof path must re-verify LIVE: " + backCard);
            require(context, SemanticWorldRegistry.INTEGRITY_RAW_READ_SCANS.get() == 0,
                    "the legal LIVE re-verification (proof-before-read) must not need omniscient scans");
            finish(context, f);
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(AIPlayerEntity bot, BlockPos start) {}

    /** Same two-block pillar HOME fixture as the MC2A01 batch (fully provable under strict rays). */
    private static Fixture home(TestContext context, String homeId) {
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
        AIPlayerEntity bot = spawnOn(context, world, start, BOT);
        world.setBlockState(start.add(0, 0, 3), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.add(0, 1, 3), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        SemanticWorldRegistry.registerHome(bot, homeId, 3, 0, 2).persisted().join();
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

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) context.throwGameTestException(message);
    }

    private static void finish(TestContext context, Fixture f) {
        io.github.zoyluo.aibot.task.TaskManager.INSTANCE.resetToIdle(f.bot);
        AIPlayerManager.INSTANCE.despawn(f.bot.getServer(), f.bot.getGameProfile().getName());
        context.complete();
    }
}
