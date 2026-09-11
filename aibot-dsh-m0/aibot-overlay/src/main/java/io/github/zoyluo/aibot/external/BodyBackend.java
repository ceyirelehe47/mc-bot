package io.github.zoyluo.aibot.external;

/** Every method is called exclusively by BridgeKernel.tick on the Minecraft server thread. */
public interface BodyBackend {
    /** Returns false until the configured, living body exists. */
    boolean ready();
    String bodyId();
    String observeJson();
    /** Must perform argument validation before mutation and retain the exact Task instance. */
    Handle start(String operation,String argumentsJson);
    /**
     * Exact bounded-execution identity seam. Legacy/fake backends stay source-compatible while the
     * real external body may bind execution-scoped operational provenance to its task.
     */
    default Handle start(String executionId,String operation,String argumentsJson) {
        return start(operation,argumentsJson);
    }
    void pause();
    void resume();
    void cancel(String reason);
    // ---- MC-2A0 read-only cognitive query surface ----
    // 与 observeJson 相同的线程契约:只由 BridgeKernel.tick 在 server 线程调用,
    // HTTP 线程只读 kernel 缓存。default null 表示该 backend 不支持认知查询(桥按 503 fail-closed)。

    /** 构建 cognitive view 快照;journal 仅作 non-consuming 只读事件尾部来源,可为 null。 */
    default io.github.zoyluo.aibot.external.cognition.CognitiveSnapshot.Snapshot cognitiveSnapshot(BridgeJournal journal) {
        return null;
    }
    /** 以当前身体为中心的 bounded 局部视图(server 线程执行,由 kernel 查询队列驱动)。 */
    default String inspectLocalJson(int radius,String detail) {
        return null;
    }
    /**
     * MC-2A0.1 on-demand evidence materialization:把单个 EvidenceRef+detail 展开成
     * bounded JSON。gameTime 是 kernel 当前快照的构建时刻——freshness 与 view 卡同源。
     */
    default String materializeEvidence(String ref,String detail,long gameTime) {
        return null;
    }
    /** 当前服务器 tick,供 kernel 缓存刷新节奏与 view meta 使用。 */
    default long serverTick() {
        return -1L;
    }
    // ---- MC-2A Graph Core: postconditions are revalidated on the server thread ----
    enum GraphPostconditionState { SATISFIED, UNSATISFIED, UNKNOWN }
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
        public static GraphPostconditionResult unknown(String reason) {
            return new GraphPostconditionResult(GraphPostconditionState.UNKNOWN,reason);
        }
    }
    /**
     * Read-only proof used after a physical execution terminates. Never starts/replays work.
     * Backends that cannot prove a postcondition must return UNKNOWN, never false certainty.
     */
    default GraphPostconditionResult verifyGraphPostcondition(TaskGraphStore.Postcondition postcondition) {
        return GraphPostconditionResult.unknown("backend_postcondition_not_supported");
    }
    interface Handle {
        /** No lookup of global lastStatus: that could refer to a different safety task. */
        Snapshot snapshot();
    }
    record Snapshot(String state,double progress,String reason) {
        public Snapshot {
            if(!java.util.Set.of("running","paused","completed","failed","cancelled","outcome_unknown").contains(state))
                throw new IllegalArgumentException("invalid_backend_state");
            if(!Double.isFinite(progress)) progress=0;
            progress=Math.max(0,Math.min(1,progress));
            reason=reason==null?"":reason;
        }
    }
}
