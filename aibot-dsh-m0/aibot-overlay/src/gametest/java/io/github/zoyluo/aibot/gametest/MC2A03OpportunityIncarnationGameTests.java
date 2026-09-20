package io.github.zoyluo.aibot.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.BridgeJournal;
import io.github.zoyluo.aibot.external.ExternalBodyAccess;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** MC-2A Graph R1.1: terminal opportunity evidence is scoped to one physical incarnation. */
public final class MC2A03OpportunityIncarnationGameTests implements FabricGameTest {
    private static final String BOT = "Mc1caBot";

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "mc2a03_opportunity_incarnation", tickLimit = 200)
    public void mc2a03SameCellSameOreGetsNewIncarnationAndOldReceiptCannotDeleteIt(TestContext context) {
        Fixture f = fixture(context);
        BlockPos ore = f.start.east(2);
        set(f.world, ore, Blocks.IRON_ORE);

        SemanticWorldRegistry.observeVisibleBlock(f.bot, ore, f.world.getBlockState(ore));
        String first = opportunityIdAt(f.bot, ore);
        require(context, !first.isBlank(), "first opportunity incarnation missing");
        SemanticWorldRegistry.observeVisibleBlock(f.bot, ore, f.world.getBlockState(ore));
        String firstAgain = opportunityIdAt(f.bot, ore);
        require(context, first.equals(firstAgain),
                "ordinary re-observation minted a new id for the same active incarnation");

        // Terminalize incarnation #1, then make the same block type physically exist at the same
        // coordinates again. This must be a new object, not a revival of the old object id.
        SemanticWorldRegistry.markOpportunityStale(f.bot, first, "fixture_first_incarnation_terminal");
        set(f.world, ore, Blocks.AIR);
        set(f.world, ore, Blocks.IRON_ORE);
        SemanticWorldRegistry.observeVisibleBlock(f.bot, ore, f.world.getBlockState(ore));
        String second = opportunityIdAt(f.bot, ore);

        require(context, !first.equals(second),
                "same-cell same-block reappearance reused terminal opportunity id " + first);
        require(context, SemanticWorldRegistry.opportunity(f.bot, first).isEmpty(),
                "terminal incarnation unexpectedly active");
        require(context, SemanticWorldRegistry.opportunity(f.bot, second).isPresent(),
                "new incarnation not active");

        // Reproduce startup reconciliation against a journal that only knows incarnation #1.
        // The new incarnation must survive because the durable receipt's object id is different.
        Path journalPath = null;
        try {
            journalPath = Files.createTempFile("mc2a03-incarnation-", ".journal");
            try (BridgeJournal journal = new BridgeJournal(journalPath, () -> 1L)) {
                Map<String,String> fields = new LinkedHashMap<>();
                fields.put("kind", "resource_opportunity_stale");
                fields.put("execution_id", "gt-incarnation-1");
                fields.put("opportunity_id", first);
                fields.put("world_id", SemanticWorldRegistry.worldId());
                fields.put("dimension", f.world.getRegistryKey().getValue().toString());
                fields.put("payload", "{}");
                journal.append(fields);
                int removed = SemanticWorldRegistry.reconcileOpportunityTerminalReceipts(journal);
                require(context, removed == 0,
                        "old incarnation receipt deleted current incarnation");
            }
        } catch (Exception failure) {
            context.throwGameTestException("incarnation journal fixture failed: " + failure);
            return;
        } finally {
            if (journalPath != null) try { Files.deleteIfExists(journalPath); } catch (Exception ignored) {}
        }

        require(context, SemanticWorldRegistry.opportunity(f.bot, second).isPresent(),
                "current incarnation vanished after old-receipt reconcile");

        // R1.1 fixture hygiene: terminalize the live incarnation and remove the ore block, so the
        // shared per-world registry stays clean for later batches (mc1caR2's omniscient observe
        // asserts the whole overworld contains only its own fixture ore).
        SemanticWorldRegistry.markOpportunityStale(f.bot, second, "fixture_incarnation_cleanup");
        set(f.world, ore, Blocks.AIR);
        require(context, SemanticWorldRegistry.opportunity(f.bot, second).isEmpty(),
                "cleanup left the incarnation active");
        finish(context, f);
    }

    private record Fixture(ServerWorld world, AIPlayerEntity bot, BlockPos start) {}

    private static Fixture fixture(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -4; dx <= 8; dx++) for (int dz = -4; dz <= 4; dz++) {
            BlockPos feet = start.add(dx, 0, dz);
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        AIPlayerManager.INSTANCE.despawn(world.getServer(), BOT);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(world.getServer(), BOT, world,
                Vec3d.ofBottomCenter(start), 0.0F, 0.0F, GameMode.SURVIVAL).orElseThrow();
        bot.teleport(world, start.getX() + .5D, start.getY(), start.getZ() + .5D,
                Set.of(), 0, 0, true);
        ExternalBodyAccess.activateReservation();
        return new Fixture(world, bot, start);
    }

    private static String opportunityIdAt(AIPlayerEntity bot, BlockPos pos) {
        JsonArray opportunities = SemanticWorldRegistry.observeBounded(bot)
                .getAsJsonArray("resource_opportunities");
        for (JsonElement element : opportunities) {
            JsonObject o = element.getAsJsonObject();
            JsonObject p = o.getAsJsonObject("position");
            if (p.get("x").getAsInt() == pos.getX()
                    && p.get("y").getAsInt() == pos.getY()
                    && p.get("z").getAsInt() == pos.getZ()) {
                return o.get("id").getAsString();
            }
        }
        return "";
    }

    private static void set(ServerWorld world, BlockPos pos, Block block) {
        world.setBlockState(pos, block.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) context.throwGameTestException(message);
    }

    private static void finish(TestContext context, Fixture f) {
        AIPlayerManager.INSTANCE.despawn(f.bot.getServer(), BOT);
        context.complete();
    }
}
