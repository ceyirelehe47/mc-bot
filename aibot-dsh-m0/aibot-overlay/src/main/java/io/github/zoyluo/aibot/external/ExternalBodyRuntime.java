package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.AIBotMod;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import java.util.*;

/** Lifecycle wiring. No second LLM loop, no MCP server, and no asynchronous Minecraft access. */
public final class ExternalBodyRuntime {
    private static BridgeKernel kernel;
    private static BridgeJournal journal;
    private static BridgeHttpServer http;
    private static UUID observedBody;
    private static float previousHealth=Float.NaN;
    private static boolean previousAlive;
    private ExternalBodyRuntime() {}
    public static void start(MinecraftServer server) {
        if(!ExternalBodyAccess.enabled())return;
        if(!ExternalBodyAccess.BOT_NAME.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("invalid_AIBOT_EXTERNAL_BOT");
        try {
            String token=System.getenv("AIBOT_BRIDGE_TOKEN");
            int port=Integer.parseInt(System.getenv().getOrDefault("AIBOT_BRIDGE_PORT","8765"));
            if(port<1024 || port>65535)throw new IllegalArgumentException("invalid_bridge_port");
            journal=new BridgeJournal(server.getSavePath(WorldSavePath.ROOT).resolve("aibot/external-body-"+ExternalBodyAccess.BOT_NAME.toLowerCase(Locale.ROOT)+".journal"),System::currentTimeMillis);
            kernel=new BridgeKernel(journal,new MinecraftBodyBackend(server,ExternalBodyAccess.BOT_NAME));
            kernel.tick(); // fence restored legacy work before the network endpoint becomes reachable
            http=new BridgeHttpServer(kernel,port,token);http.start();
            observedBody=null;previousHealth=Float.NaN;previousAlive=false;
            AIBotMod.LOGGER.info("AIBot external-body bridge bound to loopback port {} for {}",port,ExternalBodyAccess.BOT_NAME);
        }catch(Exception failure){
            if(http!=null)http.close();
            try{if(journal!=null)journal.close();}catch(Exception ignored){}
            http=null;journal=null;kernel=null;
            // Reservation remains in force. Invalid config must never silently reactivate the old brain.
            throw new IllegalStateException("external_bridge_start_failed_closed",failure);
        }
    }
    public static void tick(MinecraftServer server) {
        if(kernel==null)return;
        kernel.tick();
        AIPlayerEntity bot=AIPlayerManager.INSTANCE.all().stream().filter(ExternalBodyAccess::reserved).findFirst().orElse(null);
        if(bot==null){observedBody=null;previousHealth=Float.NaN;previousAlive=false;return;}
        if(!bot.getUuid().equals(observedBody)){observedBody=bot.getUuid();previousHealth=bot.getHealth();previousAlive=bot.isAlive();return;}
        if(previousAlive && bot.isAlive() && bot.getHealth()<previousHealth)
            kernel.publish("damage",Map.of("previous_health",previousHealth,"health",bot.getHealth(),"source","health_delta_not_causal_attribution"));
        if(!previousAlive && bot.isAlive())kernel.publish("respawn",Map.of("body_id",bot.getUuid().toString(),"health",bot.getHealth()));
        previousAlive=bot.isAlive();previousHealth=bot.getHealth();
    }
    public static void death(AIPlayerEntity bot) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return;
        String source=bot.getRecentDamageSource()==null?"unknown":bot.getRecentDamageSource().getName();
        kernel.publish("death",Map.of("body_id",bot.getUuid().toString(),"recent_damage_source",source,
                "causal_chain_complete",false,"x",bot.getX(),"y",bot.getY(),"z",bot.getZ(),
                "dimension",bot.getServerWorld().getRegistryKey().getValue().toString()));
        previousAlive=false;previousHealth=0;
    }
    public static void message(AIPlayerEntity bot,String sender,String text) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return;
        kernel.publish("player_message",Map.of("sender",bounded(sender,80),"text",bounded(text,2000),"trust","untrusted_game_text"));
    }
    private static String bounded(String s,int length){return s==null?"":s.length()<=length?s:s.substring(0,length);}
    public static void stop() {
        try{if(kernel!=null)kernel.shutdown();}
        catch(RuntimeException e){AIBotMod.LOGGER.error("external body shutdown requires reconciliation",e);}
        finally {
            if(http!=null)http.close();
            try{if(journal!=null)journal.close();}catch(Exception e){AIBotMod.LOGGER.error("external journal close failed",e);}
            http=null;journal=null;kernel=null;observedBody=null;previousHealth=Float.NaN;previousAlive=false;
        }
    }
}
