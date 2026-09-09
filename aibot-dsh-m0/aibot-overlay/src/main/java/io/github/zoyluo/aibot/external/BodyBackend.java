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
