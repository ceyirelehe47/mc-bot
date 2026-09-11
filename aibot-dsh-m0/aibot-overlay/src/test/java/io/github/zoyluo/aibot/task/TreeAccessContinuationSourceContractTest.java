package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-contract guard for the MC-2A0.2F state-machine closure.
 *
 * The physical TreeHarvestWorkset already knows how to stack supports. The regression lived in
 * GatherQuotaTask: SURVEY treated the mere existence of support #1 as a request to clean it up.
 */
final class TreeAccessContinuationSourceContractTest {
    private static final Path TASK =
            Path.of("src/main/java/io/github/zoyluo/aibot/task/GatherQuotaTask.java");

    @Test
    void activeTreeAccessSupportIsContinuationStateNotImmediateCleanupTrigger() throws IOException {
        String source = Files.readString(TASK);
        int surveyStart = source.indexOf("private void surveyCommittedTree(AIPlayerEntity bot)");
        int cleanupStart = source.indexOf("private void treeCleanup(AIPlayerEntity bot)", surveyStart);
        assertTrue(surveyStart >= 0 && cleanupStart > surveyStart,
                "MC-2A0.2 committed-tree state-machine methods must exist");

        String survey = source.substring(surveyStart, cleanupStart);
        assertTrue(survey.contains("treeWorkset.tickAccess(bot, next)"),
                "SURVEY must keep driving the same workset access transaction");
        assertFalse(survey.contains("if (treeWorkset.hasTemporarySupports())"),
                "a live TREE_ACCESS receipt must not immediately route SURVEY into cleanup");

        // Cleanup authority still exists, but only from explicit lifecycle points.
        assertTrue(source.contains("case TREE_CLEANUP -> treeCleanup(bot);"));
        assertTrue(source.contains(
                "phase = treeWorkset != null && treeWorkset.hasTemporarySupports()"));
        assertTrue(source.contains("? Phase.TREE_CLEANUP : Phase.PICKUP;"),
                "a resolved committed log with owned supports must still enter reverse cleanup");
    }

    @Test
    void genericPathPillarRemainsOutsideTreeAccessOwnership() throws IOException {
        String workset = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/external/TreeHarvestWorkset.java"));
        // Javadoc explicitly contrasts TREE_ACCESS with the generic pillar; only executable
        // code is the ownership boundary being asserted here.
        String codeOnly=workset.replaceAll("(?s)/\\*.*?\\*/","");
        assertFalse(codeOnly.contains("PILLAR_UP"));
        assertTrue(workset.contains("\"TREE_ACCESS\""));
        assertTrue(workset.contains("ownerExecution"),
                "tree support receipts must remain execution-scoped");
    }
}
