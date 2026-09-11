package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class TaskGraphBridgeSourceContractTest {
    private static final Path KERNEL=Path.of("src/main/java/io/github/zoyluo/aibot/external/BridgeKernel.java");
    private static final Path STORE=Path.of("src/main/java/io/github/zoyluo/aibot/external/TaskGraphStore.java");

    @Test void graphPhysicalDispatchMustReuseExistingBridgeExecutionLedger() throws IOException {
        String source=Files.readString(KERNEL);
        int start=source.indexOf("Map<String,Object> graphRunNext");
        int end=source.indexOf("Map<String,Object> graphCancel",start);
        assertTrue(start>=0 && end>start,"graph run/cancel seams missing");
        String run=source.substring(start,end);
        assertTrue(run.contains("submit(supplied,d.requestId(),d.operation(),d.arguments())"),
                "Graph physical dispatch must enter the ordinary BridgeKernel.submit ledger");
        assertFalse(run.contains("backend.start("),
                "Graph must not create a second physical execution path");
    }

    @Test void graphPersistenceUsesSpatialRefsNotCopiedCoordinates() throws IOException {
        String source=Files.readString(STORE);
        int refStart=source.indexOf("record SpatialRef(");
        int refEnd=source.indexOf("record Postcondition(",refStart);
        assertTrue(refStart>=0 && refEnd>refStart);
        String ref=source.substring(refStart,refEnd);
        assertTrue(ref.contains("worldId") && ref.contains("dimensionId") && ref.contains("objectId"));
        assertFalse(ref.matches("(?s).*\\b(x|y|z|seenX|seenY|seenZ)\\b.*"),
                "SpatialRef must not duplicate mutable coordinates");
    }

    @Test void fixedTaskNodeLifecycleRemainsExplicit() throws IOException {
        String source=Files.readString(STORE);
        for(String state:new String[]{"PENDING","BLOCKED","READY","RUNNING","SUSPENDED",
                "DONE","FAILED","CANCELLED","STALE"}) {
            assertTrue(source.contains(state),"missing fixed graph node state "+state);
        }
        assertTrue(source.contains("restart_revalidation_required_no_replay"));
    }
}
