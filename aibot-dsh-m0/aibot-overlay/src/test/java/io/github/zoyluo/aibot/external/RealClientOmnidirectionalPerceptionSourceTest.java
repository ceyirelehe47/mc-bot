package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class RealClientOmnidirectionalPerceptionSourceTest {
    private static final Path REAL=Path.of(
            "src/main/java/io/github/zoyluo/aibot/external/realclient");

    private static String read(String name)throws Exception {
        return Files.readString(REAL.resolve(name));
    }

    @Test void defaultModeIsOmniAndStrictModeIsExplicit()
            throws Exception {
        String source=read("RealClientOmnidirectionalPerception.java");
        assertTrue(source.contains(
                "AIBOT_REAL_CLIENT_PERCEPTION_MODE\",\"omni_semantic\""));
        assertTrue(source.contains("OMNI_SEMANTIC(\"omni_semantic\",360)"));
        assertTrue(source.contains(
                "STRICT_PLAYER_FOV(\"strict_player_fov\",110)"));
    }

    @Test void entityRadiusIsEuclideanAndAppliedBeforeCandidateBudget()
            throws Exception {
        String source=read("RealClientOmnidirectionalPerception.java");
        int radiusGate=source.indexOf(
                "withinEntityRadius(player,entity,radius)");
        int candidateBudget=source.indexOf(
                "if(evaluated++>=ENTITY_CANDIDATE_LIMIT)");
        assertTrue(radiusGate>=0,
                "entity scan must apply the exact-radius predicate");
        assertTrue(candidateBudget>radiusGate,
                "out-of-radius AABB candidates must be removed before "
                        +"consuming the bounded candidate budget");
        assertTrue(source.contains(
                "target.squaredDistanceTo(player.getPos())<=radius*radius"));
    }

    @Test void currentEntityStateRequiresServerLineOfSight()
            throws Exception {
        String source=read("RealClientOmnidirectionalPerception.java");
        assertTrue(source.contains("visibleEntity(player,entity)"));
        assertTrue(source.contains("unobstructed("));
        assertTrue(source.contains("RaycastContext.ShapeType.COLLIDER"));
        assertTrue(source.contains("hit.getType()==HitResult.Type.MISS"));
        assertFalse(source.contains("player.canSee(entity) ||"));
    }

    @Test void visibleBlocksRequireExactRaycastHit()
            throws Exception {
        String source=read("RealClientOmnidirectionalPerception.java");
        assertTrue(source.contains("visibleBlock(player,candidate.pos())"));
        assertTrue(source.contains(
                "blockHit.getBlockPos().equals(pos)"));
        assertTrue(source.contains(
                "\"actionability\",\"awareness_only\""));
    }

    @Test void awarenessHasNoPhysicalMutationPath()
            throws Exception {
        String source=read("RealClientOmnidirectionalPerception.java");
        assertFalse(source.contains("attackBlock("));
        assertFalse(source.contains("clickSlot("));
        assertFalse(source.contains("interactBlock("));
        assertFalse(source.contains("sendCommand("));
        assertFalse(source.contains("TaskGraphStore"));
        assertFalse(source.contains("resource_opportunity_birth"));
    }

    @Test void memoryIsBoundedFreshnessTaggedAndSessionReset()
            throws Exception {
        String source=read("RealClientOmnidirectionalPerception.java");
        assertTrue(source.contains("ENTITY_MEMORY_CAPACITY=128"));
        assertTrue(source.contains("BLOCK_MEMORY_CAPACITY=128"));
        assertTrue(source.contains("tick-entry.getValue().lastSeenTick"));
        assertTrue(source.contains("\"CURRENT_VISIBLE\":\"LAST_KNOWN\""));
        assertTrue(source.contains("!gameSession.equals(boundSession)"));
        assertTrue(source.contains("reset(gameSession,dimension)"));
    }

    @Test void viewIsBoundedAndNamesAreUntrustedData()
            throws Exception {
        String source=read("RealClientOmnidirectionalPerception.java");
        assertTrue(source.contains("ENTITY_VIEW_LIMIT=20"));
        assertTrue(source.contains("BLOCK_VIEW_LIMIT=16"));
        assertTrue(source.contains("\"origin\",\"entity_name\""));
        assertTrue(source.contains("\"trust\",\"untrusted_data\""));
        assertTrue(source.contains("\"truncated\""));
        assertTrue(source.contains("\"omitted_count\""));
        assertFalse(source.contains("candidates.size()-ENTITY_CANDIDATE_LIMIT"));
        assertFalse(source.contains("candidates.size()-BLOCK_CANDIDATE_LIMIT"));
        assertTrue(source.contains(
                "candidate_evaluation_is_bounded_and_may_omit_unseen_objects"));
    }

    @Test void backendTicksPerceptionAndLocalInspectUsesIt()
            throws Exception {
        String backend=read("RealClientBodyBackend.java");
        assertTrue(backend.contains(
                "private final RealClientOmnidirectionalPerception perception"));
        assertTrue(backend.contains(
                "perception.tick(player,sensor.gameSession())"));
        assertTrue(backend.contains("perception.snapshot(current)"));
        assertTrue(backend.contains(".localWire(radius,detail==null?"));
        assertTrue(backend.contains("perception.clear()"));
        assertFalse(backend.contains(
                "real_client_mvp_exports_self_and_server_validated_crosshair_only"));
    }

    @Test void cognitiveViewExportsPhysicalFacingAndSpatialAwareness()
            throws Exception {
        String builder=read("RealClientCognitiveViewBuilder.java");
        assertTrue(builder.contains("\"facing\""));
        assertTrue(builder.contains("\"spatial_awareness\""));
        assertTrue(builder.contains("perception.sceneWire()"));
        assertFalse(builder.contains(
                "real_client_mvp_does_not_export_visual_entity_list"));
    }

    @Test void directionalCrosshairAuthorityRemainsSeparate()
            throws Exception {
        String perception=read("RealClientOmnidirectionalPerception.java");
        String backend=read("RealClientBodyBackend.java");
        assertTrue(perception.contains(
                "precise_actions_require_directional_client_crosshair"));
        assertTrue(backend.contains(
                "sensor\",\"client_crosshair_server_validated"));
        assertTrue(backend.contains("RealClientOpportunityTracker"));
    }
}
