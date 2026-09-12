package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TaskGraphStoreTest {
    @TempDir Path temp;

    private static TaskGraphStore.SpatialRef ref(String id) {
        return new TaskGraphStore.SpatialRef("world-a","minecraft:overworld",id);
    }

    @Test void producerIsIdempotentAndClaimsPreventCompetingPlan() {
        TaskGraphStore store=new TaskGraphStore(temp.resolve("graphs.bin"),()->100L);
        Map<String,Object> first=store.planOpportunity("plan-a",ref("opp-1"));
        Map<String,Object> again=store.planOpportunity("plan-a",ref("opp-1"));
        assertEquals(first.get("graph_id"),again.get("graph_id"));
        BridgeFault conflict=assertThrows(BridgeFault.class,
                ()->store.planOpportunity("plan-b",ref("opp-1")));
        assertTrue(conflict.code.startsWith("graph_resource_claim_conflict"));
    }

    @Test void restartSuspendsRunningNodeAndNeverReplaysIt() {
        Path file=temp.resolve("restart.bin");
        TaskGraphStore a=new TaskGraphStore(file,()->100L);
        String id=(String)a.planOpportunity("plan-a",ref("opp-1")).get("graph_id");
        TaskGraphStore.Dispatch dispatch=a.prepareDispatch(id);
        a.attachExecution(dispatch,"execution-1");

        TaskGraphStore b=new TaskGraphStore(file,()->200L);
        Map<String,Object> recovered=b.inspect(id);
        assertEquals("SUSPENDED",recovered.get("state"));
        BridgeFault blocked=assertThrows(BridgeFault.class,()->b.prepareDispatch(id));
        assertEquals("graph_suspended_reconcile_or_cancel",blocked.code);

        b.reconcileSuspended(pc->new BodyBackend.GraphPostconditionResult(
                BodyBackend.GraphPostconditionState.SATISFIED,"opportunity_absent"));
        assertEquals("DONE",b.inspect(id).get("state"));
    }

    @Test void completedExecutionNeedsPostconditionBeforeDone() {
        TaskGraphStore store=TaskGraphStore.memory();
        String id=(String)store.planOpportunity("plan-a",ref("opp-1")).get("graph_id");
        TaskGraphStore.Dispatch d=store.prepareDispatch(id);store.attachExecution(d,"execution-1");
        store.executionTerminal("execution-1","completed",pc->new BodyBackend.GraphPostconditionResult(
                BodyBackend.GraphPostconditionState.UNSATISFIED,"still_present"));
        assertEquals("STALE",store.inspect(id).get("state"));
    }

    @Test void outcomeUnknownSuspendsAndCancelReleasesClaim() {
        TaskGraphStore store=TaskGraphStore.memory();
        String id=(String)store.planOpportunity("plan-a",ref("opp-1")).get("graph_id");
        TaskGraphStore.Dispatch d=store.prepareDispatch(id);store.attachExecution(d,"execution-1");
        store.executionTerminal("execution-1","outcome_unknown",pc->BodyBackend.GraphPostconditionResult.unknown("unused"));
        assertEquals("SUSPENDED",store.inspect(id).get("state"));
        store.cancel(id,"replan");
        assertEquals("CANCELLED",store.inspect(id).get("state"));
        assertDoesNotThrow(()->store.planOpportunity("plan-b",ref("opp-1")));
    }

    @Test void suspendedTerminalLossBecomesStaleNeverDone() {
        TaskGraphStore store=TaskGraphStore.memory();
        String id=(String)store.planOpportunity("plan-a",ref("opp-loss")).get("graph_id");
        TaskGraphStore.Dispatch d=store.prepareDispatch(id);store.attachExecution(d,"execution-loss");
        store.executionTerminal("execution-loss","outcome_unknown",
                pc->BodyBackend.GraphPostconditionResult.unknown("transport_lost"));
        assertEquals("SUSPENDED",store.inspect(id).get("state"));
        store.reconcileSuspended(pc->BodyBackend.GraphPostconditionResult
                .terminalUnsatisfied("durable_stale_or_loss_receipt"));
        assertEquals("STALE",store.inspect(id).get("state"));
    }

    @Test void legacyDoneWithoutDurableSuccessReceiptIsDowngraded() {
        TaskGraphStore store=TaskGraphStore.memory();
        String id=(String)store.planOpportunity("plan-a",ref("opp-old")).get("graph_id");
        TaskGraphStore.Dispatch d=store.prepareDispatch(id);store.attachExecution(d,"execution-old");
        store.executionTerminal("execution-old","completed",
                pc->BodyBackend.GraphPostconditionResult.satisfied("old_registry_absence"));
        assertEquals("DONE",store.inspect(id).get("state"));
        store.auditCompletedPostconditions(pc->BodyBackend.GraphPostconditionResult
                .unknown("no_durable_success_receipt"));
        assertEquals("SUSPENDED",store.inspect(id).get("state"));
        store.reconcileSuspended(pc->BodyBackend.GraphPostconditionResult
                .satisfied("durable_inventory_gain_receipt"));
        assertEquals("DONE",store.inspect(id).get("state"));
    }

    @Test void truncatedFinalDependencyStringFailsClosedEvenWhenPrefixIsAnotherValidNode() throws Exception {
        Path file=temp.resolve("truncated-dependency.bin");
        TaskGraphStore store=new TaskGraphStore(file,()->100L);
        TaskGraphStore.SpatialRef a=ref("a"), ax=ref("ax"), z=ref("z");
        store.createFragment("graph-trunc","producer-trunc","plan-trunc","TEST",List.of(
                new TaskGraphStore.NodeSpec("a",Set.of(),"mine_opportunity",
                        JsonOutput.encode(Map.of("id","a")),a,
                        new TaskGraphStore.Postcondition("OPPORTUNITY_RESOLVED",a),a.claimKey("opportunity")),
                new TaskGraphStore.NodeSpec("aX",Set.of(),"mine_opportunity",
                        JsonOutput.encode(Map.of("id","ax")),ax,
                        new TaskGraphStore.Postcondition("OPPORTUNITY_RESOLVED",ax),ax.claimKey("opportunity")),
                new TaskGraphStore.NodeSpec("z",Set.of("aX"),"mine_opportunity",
                        JsonOutput.encode(Map.of("id","z")),z,
                        new TaskGraphStore.Postcondition("OPPORTUNITY_RESOLVED",z),z.claimKey("opportunity"))));
        byte[] bytes=Files.readAllBytes(file);
        Files.write(file,Arrays.copyOf(bytes,bytes.length-1)); // "aX" -> "a" if short read were accepted
        BridgeFault fault=assertThrows(BridgeFault.class,
                ()->new TaskGraphStore(file,()->200L));
        assertEquals("task_graph_store_invalid",fault.code);
    }
}
