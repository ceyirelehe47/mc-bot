package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.external.realclient.RealClientOpportunityAdmission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MC-RCF-1-R2 R02: full refusal matrix of the REAL production admission core
 * ({@link RealClientOpportunityAdmission}) used by
 * {@code RealClientOpportunityTracker.observe}. Every refusal below must
 * mean NO durable birth — the tracker only appends births after this core
 * admits, and the sweep bypass (registration before validation) is gone.
 */
final class RealClientOpportunityAdmissionTest {
    private static RealClientOpportunityAdmission.Facts.Builder base() {
        return new RealClientOpportunityAdmission.Facts.Builder();
    }

    @Test void admittedFramePassesAllChecks() {
        RealClientOpportunityAdmission.Decision d=RealClientOpportunityAdmission
                .evaluate(base().build());
        assertTrue(d.admit());
        assertEquals("",d.refusal());
        assertFalse(d.duplicateFrame());
    }

    @Test void everySingleFactCorruptionRefusesIndividually() {
        assertFalse(RealClientOpportunityAdmission.evaluate(
                        base().crosshairPresent(false).build()).admit(),
                "no crosshair");
        assertEquals("sensor_not_present",RealClientOpportunityAdmission
                .evaluate(base().crosshairPresent(false).build()).refusal());

        assertFalse(RealClientOpportunityAdmission.evaluate(
                        base().gameSession("").build()).admit(),
                "no game session");
        assertFalse(RealClientOpportunityAdmission.evaluate(
                        base().gameSession(null).build()).admit());

        assertFalse(RealClientOpportunityAdmission.evaluate(
                        base().frameAgeMs(2001L).build()).admit(),
                "stale frame");
        assertEquals("frame_stale",RealClientOpportunityAdmission
                .evaluate(base().frameAgeMs(2001L).build()).refusal());

        assertFalse(RealClientOpportunityAdmission.evaluate(
                        base().positionDrift(2.01D).build()).admit(),
                "position drift");
        assertFalse(RealClientOpportunityAdmission.evaluate(
                        base().squaredDistance(49.01D).build()).admit(),
                "out of range");
        assertFalse(RealClientOpportunityAdmission.evaluate(
                        base().serverRayAtCrosshair(false).build()).admit(),
                "server ray misses crosshair cell");
        assertEquals("ray_mismatch",RealClientOpportunityAdmission
                .evaluate(base().serverRayAtCrosshair(false).build()).refusal());
        assertFalse(RealClientOpportunityAdmission.evaluate(
                        base().blockIdMatches(false).build()).admit(),
                "cell holds a different block");
        assertFalse(RealClientOpportunityAdmission.evaluate(
                        base().eligibleBlock(false).build()).admit(),
                "block not an eligible resource");
    }

    @Test void alreadyProcessedFrameIsASilentDuplicate() {
        RealClientOpportunityAdmission.Decision d=RealClientOpportunityAdmission
                .evaluate(base()
                        .lastProcessedGameSession("game-1")
                        .lastProcessedFrameSeq(100L)
                        .gameSession("game-1")
                        .frameSeq(100L)
                        .build());
        assertFalse(d.admit());
        assertFalse(d.duplicateFrame()==false && d.refusal().isEmpty());
        assertTrue(d.duplicateFrame(),
                "same-session frame at or below the processed seq is a duplicate");
        assertEquals("",d.refusal(),"duplicates are silent, not errors");

        // newer frame in the same session is not a duplicate
        assertTrue(RealClientOpportunityAdmission.evaluate(base()
                        .lastProcessedGameSession("game-1")
                        .lastProcessedFrameSeq(100L)
                        .gameSession("game-1")
                        .frameSeq(101L)
                        .build()).admit());
        // same seq in a NEW session is not a duplicate either
        assertTrue(RealClientOpportunityAdmission.evaluate(base()
                        .lastProcessedGameSession("game-1")
                        .lastProcessedFrameSeq(100L)
                        .gameSession("game-2")
                        .frameSeq(100L)
                        .build()).admit());
    }

    @Test void refusalOrderIsFailClosedAndStable() {
        // several facts corrupted at once: the FIRST refusal in the check
        // order wins — no combination of corrupt facts can admit
        RealClientOpportunityAdmission.Decision d=RealClientOpportunityAdmission
                .evaluate(base()
                        .crosshairPresent(false)
                        .frameAgeMs(9999L)
                        .serverRayAtCrosshair(false)
                        .build());
        assertEquals("sensor_not_present",d.refusal());
        assertFalse(d.admit());
    }

    @Test void malformedFactsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                ()->base().freshnessLimitMs(0L).build());
        assertThrows(IllegalArgumentException.class,
                ()->base().positionTolerance(-1D).build());
        assertThrows(IllegalArgumentException.class,
                ()->base().maxSquaredDistance(0D).build());
    }
}
