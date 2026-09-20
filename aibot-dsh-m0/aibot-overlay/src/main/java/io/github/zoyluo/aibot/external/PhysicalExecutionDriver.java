package io.github.zoyluo.aibot.external;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Backend-neutral physical execution seam.
 *
 * <p>The shared Bridge/Graph layer submits one normalized bounded operation. A server fake-player
 * driver and a future real-client driver may implement this contract without putting backend
 * selection branches inside Graph or DSH-facing code.</p>
 */
public interface PhysicalExecutionDriver {
    record Request(String executionId,String operation,String argumentsJson) {
        public Request {
            executionId=executionId==null?"":executionId;
            operation=Objects.requireNonNull(operation,"operation");
            argumentsJson=Objects.requireNonNull(argumentsJson,"argumentsJson");
            if(operation.isBlank() || operation.length()>80)
                throw new IllegalArgumentException("invalid_operation");
            if(argumentsJson.getBytes(StandardCharsets.UTF_8).length>16384)
                throw new IllegalArgumentException("arguments_too_large");
        }
    }

    BodyBackend.Handle start(Request request);
    void pause();
    void resume();
    void cancel(String reason);
}
