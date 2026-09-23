import io.github.zoyluo.aibot.external.realclient.RealClientEatDecisionCore;
import io.github.zoyluo.aibot.external.realclient.RealClientEatDecisionCore.Observed;

/** Synthetic observations injected into the exact committed production core.
 * This is NOT a Minecraft LIVE reproduction. */
public class EatCoreProbe {
    static Observed o(boolean held, int count, boolean using) {
        return new Observed(held, count, held, false, false, false, using);
    }
    public static void main(String[] args) {
        var prep = new RealClientEatDecisionCore.Machine();
        var first = prep.tick(new Observed(false,0,false,false,false,true,false));
        System.out.println("MAIN_ONLY_FIRST_STEP="+first+",phase="+prep.phase()+",terminal="+prep.terminal());

        var m = new RealClientEatDecisionCore.Machine();
        System.out.println("EXTERNAL_CLEAR mark="+m.tick(o(true,2,false)));
        System.out.println("EXTERNAL_CLEAR started="+m.tick(o(true,2,true)));
        // No item was eaten. The test fixture removes both items immediately.
        System.out.println("EXTERNAL_CLEAR removed="+m.tick(o(false,0,false)));
        System.out.println("EXTERNAL_CLEAR verify="+m.tick(o(false,0,false)));
        System.out.println("EXTERNAL_CLEAR reason="+m.reason());
        int before=2, after=0, claimed=2;
        // Exact Boolean success condition visible in server eatSnapshot.
        boolean serverCondition = claimed>0 && after==before-claimed;
        System.out.println("EXTERNAL_CLEAR server_numeric_condition="+serverCondition);

        var noFood = new RealClientEatDecisionCore.Machine();
        System.out.println("FAILURE_TERMINAL first="+noFood.tick(o(false,0,false)));
        System.out.println("FAILURE_TERMINAL second="+noFood.tick(o(false,0,false)));
        System.out.println("FAILURE_TERMINAL reason="+noFood.reason());
    }
}
