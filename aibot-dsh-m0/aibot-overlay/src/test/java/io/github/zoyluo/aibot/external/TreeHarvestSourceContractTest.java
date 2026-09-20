package io.github.zoyluo.aibot.external;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-boundary regression for the MC-2A0.2 tree transaction. */
final class TreeHarvestSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");

    @Test
    void treeAccessIsExplicitlyOwnedAndDoesNotHijackGenericPillarInfrastructure() throws IOException {
        String workset = Files.readString(MAIN.resolve("external/TreeHarvestWorkset.java"));
        String gather = Files.readString(MAIN.resolve("task/GatherQuotaTask.java"));
        String path = Files.readString(MAIN.resolve("pathfinding/PathExecutor.java"));

        assertTrue(workset.contains("ownerExecution"));
        assertTrue(workset.contains("TREE_ACCESS"));
        assertTrue(workset.contains("support_conflict"));
        assertTrue(workset.contains("BuildAction.placeBlockAt"));
        assertTrue(gather.contains("countSoFar >= targetCount && treeWorkset == null"),
                "quota may only terminate immediately when no tree transaction is active");
        assertTrue(gather.contains("TreeHarvestWorkset")
                && gather.contains(".acquire(bot, targetPos, externalExecutionId)"),
                "external gather must acquire the tree workset bound to the exact execution");
        assertFalse(path.contains("TreeHarvestWorkset"),
                "generic PILLAR_UP must remain persistent and independent of tree cleanup");
        assertFalse(path.contains("TREE_ACCESS"),
                "tree-specific temporary ownership must not leak into the generic path executor");
    }

    @Test
    void bridgePassesTheExactBoundedExecutionIdIntoTheBodyBackend() throws IOException {
        String kernel = Files.readString(MAIN.resolve("external/BridgeKernel.java"));
        // MC-2A0.3 后 Task 构建移入 ServerFakePlayerExecutionDriver;executionId 绑定契约不变。
        String driver = Files.readString(MAIN.resolve("external/ServerFakePlayerExecutionDriver.java"));
        assertTrue(kernel.contains("backend.start(e.id,e.operation,e.arguments)"),
                "BridgeKernel must pass its exact execution id into the backend dispatch");
        assertTrue(driver.contains("new GatherQuotaTask(target,count,executionId)"),
                "external gather must bind the exact bridge execution id into its tree workset");
    }
}
