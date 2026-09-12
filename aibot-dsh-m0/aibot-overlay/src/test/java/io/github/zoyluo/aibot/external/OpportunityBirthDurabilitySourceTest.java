package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class OpportunityBirthDurabilitySourceTest {
    private static final Path REGISTRY=Path.of(
            "src/main/java/io/github/zoyluo/aibot/external/SemanticWorldRegistry.java");
    private static final Path RUNTIME=Path.of(
            "src/main/java/io/github/zoyluo/aibot/external/ExternalBodyRuntime.java");

    @Test void newIncarnationBirthMustBeDurableBeforeRegistryExposure() throws IOException {
        String source=Files.readString(REGISTRY);
        int observe=source.indexOf("ResourceOpportunity prior = findActiveOpportunityAt");
        int branch=source.indexOf("if (prior == null) {",observe);
        assertTrue(observe>=0 && branch>observe);
        int birth=source.indexOf("appendOpportunityBirth(opportunityLifecycleJournal,next)",branch);
        int put=source.indexOf("OPPORTUNITIES.put(key, next)",branch);
        int actionable=source.indexOf("resourceOpportunityActionable",branch);
        assertTrue(birth>branch && put>birth && actionable>put,
                "birth fsync must precede active-registry/actionable exposure");
    }

    @Test void capacityMustNotEvictALiveIncarnation() throws IOException {
        String source=Files.readString(REGISTRY);
        assertTrue(source.contains("prior==null && OPPORTUNITIES.size()>=MAX_OPPORTUNITIES"));
        assertFalse(source.contains("OPPORTUNITIES.remove(oldest)"),
                "active incarnation membership must not be a disposable LRU cache");
    }

    @Test void startupLifecycleReconcilePrecedesGraphAndHttpExposure() throws IOException {
        String source=Files.readString(RUNTIME);
        int lifecycle=source.indexOf("reconcileOpportunityLifecycleReceipts(journal)");
        int graphs=source.indexOf("new TaskGraphStore(",lifecycle);
        int kernel=source.indexOf("new BridgeKernel(",graphs);
        int http=source.indexOf("new BridgeHttpServer(",kernel);
        assertTrue(lifecycle>=0 && graphs>lifecycle && kernel>graphs && http>kernel);
    }

    @Test void staleReceiptMustBeDurableBeforeSemanticRemoval() throws IOException {
        String registry=Files.readString(REGISTRY);
        int method=registry.indexOf("boolean markOpportunityStale(");
        int end=registry.indexOf("reactivateOpportunity(",method);
        assertTrue(method>=0 && end>method);
        String stale=registry.substring(method,end);
        int receipt=stale.indexOf("resourceOpportunityStale(");
        int remove=stale.indexOf("OPPORTUNITIES.remove(key)");
        assertTrue(receipt>=0 && remove>receipt,
                "stale receipt must cross the journal boundary before registry removal");

        String runtime=Files.readString(RUNTIME);
        assertTrue(runtime.contains("boolean resourceOpportunityStale("));
        assertTrue(runtime.contains("return kernel.recordOpportunityResolution(\"resource_opportunity_stale\""));
    }

    @Test void lifecycleReplayMustSupportBirthRestoreAndLegacyAdoption() throws IOException {
        String source=Files.readString(REGISTRY);
        int method=source.indexOf("reconcileOpportunityLifecycleReceipts(");
        int end=source.indexOf("private static void appendOpportunityBirth",method);
        assertTrue(method>=0 && end>method);
        String reconcile=source.substring(method,end);
        assertTrue(reconcile.contains("resource_opportunity_birth"));
        assertTrue(reconcile.contains("appendOpportunityBirth(journal,entry.getValue())"),
                "legacy active ids must be adopted without id rewrite");
        assertTrue(reconcile.contains("OPPORTUNITIES.put(entry.getKey(),birth)"),
                "durable birth missing from stale semantic snapshot must be restored");
        assertTrue(reconcile.contains("terminal.contains(key)"),
                "terminal receipts must dominate stale semantic state");
    }
}
