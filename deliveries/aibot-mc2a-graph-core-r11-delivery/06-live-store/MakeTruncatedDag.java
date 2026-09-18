package io.github.zoyluo.aibot.external;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R1.1 LIVE exact-short-read fixture.
 *
 * Writes a real TaskGraphStore DAG whose final bytes are:
 *   dependency length = 2, dependency bytes = "aX"
 * and then truncates exactly the final 'X'.  A short-read bug would turn the dependency into the
 * other valid node id "a"; correct code must fail closed while loading the store.
 */
public final class MakeTruncatedDag {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: MakeTruncatedDag <task-graphs-bob.bin> <world-id>");
        }
        Path file = Paths.get(args[0]);
        String world = args[1];
        Files.deleteIfExists(file);

        TaskGraphStore store = new TaskGraphStore(file, () -> 1L);
        TaskGraphStore.SpatialRef a = new TaskGraphStore.SpatialRef(
                world, "minecraft:overworld", "fixture-a");
        TaskGraphStore.SpatialRef ax = new TaskGraphStore.SpatialRef(
                world, "minecraft:overworld", "fixture-ax");
        TaskGraphStore.SpatialRef z = new TaskGraphStore.SpatialRef(
                world, "minecraft:overworld", "fixture-z");

        store.createFragment("graph-r11-truncated-dependency",
                "producer-r11-truncated-dependency",
                "plan-r11-truncated-dependency",
                "R11_LIVE_FIXTURE",
                List.of(
                        node("a", Set.of(), a),
                        node("aX", Set.of(), ax),
                        node("z", Set.of("aX"), z)
                ));

        byte[] valid = Files.readAllBytes(file);
        if (valid.length < 2 || valid[valid.length - 2] != 'a' || valid[valid.length - 1] != 'X') {
            throw new IllegalStateException("fixture_not_ending_in_dependency_aX");
        }
        Files.write(file, Arrays.copyOf(valid, valid.length - 1));
        System.out.println("valid_bytes=" + valid.length);
        System.out.println("truncated_bytes=" + (valid.length - 1));
        System.out.println("final_valid_dependency=aX");
        System.out.println("final_truncated_prefix=a");
    }

    private static TaskGraphStore.NodeSpec node(
            String id, Set<String> deps, TaskGraphStore.SpatialRef ref) {
        return new TaskGraphStore.NodeSpec(
                id,
                deps,
                "mine_opportunity",
                JsonOutput.encode(Map.of("id", ref.objectId())),
                ref,
                new TaskGraphStore.Postcondition("OPPORTUNITY_RESOLVED", ref),
                ref.claimKey("opportunity"));
    }
}
