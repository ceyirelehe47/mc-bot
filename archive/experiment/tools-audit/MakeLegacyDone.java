import io.github.zoyluo.aibot.external.BodyBackend;
import io.github.zoyluo.aibot.external.TaskGraphStore;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 写一个 pre-fix Graph v0 的 DONE fixture: 无 durable 成功收据的机会图。 */
public class MakeLegacyDone {
    public static void main(String[] args) {
        Path file = Paths.get(args[0]);
        String world = args[1];
        String opp = args[2];
        TaskGraphStore store = new TaskGraphStore(file, () -> 1L);
        String id = (String) store.planOpportunity("r1c5-legacy-done",
                new TaskGraphStore.SpatialRef(world, "minecraft:overworld", opp)).get("graph_id");
        TaskGraphStore.Dispatch d = store.prepareDispatch(id);
        store.attachExecution(d, "legacy-execution-r1c5");
        store.executionTerminal("legacy-execution-r1c5", "completed",
                pc -> BodyBackend.GraphPostconditionResult.satisfied("opportunity_absent_from_current_registry"));
        System.out.println("graph_id=" + id + " state=" + store.inspect(id).get("state"));
    }
}
