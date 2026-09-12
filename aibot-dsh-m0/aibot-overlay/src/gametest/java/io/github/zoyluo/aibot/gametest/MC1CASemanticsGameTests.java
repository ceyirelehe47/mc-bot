package io.github.zoyluo.aibot.gametest;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.BreakPolicy;
import io.github.zoyluo.aibot.external.ExternalBodyAccess;
import io.github.zoyluo.aibot.external.NaturalTreeClassifier;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * MC-1C-A structure-semantics regression gates.
 *
 * Requires the gradle run to carry AIBOT_EXTERNAL_BOT=Mc1caBot so the spawned body is a reserved
 * external body; each test activates the reservation itself (idempotent) and registers unique
 * semantic ids so batch ordering never couples tests together.
 */
public final class MC1CASemanticsGameTests implements FabricGameTest {
    private static final String BOT = "Mc1caBot";

    /** GT-1 protected home blocks are rejected by the ordinary mining entrypoint. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caSemantics", tickLimit = 200)
    public void gt1ProtectedHomeBlocksAreRejected(TestContext context) {
        Fixture fixture = fixture(context, 4);
        AIPlayerEntity bot = fixture.bot();
        SemanticWorldRegistry.registerHome(bot, "gt1_home", 2, 1, 3).persisted().join();
        BlockPos pillar = fixture.start().east(1);
        set(bot, pillar, Blocks.OAK_LOG);

        ActionResult result = MiningAction.startMining(bot, pillar, Direction.UP);

        require(context, result.isFailed(), "protected HOME mining must fail, got " + result);
        require(context, result.reason().contains("protected_structure:gt1_home"),
                "rejection must name the structure, got " + result.reason());
        require(context, bot.getServerWorld().getBlockState(pillar).isOf(Blocks.OAK_LOG),
                "protected block must be unchanged");
        finish(context, fixture);
    }

    /** GT-2 an active SAFETY origin overrides structure protection; clearing it restores the gate. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caSemantics", tickLimit = 200)
    public void gt2SafetyOriginOverridesBreakPolicy(TestContext context) {
        Fixture fixture = fixture(context, 4);
        AIPlayerEntity bot = fixture.bot();
        SemanticWorldRegistry.registerHome(bot, "gt2_home", 2, 1, 3).persisted().join();
        BlockPos cell = fixture.start().east(1);
        set(bot, cell, Blocks.OAK_LOG);

        TaskManager.INSTANCE.assign(bot, new GatherQuotaTask(Items.DIAMOND, 1), TaskOrigin.safety("gt2"));
        BreakPolicy.Decision safetyDecision = BreakPolicy.decide(bot, cell);
        TaskManager.INSTANCE.resetToIdle(bot);
        BreakPolicy.Decision ordinaryDecision = BreakPolicy.decide(bot, cell);

        require(context, safetyDecision.allowed() && "safety_override".equals(safetyDecision.reason()),
                "SAFETY origin must override protection, got " + safetyDecision);
        require(context, !ordinaryDecision.allowed() && ordinaryDecision.reason().contains("protected_structure:gt2_home"),
                "after the SAFETY task clears, protection must fail closed again, got " + ordinaryDecision);
        finish(context, fixture);
    }

    /** GT-3 structural logs inside a protected structure are never natural-tree candidates. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caSemantics", tickLimit = 200)
    public void gt3StructuralLogsAreNotTreeCandidates(TestContext context) {
        Fixture fixture = fixture(context, 4);
        AIPlayerEntity bot = fixture.bot();
        SemanticWorldRegistry.registerHome(bot, "gt3_home", 2, 1, 3).persisted().join();
        BlockPos log = fixture.start().east(1);
        set(bot, log, Blocks.OAK_LOG);
        set(bot, log.up(), Blocks.OAK_LOG);
        set(bot, log.up().north(), Blocks.OAK_LEAVES);
        set(bot, log.up().east(), Blocks.OAK_LEAVES);
        set(bot, log.up().south(), Blocks.OAK_LEAVES);
        set(bot, log.up().west(), Blocks.OAK_LEAVES);
        set(bot, log.down(), Blocks.DIRT);

        require(context, !NaturalTreeClassifier.isNaturalTreeLog(bot, log),
                "decorative log cluster inside HOME must not classify as a natural tree");
        require(context, !NaturalTreeClassifier.isHarvestCandidate(bot, log),
                "protected structural log must not be a harvest candidate");
        finish(context, fixture);
    }

    /** GT-4 a dirt-rooted log cluster with leaves outside any structure is a natural tree. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caSemantics", tickLimit = 200)
    public void gt4NaturalTreeCandidate(TestContext context) {
        Fixture fixture = fixture(context, 6);
        AIPlayerEntity bot = fixture.bot();
        BlockPos base = fixture.start().east(6);
        set(bot, base.down(), Blocks.DIRT);
        set(bot, base, Blocks.OAK_LOG);
        set(bot, base.up(), Blocks.OAK_LOG);
        set(bot, base.up().north(), Blocks.OAK_LEAVES);
        set(bot, base.up().east(), Blocks.OAK_LEAVES);
        set(bot, base.up().south(), Blocks.OAK_LEAVES);
        set(bot, base.up().west(), Blocks.OAK_LEAVES);

        require(context, NaturalTreeClassifier.isNaturalTreeLog(bot, base),
                "rooted 2-log cluster with 4 leaves outside HOME must classify as a natural tree");
        require(context, NaturalTreeClassifier.isHarvestCandidate(bot, base),
                "natural tree log must stay a harvest candidate for the reserved body");
        finish(context, fixture);
    }

    /** GT-5 leafless vertical logs fail closed even on dirt. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caSemantics", tickLimit = 200)
    public void gt5AmbiguousLeaflessLogsFailClosed(TestContext context) {
        Fixture fixture = fixture(context, 6);
        AIPlayerEntity bot = fixture.bot();
        BlockPos base = fixture.start().east(6);
        set(bot, base.down(), Blocks.DIRT);
        set(bot, base, Blocks.OAK_LOG);
        set(bot, base.up(), Blocks.OAK_LOG);
        set(bot, base.up().up(), Blocks.OAK_LOG);

        require(context, !NaturalTreeClassifier.isNaturalTreeLog(bot, base),
                "leafless dirt log column must fail closed as a resource");
        require(context, !NaturalTreeClassifier.isHarvestCandidate(bot, base),
                "leafless ambiguous log must not be a harvest candidate");
        finish(context, fixture);
    }

    /** GT-6 deterministic farm summary: mature / immature / empty farmland counted from BlockState. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caSemantics", tickLimit = 300)
    public void gt6FarmSummaryCountsCropsByState(TestContext context) {
        Fixture fixture = fixture(context, 6);
        AIPlayerEntity bot = fixture.bot();
        BlockPos ground = fixture.start().east(2);
        farmland(bot, ground, 7);          // mature wheat
        farmland(bot, ground.east(), 2);   // immature wheat
        farmland(bot, ground.east(2), 5);  // immature wheat
        set(bot, ground.east(3), Blocks.FARMLAND); // empty farmland

        JsonObject semantic = SemanticWorldRegistry.observe(bot);
        JsonObject candidate = semantic.getAsJsonObject("nearby_farm_candidate");
        require(context, candidate != null, "farm next to the body must be observable, got " + semantic);
        require(context, "minecraft:wheat".equals(candidate.get("crop").getAsString()),
                "crop must be detected as wheat, got " + candidate.get("crop"));
        require(context, candidate.get("farmland").getAsInt() == 4,
                "expected 4 farmland, got " + candidate.get("farmland"));
        require(context, candidate.get("mature").getAsInt() == 1,
                "expected 1 mature wheat, got " + candidate.get("mature"));
        require(context, candidate.get("immature").getAsInt() == 2,
                "expected 2 immature wheat, got " + candidate.get("immature"));
        require(context, candidate.get("empty_farmland").getAsInt() == 1,
                "expected 1 empty farmland, got " + candidate.get("empty_farmland"));
        finish(context, fixture);
    }

    /** GT-7 registry save/reload round-trip and malformed-JSON fail-closed behavior. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "mc1caSemantics", tickLimit = 300)
    public void gt7RegistryPersistenceAndFailClosedReload(TestContext context) {
        Fixture fixture = fixture(context, 4);
        AIPlayerEntity bot = fixture.bot();
        BlockPos farmLand = fixture.start().east(2);
        set(bot, farmLand, Blocks.FARMLAND);
        bot.getServerWorld().setBlockState(farmLand.up(),
                Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, 7), Block.NOTIFY_ALL);
        SemanticWorldRegistry.registerHome(bot, "gt7_home", 2, 1, 3).persisted().join();
        SemanticWorldRegistry.registerFarm(bot, "gt7_farm", 2, "wheat").persisted().join();
        BlockPos inside = fixture.start().east(1);

        MinecraftServer server = bot.getServer();
        Path file = server.getSavePath(WorldSavePath.ROOT)
                .resolve("aibot/external-semantics-" + BOT.toLowerCase(Locale.ROOT) + ".json");
        try {
            // 1. clean reload restores both registrations
            SemanticWorldRegistry.stop();
            SemanticWorldRegistry.start(server, BOT);
            require(context, SemanticWorldRegistry.protectionReason(bot, inside) != null
                            && SemanticWorldRegistry.protectionReason(bot, inside).contains("gt7_home"),
                    "HOME protection must survive a registry reload");
            require(context, SemanticWorldRegistry.farm(bot, "gt7_farm").isPresent(),
                    "farm registration must survive a registry reload");

            // 2. malformed registry fails closed and clears state
            String validSnapshot = Files.readString(file, StandardCharsets.UTF_8);
            Files.writeString(file, "{\"version\":1,\"structures\":\"not-an-array\"", StandardCharsets.UTF_8);
            SemanticWorldRegistry.stop();
            boolean failedClosed = false;
            try {
                SemanticWorldRegistry.start(server, BOT);
            } catch (IOException expected) {
                failedClosed = true;
            }
            require(context, failedClosed, "malformed registry must fail closed on load");
            require(context, SemanticWorldRegistry.protectionReason(bot, inside) == null,
                    "malformed registry must clear in-memory protection");

            // 3. restore the valid snapshot so later lifecycle stages see a healthy registry
            Files.writeString(file, validSnapshot, StandardCharsets.UTF_8);
            SemanticWorldRegistry.stop();
            SemanticWorldRegistry.start(server, BOT);
            require(context, SemanticWorldRegistry.farm(bot, "gt7_farm").isPresent(),
                    "restored registry must serve farm lookups again");
        } catch (IOException failure) {
            context.throwGameTestException("gt7 io failure: " + failure + " / cause=" + failure.getCause());
        }
        finish(context, fixture);
    }

    private static void set(AIPlayerEntity bot, BlockPos pos, Block block) {
        bot.getServerWorld().setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static void farmland(AIPlayerEntity bot, BlockPos ground, int age) {
        set(bot, ground, Blocks.FARMLAND);
        bot.getServerWorld().setBlockState(ground.up(),
                Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, age), Block.NOTIFY_ALL);
    }

    private static Fixture fixture(TestContext context, int east) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -2; dx <= east; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), BOT, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + BOT));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        ExternalBodyAccess.activateReservation();
        return new Fixture(bot, start);
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private static void finish(TestContext context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), BOT);
        context.complete();
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start) {}
}
