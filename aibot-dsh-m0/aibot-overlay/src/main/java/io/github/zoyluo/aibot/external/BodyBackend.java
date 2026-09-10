package io.github.zoyluo.aibot.external;

/** Every method is called exclusively by BridgeKernel.tick on the Minecraft server thread. */
public interface BodyBackend {
    /** Returns false until the configured, living body exists. */
    boolean ready();
    String bodyId();
    String observeJson();
    /** Must perform argument validation before mutation and retain the exact Task instance. */
    Handle start(String operation,String argumentsJson);
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
    /** 当前服务器 tick,供 kernel 缓存刷新节奏与 view meta 使用。 */
    default long serverTick() {
        return -1L;
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
