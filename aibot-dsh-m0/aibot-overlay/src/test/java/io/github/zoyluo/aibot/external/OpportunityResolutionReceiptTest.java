package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class OpportunityResolutionReceiptTest {
    @TempDir Path temp;
    private static final TaskGraphStore.SpatialRef REF =
            new TaskGraphStore.SpatialRef("world-a","minecraft:overworld","opp-1");

    @Test void absenceAloneIsNotAReceipt() throws Exception {
        try(BridgeJournal journal=new BridgeJournal(temp.resolve("none.journal"),()->1L)) {
            assertTrue(BridgeKernel.durableOpportunityResolution(journal,REF).isEmpty());
        }
    }

    @Test void durableConsumedReceiptProvesSuccessAndStaleReceiptProvesTerminalLoss() throws Exception {
        try(BridgeJournal consumed=new BridgeJournal(temp.resolve("consumed.journal"),()->1L)) {
            consumed.append(fields("resource_opportunity_consumed"));
            var result=BridgeKernel.durableOpportunityResolution(consumed,REF).orElseThrow();
            assertEquals(BodyBackend.GraphPostconditionState.SATISFIED,result.state());
        }
        try(BridgeJournal stale=new BridgeJournal(temp.resolve("stale.journal"),()->1L)) {
            stale.append(fields("resource_opportunity_stale"));
            var result=BridgeKernel.durableOpportunityResolution(stale,REF).orElseThrow();
            assertEquals(BodyBackend.GraphPostconditionState.TERMINAL_UNSATISFIED,result.state());
        }
    }

    @Test void receiptIsScopedByWorldDimensionAndObject() throws Exception {
        try(BridgeJournal journal=new BridgeJournal(temp.resolve("scope.journal"),()->1L)) {
            Map<String,String> wrong=fields("resource_opportunity_consumed");
            wrong.put("world_id","other-world");
            journal.append(wrong);
            assertTrue(BridgeKernel.durableOpportunityResolution(journal,REF).isEmpty());
        }
    }

    @Test void terminalReceiptNeverCrossesOpportunityIncarnationsAtSameCell() throws Exception {
        var first=new TaskGraphStore.SpatialRef("world-a","minecraft:overworld","ore_same_cell_inc1");
        var second=new TaskGraphStore.SpatialRef("world-a","minecraft:overworld","ore_same_cell_inc2");
        try(BridgeJournal journal=new BridgeJournal(temp.resolve("incarnation.journal"),()->1L)) {
            journal.append(fields("resource_opportunity_stale",first.objectId()));
            var oldResult=BridgeKernel.durableOpportunityResolution(journal,first).orElseThrow();
            assertEquals(BodyBackend.GraphPostconditionState.TERMINAL_UNSATISFIED,oldResult.state());
            assertTrue(BridgeKernel.durableOpportunityResolution(journal,second).isEmpty(),
                    "old terminal receipt must not apply to a later opportunity incarnation");
        }
    }

    private static Map<String,String> fields(String kind) {
        return fields(kind,"opp-1");
    }

    private static Map<String,String> fields(String kind,String opportunityId) {
        Map<String,String> out=new LinkedHashMap<>();
        out.put("kind",kind);
        out.put("execution_id","e");
        out.put("opportunity_id",opportunityId);
        out.put("world_id","world-a");
        out.put("dimension","minecraft:overworld");
        out.put("payload","{}");
        return out;
    }
}
