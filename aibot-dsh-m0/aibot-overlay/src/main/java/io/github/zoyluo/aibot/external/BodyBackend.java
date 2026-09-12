package io.github.zoyluo.aibot.external;

import java.util.Set;

/** Every method is called exclusively by BridgeKernel.tick on the owning body thread. */
public interface BodyBackend {
    /** Operations admitted by this physical backend. The public DSH tool surface remains fixed. */
    Set<String> ALL_OPERATIONS=Set.of(
            "goto","gather","craft","smelt","eat","set_base","deposit","say",
            "register_home","capture_home","repair_home","register_farm","tend_farm",
            "mine_opportunity");

    /** Returns false until the configured, living physical body exists. */
    boolean ready();

    /** Stable Iris/DSH body identity; never a transient Minecraft entity/session id. */
    String bodyId();

    /** Current physical binding. */
    default Binding binding() {
        String id=bodyId();
        return new Binding(id,"legacy_body_backend",id,"legacy");
    }

    /** Backends may implement a strict subset. Unsupported work must never fall back elsewhere. */
    default Set<String> supportedOperations() {
        return ALL_OPERATIONS;
    }

    String observeJson();

    /** Must perform argument validation before mutation and retain the exact physical execution. */
    Handle start(String operation,String argumentsJson);

    /** Exact bounded-execution identity seam. */
    default Handle start(String executionId,String operation,String argumentsJson) {
        return start(operation,argumentsJson);
    }

    void pause();
    void resume();
    void cancel(String reason);

    /** Stable logical body plus replaceable physical carrier/session identity. */
    record Binding(String bodyId,String backendKind,String instanceId,String sessionEpoch) {
        public Binding {
            bodyId=bounded(bodyId,"body_id",160);
            backendKind=bounded(backendKind,"backend_kind",80);
            instanceId=bounded(instanceId,"body_instance_id",160);
            sessionEpoch=bounded(sessionEpoch,"body_session_epoch",160);
        }
        public java.util.Map<String,Object> wire() {
            java.util.LinkedHashMap<String,Object> out=new java.util.LinkedHashMap<>();
            out.put("body_id",bodyId);
            out.put("backend_kind",backendKind);
            out.put("body_instance_id",instanceId);
            out.put("body_session_epoch",sessionEpoch);
            return out;
        }
        private static String bounded(String value,String field,int max) {
            if(value==null || value.isBlank() || value.length()>max
                    || value.chars().anyMatch(c->c<0x20))
                throw new IllegalArgumentException("invalid_"+field);
            return value;
        }
    }

    // ---- MC-2A0 read-only cognitive query surface ----

    default io.github.zoyluo.aibot.external.cognition.CognitiveSnapshot.Snapshot cognitiveSnapshot(
            BridgeJournal journal) {
        return null;
    }

    default String inspectLocalJson(int radius,String detail) {
        return null;
    }

    default String materializeEvidence(String ref,String detail,long gameTime) {
        return null;
    }

    default long serverTick() {
        return -1L;
    }

    // ---- MC-2A Graph Core postconditions ----

    enum GraphPostconditionState { SATISFIED, UNSATISFIED, TERMINAL_UNSATISFIED, UNKNOWN }

    record GraphPostconditionResult(GraphPostconditionState state,String reason) {
        public GraphPostconditionResult {
            java.util.Objects.requireNonNull(state);
            reason=reason==null?"":reason;
        }
        public static GraphPostconditionResult satisfied(String reason) {
            return new GraphPostconditionResult(GraphPostconditionState.SATISFIED,reason);
        }
        public static GraphPostconditionResult unsatisfied(String reason) {
            return new GraphPostconditionResult(GraphPostconditionState.UNSATISFIED,reason);
        }
        public static GraphPostconditionResult terminalUnsatisfied(String reason) {
            return new GraphPostconditionResult(GraphPostconditionState.TERMINAL_UNSATISFIED,reason);
        }
        public static GraphPostconditionResult unknown(String reason) {
            return new GraphPostconditionResult(GraphPostconditionState.UNKNOWN,reason);
        }
    }

    default GraphPostconditionResult verifyGraphPostcondition(
            TaskGraphStore.Postcondition postcondition) {
        return GraphPostconditionResult.unknown("backend_postcondition_not_supported");
    }

    interface Handle {
        Snapshot snapshot();
    }

    record Snapshot(String state,double progress,String reason) {
        public Snapshot {
            if(!Set.of("running","paused","completed","failed","cancelled","outcome_unknown")
                    .contains(state))
                throw new IllegalArgumentException("invalid_backend_state");
            if(!Double.isFinite(progress))progress=0;
            progress=Math.max(0,Math.min(1,progress));
            reason=reason==null?"":reason;
        }
    }
}
