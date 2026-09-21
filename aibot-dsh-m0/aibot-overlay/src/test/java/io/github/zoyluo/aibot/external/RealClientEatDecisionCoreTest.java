package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.external.realclient.RealClientEatDecisionCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MC-RCF-1-R2 R03 deterministic regression against the REAL production eat
 * state machine ({@link RealClientEatDecisionCore.Machine}). The historical
 * defect: phase-0 main-package PICKUP fell through to the consumption
 * verification on the same tick with consumedRounds=0 and heldStart=0,
 * deterministically failing {@code client_eat_no_effect}.
 */
final class RealClientEatDecisionCoreTest {
    private static RealClientEatDecisionCore.Observed obs(
            boolean heldIsFood,int heldCount,boolean hotbarHasFood,
            boolean cursorIsFood,boolean cursorNonEmpty,boolean mainHasFood,
            boolean usingItem) {
        return new RealClientEatDecisionCore.Observed(
                heldIsFood,heldCount,hotbarHasFood,cursorIsFood,
                cursorNonEmpty,mainHasFood,usingItem);
    }

    @Test void mainPackagePickupReturnsBeforeAnyVerificationStep() {
        RealClientEatDecisionCore.Machine m=
                new RealClientEatDecisionCore.Machine();
        // R03 exact scenario: food ONLY in main package, hotbar empty,
        // cursor empty, nothing held.
        RealClientEatDecisionCore.Step step=m.tick(obs(
                false,0,false,false,false,true,false));
        assertEquals(RealClientEatDecisionCore.Step.PICKUP_MAIN,step,
                "main-package pickup must be the decision");
        assertFalse(m.terminal(),
                "the machine must NOT terminate on the pickup tick");
        // Re-evaluating the SAME (pre-sync) observation must keep preparing,
        // never verify: a fixed sleep is not a sync confirmation.
        for(int i=0;i<5;i++) {
            RealClientEatDecisionCore.Step again=m.tick(obs(
                    false,0,false,false,false,true,false));
            assertNotEquals(RealClientEatDecisionCore.Step.COMPLETE,again);
            assertNotEquals(RealClientEatDecisionCore.Step.FAIL_NO_EFFECT,again);
            assertFalse(m.terminal());
        }
    }

    @Test void mainPackageChainProgressesByObservedFactsOnly() {
        RealClientEatDecisionCore.Machine m=
                new RealClientEatDecisionCore.Machine();
        // 1) pickup main
        assertEquals(RealClientEatDecisionCore.Step.PICKUP_MAIN,
                m.tick(obs(false,0,false,false,false,true,false)));
        // 2) cursor now holds food (server-synced fact)
        assertEquals(RealClientEatDecisionCore.Step.PLACE_CURSOR_TO_HOTBAR,
                m.tick(obs(false,0,false,true,true,true,false)));
        // 3) food landed in hotbar (synced fact)
        assertEquals(RealClientEatDecisionCore.Step.SELECT_HOTBAR,
                m.tick(obs(false,0,true,false,false,true,false)));
        // 4) held becomes the food: baseline recorded, enter use window
        assertEquals(RealClientEatDecisionCore.Step.MARK_HELD,
                m.tick(obs(true,3,true,false,false,true,false)));
        assertEquals(3,m.heldStart());
        assertEquals(1,m.phase());
        // 5) press use until the server confirms using
        for(int i=0;i<4;i++)
            assertEquals(RealClientEatDecisionCore.Step.PRESS_USE,
                    m.tick(obs(true,3,true,false,false,true,false)));
        // 6) using confirmed → hold inside the window
        assertEquals(RealClientEatDecisionCore.Step.HOLD_USE,
                m.tick(obs(true,3,true,false,false,true,true)));
    }

    @Test void consumesExactlyOneItemThenStopsUsing() {
        RealClientEatDecisionCore.Machine m=
                new RealClientEatDecisionCore.Machine();
        m.tick(obs(false,0,false,false,false,true,false));
        m.tick(obs(false,0,false,true,true,true,false));
        m.tick(obs(false,0,true,false,false,true,false));
        m.tick(obs(true,3,true,false,false,true,false));
        for(int i=0;i<3;i++)
            assertEquals(RealClientEatDecisionCore.Step.PRESS_USE,
                    m.tick(obs(true,3,true,false,false,true,false)));
        // server confirms using; vanilla finishes eating one item after
        // 32 ticks: using flips true→false exactly once
        RealClientEatDecisionCore.Step step=null;
        int window=0;
        while(m.phase()==1) {
            boolean using=window<32;
            int countNow=using?3:2;
            step=m.tick(obs(true,countNow,true,false,false,true,using));
            window++;
            assertTrue(window<200,"window must be bounded");
        }
        // the very tick the use transition completes, use is released
        assertEquals(RealClientEatDecisionCore.Step.RELEASE_AND_VERIFY,step);
        assertEquals(1,m.consumedRounds());
        // verify: held 3→2, one real transition → claimed=1
        assertEquals(RealClientEatDecisionCore.Step.COMPLETE,
                m.tick(obs(true,2,true,false,false,true,false)));
        assertTrue(m.reason().contains("client_consumed=1"));
        // Terminal: no further use steps are issued after completion.
        assertEquals(RealClientEatDecisionCore.Step.COMPLETE,
                m.tick(obs(true,2,true,false,false,true,false)));
    }

    @Test void isUsingFlipWithoutHeldDecreaseFailsClosed() {
        RealClientEatDecisionCore.Machine m=
                new RealClientEatDecisionCore.Machine();
        assertEquals(RealClientEatDecisionCore.Step.MARK_HELD,
                m.tick(obs(true,2,true,false,false,true,false)));
        for(int i=0;i<3;i++)
            assertEquals(RealClientEatDecisionCore.Step.PRESS_USE,
                    m.tick(obs(true,2,true,false,false,true,false)));
        // window starts, using stays true without any true→false round
        // (cancelled externally / switched item): bound releases the window
        RealClientEatDecisionCore.Step step=null;
        int window=0;
        while(m.phase()==1) {
            step=m.tick(obs(true,2,true,false,false,true,true));
            window++;
            assertTrue(window<200,"window must be bounded");
        }
        assertEquals(RealClientEatDecisionCore.Step.RELEASE_AND_VERIFY,step);
        assertEquals(0,m.consumedRounds(),
                "isUsing staying true is NOT a consumed round");
        // R2/A08:held 计数同步滞后——verify 有界等待(RELEASE 重试),
        // 40 tick 内计数仍无变化才判 no_effect
        for(int i=0;i<39;i++)
            assertEquals(RealClientEatDecisionCore.Step.RELEASE_AND_VERIFY,
                    m.tick(obs(true,2,true,false,false,true,false)),
                    "verify waits bounded for the count sync");
        assertEquals(RealClientEatDecisionCore.Step.FAIL_NO_EFFECT,
                m.tick(obs(true,2,true,false,false,true,false)));
    }

    @Test void noFoodAnywhereFailsImmediately() {
        RealClientEatDecisionCore.Machine m=
                new RealClientEatDecisionCore.Machine();
        RealClientEatDecisionCore.Step step=m.tick(obs(
                false,0,false,false,false,false,false));
        assertEquals(RealClientEatDecisionCore.Step.FAIL_NO_FOOD,step);
        assertTrue(m.terminal());
        assertEquals("client_food_not_found",m.reason());
    }

    @Test void foreignCursorIsReturnedBeforePickup() {
        RealClientEatDecisionCore.Machine m=
                new RealClientEatDecisionCore.Machine();
        // cursor holds a foreign stack (e.g. tool swapped out of a hotbar slot)
        assertEquals(RealClientEatDecisionCore.Step.RETURN_FOREIGN_CURSOR,
                m.tick(obs(false,0,false,false,true,true,false)));
    }
}
