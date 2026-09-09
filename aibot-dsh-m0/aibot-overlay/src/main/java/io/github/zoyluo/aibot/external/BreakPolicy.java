package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.TaskManager;
import net.minecraft.util.math.BlockPos;

/**
 * Final destructive-action gate for the reserved external body.
 *
 * Structure/farm protection is Body-domain operational state. A SAFETY-origin task may still
 * break a protected cell when that is required to stay alive; ordinary gathering/navigation/
 * building must fail closed instead of guessing that a structural block is a resource.
 *
 * Rejection reasons come from SemanticWorldRegistry.protectionReason: "protected_structure:<id>"
 * for registered HOME cuboid cells and "registered_farm:<id>" for registered farm cells.
 */
public final class BreakPolicy {
    private BreakPolicy() {}

    public record Decision(boolean allowed, String reason) {}

    public static Decision decide(AIPlayerEntity bot, BlockPos pos) {
        if (bot == null || pos == null || !ExternalBodyAccess.reserved(bot)) {
            return new Decision(true, "not_reserved_external_body");
        }
        TaskOrigin origin = TaskManager.INSTANCE.activeOrigin(bot).orElse(null);
        if (origin != null && origin.safety()) {
            return new Decision(true, "safety_override");
        }
        String protectedReason = SemanticWorldRegistry.protectionReason(bot, pos);
        if (protectedReason != null) {
            return new Decision(false, protectedReason);
        }
        return new Decision(true, "ordinary_world_cell");
    }

    public static boolean mayBreak(AIPlayerEntity bot, BlockPos pos) {
        return decide(bot, pos).allowed();
    }
}
