package io.github.zoyluo.aibot.external.realclient;

/**
 * MC-RCF-1-R2 R03: pure per-tick decision core for the client eat action.
 *
 * <p>The production {@code RealClientActionController.EatAction} observes the
 * live client inventory/cursor/using state each tick and executes the step
 * this core returns. The core owns the phase transitions
 * (prepare-hold → use-window → verify) so the exact production state machine
 * is deterministically testable offline (see
 * {@code RealClientEatDecisionCoreTest}), including the historical R03
 * defect: the phase-0 main-package PICKUP used to fall through to the
 * consumption verification on the same tick (consumedRounds=0, heldStart=0)
 * and fail with {@code client_eat_no_effect}.</p>
 *
 * <p>Invariants enforced here:</p>
 * <ul>
 *   <li>Every prepare step returns before any verify step — phases are
 *       mutually exclusive, one step per tick.</li>
 *   <li>The use window only starts after the server confirmed use-in-progress
 *       ({@code usingItem}); a fixed sleep is never a sync confirmation.</li>
 *   <li>{@code isUsingItem true→false} alone never counts as a consumed item:
 *       the final claim is min(use-transitions, held-count-decrease) and must
 *       be strictly positive to complete.</li>
 *   <li>One item per invocation: once the window elapses and using has
 *       finished, use is released and the machine moves to verify — no
 *       continuous eating for a full hunger window.</li>
 * </ul>
 */
public final class RealClientEatDecisionCore {
    private RealClientEatDecisionCore() {}

    /** How long the use key stays pressed once the server confirmed use (ticks). */
    public static final int USE_WINDOW_TICKS=48;

    public enum Step {
        /** Wait for the shared-transaction sync (cooldown throttling only). */
        WAIT_SYNC,
        /** Cursor empty, food in main package: PICKUP one main stack to cursor. */
        PICKUP_MAIN,
        /** Cursor holds the food: place it into a hotbar slot. */
        PLACE_CURSOR_TO_HOTBAR,
        /** Cursor holds a foreign stack: return it to the main package. */
        RETURN_FOREIGN_CURSOR,
        /** Food is in a hotbar slot: select it. */
        SELECT_HOTBAR,
        /** Held item just became the food: record the baseline and enter the window. */
        MARK_HELD,
        /** Press use and keep waiting for the server to confirm use-in-progress. */
        PRESS_USE,
        /** Server confirmed using: keep the use key pressed inside the window. */
        HOLD_USE,
        /** Window elapsed and using finished: release use, enter verify. */
        RELEASE_AND_VERIFY,
        /** Verify passed: consumed>0 claim is provable from held-count decrease. */
        COMPLETE,
        /** No food anywhere in the inventory. */
        FAIL_NO_FOOD,
        /** Use window or total budget exhausted. */
        FAIL_TIMEOUT,
        /** Verify rejected: no provable consumption this invocation. */
        FAIL_NO_EFFECT
    }

    /** One tick of client-observed state (all fields from live client facts). */
    public record Observed(
            boolean heldIsFood,
            int heldCount,
            boolean hotbarHasFood,
            boolean cursorIsFood,
            boolean cursorNonEmpty,
            boolean mainHasFood,
            boolean usingItem) {}

    public static final class Machine {
        private int phase;           // 0=prepare hold 1=use window 2=verify
        private int windowTicks;
        private boolean windowStarted;
        private int heldStart=-1;
        private boolean wasUsing;
        private int consumedRounds;
        private int verifyWaitTicks;
        private boolean done;
        private String doneReason="";

        public int phase() { return phase; }
        public int heldStart() { return heldStart; }
        /** Real completed use transitions (isUsing true→false with our use held). */
        public int consumedRounds() { return consumedRounds; }
        public boolean terminal() { return done; }
        public String reason() { return doneReason; }

        public Step tick(Observed o) {
            if(done)return Step.COMPLETE;
            if(phase==0)return prepareTick(o);
            if(phase==1)return windowTick(o);
            return verifyTick(o);
        }

        private Step prepareTick(Observed o) {
            if(o.heldIsFood()) {
                heldStart=o.heldCount();
                phase=1;
                return Step.MARK_HELD;
            }
            if(o.hotbarHasFood())
                return Step.SELECT_HOTBAR;
            if(o.cursorIsFood())
                return Step.PLACE_CURSOR_TO_HOTBAR;
            if(o.cursorNonEmpty())
                return Step.RETURN_FOREIGN_CURSOR;
            if(!o.mainHasFood())
                return fail(Step.FAIL_NO_FOOD,"client_food_not_found");
            // Food only in the main package: pick it up and RETURN — never
            // fall through to verify on this tick (R03 defect).
            return Step.PICKUP_MAIN;
        }

        private Step windowTick(Observed o) {
            boolean using=o.usingItem();
            if(wasUsing&&!using)consumedRounds++;
            wasUsing=using;
            if(using&&!windowStarted)windowStarted=true;
            if(!windowStarted)
                return Step.PRESS_USE;
            windowTicks++;
            boolean boundReached=windowTicks>=USE_WINDOW_TICKS;
            // R2/A08:手持数量减少=真实消费的直接证据——立即停 use。
            // 连吃场景(吃完一块同 tick 开始下一块)isUsing 永不落 false,
            // 转换捕获不到(实测 hotbar 食物 no_effect 根因)。
            boolean heldDecreased=o.heldIsFood()&&o.heldCount()<heldStart;
            if(consumedRounds>=1||heldDecreased||boundReached) {
                phase=2;
                return Step.RELEASE_AND_VERIFY;
            }
            return Step.HOLD_USE;
        }
        private Step verifyTick(Observed o) {
            int heldNow=o.heldIsFood()?o.heldCount():0;
            // R2/A08:claimed 以手持数量真实减少为主证据(服务器端还会
            // 核对 after==before-claimed);isUsing 转换只是辅助记录。
            int claimed=heldStart-heldNow;
            if(claimed>0) {
                done=true;
                doneReason="client_food_consumed:client_consumed="+claimed;
                return Step.COMPLETE;
            }
            // isUsing 转换可能先于客户端物品数量同步:有界等待计数变化。
            if(++verifyWaitTicks<40 && windowStarted)
                return Step.RELEASE_AND_VERIFY;
            done=true;
            doneReason="client_eat_no_effect";
            return Step.FAIL_NO_EFFECT;
        }

        private Step fail(Step step,String reason) {
            done=true;
            doneReason=reason;
            return step;
        }

        /** External timeout abort (production hard budget). */
        public Step abortTimeout() {
            return fail(Step.FAIL_TIMEOUT,"client_eat_timeout");
        }
    }
}
