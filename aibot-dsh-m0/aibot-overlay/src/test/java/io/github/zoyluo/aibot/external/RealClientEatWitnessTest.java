package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.external.realclient.RealClientEatWitness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MC-RCF-1-R3 F04: server-side consumption witness unit facts. The witness
 * is the only completion-proof source for {@code eatSnapshot}; entries exist
 * ONLY when the mixin observes real game completion processing.
 */
final class RealClientEatWitnessTest {
    private static final String P1=
            "11111111-1111-1111-1111-111111111111";
    private static final String P2=
            "22222222-2222-2222-2222-222222222222";

    @Test void countsOnlyMatchingPlayerItemAndWindow() {
        long t0=System.currentTimeMillis()-5;
        RealClientEatWitness.record(P1,"minecraft:bread",
                "MAIN_HAND","minecraft:overworld",1000L);
        RealClientEatWitness.record(P1,"minecraft:bread",
                "MAIN_HAND","minecraft:overworld",1001L);
        RealClientEatWitness.record(P1,"minecraft:apple",
                "OFF_HAND","minecraft:overworld",1002L);
        RealClientEatWitness.record(P2,"minecraft:bread",
                "MAIN_HAND","minecraft:overworld",1003L);
        assertEquals(2,RealClientEatWitness.countFor(
                P1,"minecraft:bread",t0));
        assertEquals(1,RealClientEatWitness.countFor(
                P1,"minecraft:apple",t0));
        assertEquals(1,RealClientEatWitness.countFor(
                P2,"minecraft:bread",t0));
        assertEquals(0,RealClientEatWitness.countFor(
                P1,"minecraft:cooked_beef",t0));
        long later=System.currentTimeMillis()+1;
        assertEquals(0,RealClientEatWitness.countFor(
                P1,"minecraft:bread",later));
    }

    @Test void ringIsBounded() {
        for(int i=0;i<RealClientEatWitness.MAX_ENTRIES+20;i++)
            RealClientEatWitness.record(P2,"minecraft:apple",
                    "MAIN_HAND","minecraft:overworld",2000L+i);
        assertTrue(RealClientEatWitness.snapshot().size()
                <=RealClientEatWitness.MAX_ENTRIES,
                "witness ring must stay bounded");
    }
}
