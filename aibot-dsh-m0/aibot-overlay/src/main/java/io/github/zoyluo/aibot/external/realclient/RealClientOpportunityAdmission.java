package io.github.zoyluo.aibot.external.realclient;

/**
 * MC-RCF-1-R2 R02: pure admission decision for durable opportunity birth.
 *
 * <p>The production tracker ({@link RealClientOpportunityTracker#observe})
 * computes frame/world facts on the server thread and runs them through this
 * core. Every refusal branch here means NO durable birth and no actionable
 * side effect. This class has no Minecraft imports so the full refusal matrix
 * is testable deterministically offline (see
 * {@code RealClientOpportunityAdmissionTest}).</p>
 *
 * <p>Check order (each is fail-closed): crosshair present → game session
 * bound → frame not already processed → frame fresh → frame position coherent
 * with the authoritative body → target within validation range → server ray
 * reconstruction lands on the client-reported crosshair cell → cell still
 * holds the reported block → block is an eligible resource.</p>
 */
public final class RealClientOpportunityAdmission {
    private RealClientOpportunityAdmission() {}

    /** Facts extracted from one client sensor frame plus server world state. */
    public record Facts(
            boolean crosshairPresent,
            String gameSession,
            String lastProcessedGameSession,
            long lastProcessedFrameSeq,
            long frameSeq,
            long frameAgeMs,
            long freshnessLimitMs,
            double positionDrift,
            double positionTolerance,
            double squaredDistance,
            double maxSquaredDistance,
            boolean serverRayAtCrosshair,
            boolean blockIdMatches,
            boolean eligibleBlock) {
        public Facts {
            if(freshnessLimitMs<=0L)
                throw new IllegalArgumentException("freshness_limit_positive");
            if(positionTolerance<0D)
                throw new IllegalArgumentException("position_tolerance_non_negative");
            if(maxSquaredDistance<=0D)
                throw new IllegalArgumentException("distance_limit_positive");
        }

        /** Test convenience: canonical admitted frame, one fact at a time. */
        public static final class Builder {
            private boolean crosshairPresent=true;
            private String gameSession="game-1";
            private String lastProcessedGameSession="";
            private long lastProcessedFrameSeq=-1L;
            private long frameSeq=1L;
            private long frameAgeMs=100L;
            private long freshnessLimitMs=2000L;
            private double positionDrift=.05D;
            private double positionTolerance=2.0D;
            private double squaredDistance=9.0D;
            private double maxSquaredDistance=49.0D;
            private boolean serverRayAtCrosshair=true;
            private boolean blockIdMatches=true;
            private boolean eligibleBlock=true;

            public Builder crosshairPresent(boolean v) { this.crosshairPresent=v; return this; }
            public Builder gameSession(String v) { this.gameSession=v; return this; }
            public Builder lastProcessedGameSession(String v) { this.lastProcessedGameSession=v; return this; }
            public Builder lastProcessedFrameSeq(long v) { this.lastProcessedFrameSeq=v; return this; }
            public Builder frameSeq(long v) { this.frameSeq=v; return this; }
            public Builder frameAgeMs(long v) { this.frameAgeMs=v; return this; }
            public Builder freshnessLimitMs(long v) { this.freshnessLimitMs=v; return this; }
            public Builder positionDrift(double v) { this.positionDrift=v; return this; }
            public Builder positionTolerance(double v) { this.positionTolerance=v; return this; }
            public Builder squaredDistance(double v) { this.squaredDistance=v; return this; }
            public Builder maxSquaredDistance(double v) { this.maxSquaredDistance=v; return this; }
            public Builder serverRayAtCrosshair(boolean v) { this.serverRayAtCrosshair=v; return this; }
            public Builder blockIdMatches(boolean v) { this.blockIdMatches=v; return this; }
            public Builder eligibleBlock(boolean v) { this.eligibleBlock=v; return this; }

            public Facts build() {
                return new Facts(crosshairPresent,gameSession,
                        lastProcessedGameSession,lastProcessedFrameSeq,
                        frameSeq,frameAgeMs,freshnessLimitMs,positionDrift,
                        positionTolerance,squaredDistance,maxSquaredDistance,
                        serverRayAtCrosshair,blockIdMatches,eligibleBlock);
            }
        }
    }

    /**
     * @param admit           durable birth/refresh is authorized
     * @param refusal         machine-readable refusal reason ("" when admitted)
     * @param duplicateFrame  frame already processed for this game session:
     *                        not an error, silently produces nothing
     */
    public record Decision(boolean admit,String refusal,boolean duplicateFrame) {
        public static Decision ofDuplicate() {
            return new Decision(false,"",true);
        }
        public static Decision ofRefusal(String reason) {
            return new Decision(false,reason,false);
        }
        public static Decision ofAdmitted() {
            return new Decision(true,"",false);
        }
    }

    public static Decision evaluate(Facts f) {
        if(!f.crosshairPresent())
            return Decision.ofRefusal("sensor_not_present");
        if(f.gameSession()==null || f.gameSession().isBlank())
            return Decision.ofRefusal("game_session_missing");
        if(f.gameSession().equals(f.lastProcessedGameSession())
                && f.frameSeq()<=f.lastProcessedFrameSeq())
            return Decision.ofDuplicate();
        if(f.frameAgeMs()>f.freshnessLimitMs())
            return Decision.ofRefusal("frame_stale");
        if(f.positionDrift()>f.positionTolerance())
            return Decision.ofRefusal("position_drift_exceeded");
        if(f.squaredDistance()>f.maxSquaredDistance())
            return Decision.ofRefusal("distance_exceeded");
        if(!f.serverRayAtCrosshair())
            return Decision.ofRefusal("ray_mismatch");
        if(!f.blockIdMatches())
            return Decision.ofRefusal("block_or_ore_mismatch");
        if(!f.eligibleBlock())
            return Decision.ofRefusal("block_not_eligible");
        return Decision.ofAdmitted();
    }
}
