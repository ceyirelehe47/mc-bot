package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/** Narrow authorization boundary for the configured external body. Disabled by default. */
public final class ExternalBodyAccess {
    public static final String BOT_NAME=System.getenv().getOrDefault("AIBOT_EXTERNAL_BOT", "").trim();
    public static final String BODY_ID=logicalBodyId();
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(()->0);
    private static String logicalBodyId() {
        if(BOT_NAME.isEmpty())return "";
        String configured=System.getenv().getOrDefault(
                "AIBOT_EXTERNAL_BODY_ID",BOT_NAME).trim().toLowerCase(Locale.ROOT);
        if(!configured.matches("[a-z0-9][a-z0-9._:-]{0,79}"))
            throw new IllegalArgumentException("invalid_AIBOT_EXTERNAL_BODY_ID");
        return configured;
    }
    private static volatile boolean RESERVATION_ACTIVE;
    private ExternalBodyAccess() {}
    public static boolean enabled() { return !BOT_NAME.isEmpty(); }
    public static boolean reserved(AIPlayerEntity bot) {
        return enabled() && RESERVATION_ACTIVE && bot!=null && BOT_NAME.equalsIgnoreCase(bot.getGameProfile().getName());
    }
    public static void activateReservation() { if(enabled()) RESERVATION_ACTIVE=true; }
    public static boolean dispatching() { return DEPTH.get()>0; }
    public static <T> T dispatch(Supplier<T> body) {
        int old=DEPTH.get(); DEPTH.set(old+1);
        try { return body.get(); } finally { if(old==0)DEPTH.remove();else DEPTH.set(old); }
    }
    public static void checkAssignment(AIPlayerEntity bot,TaskOrigin origin) {
        if(reserved(bot) && !dispatching() && (origin==null || !origin.safety()))
            throw new IllegalStateException("external_body_reserved_use_dsh_bridge");
    }
    public static void checkTool(AIPlayerEntity bot) {
        if(reserved(bot) && !dispatching()) throw new IllegalStateException("external_body_reserved_use_dsh_bridge");
    }
    /** Public legacy controllers may observe and send authorized chat, never mutate this body. */
    public static boolean permitsLegacyOperation(AIPlayerEntity bot,String operation,String channel) {
        if(!reserved(bot))return true;
        if("VIEW".equals(operation))return true;
        return "COMMAND".equals(operation) && ("chat:@bot".equals(channel) || "chat:plain_external".equals(channel) || "network:command".equals(channel));
    }
    public static void message(AIPlayerEntity bot,String sender,String text) {
        ExternalBodyRuntime.playerMessage(bot,null,sender,"legacy_brain_handle",false,text);
    }
    public static void playerMessage(AIPlayerEntity bot,UUID senderUuid,String senderName,
                                     String channel,boolean authorizedControl,String text) {
        ExternalBodyRuntime.playerMessage(bot,senderUuid,senderName,channel,authorizedControl,text);
    }
}
