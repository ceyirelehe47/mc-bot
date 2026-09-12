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
    private static final Map<String,Integer> nextSurvivalAlertTick=new HashMap<>();
    private ExternalBodyRuntime() {}
    public static void start(MinecraftServer server) {
        if(!ExternalBodyAccess.enabled())return;
        // Preserve the reservation even when configuration is invalid: never fall back to the old brain.
        ExternalBodyAccess.activateReservation();
        try {
            if(!ExternalBodyAccess.BOT_NAME.matches("[A-Za-z0-9_]{1,16}"))
                throw new IllegalArgumentException("invalid_AIBOT_EXTERNAL_BOT");
            String logicalBodyId=ExternalBodyAccess.configureBodyId();
            SemanticWorldRegistry.start(server, ExternalBodyAccess.BOT_NAME);
            String token=System.getenv("AIBOT_BRIDGE_TOKEN");
            int port=Integer.parseInt(System.getenv().getOrDefault("AIBOT_BRIDGE_PORT","8765"));
            if(port<1024 || port>65535)throw new IllegalArgumentException("invalid_bridge_port");
            var bodyRoot=server.getSavePath(WorldSavePath.ROOT).resolve("aibot");
            journal=new BridgeJournal(bodyRoot.resolve("external-body-"+ExternalBodyAccess.BOT_NAME.toLowerCase(Locale.ROOT)+".journal"),System::currentTimeMillis);
            // Birth/terminal lifecycle receipts are the durability fence for opportunity identity.
            // Reconcile them before Graph construction and before the HTTP endpoint is reachable.
            SemanticWorldRegistry.reconcileOpportunityLifecycleReceipts(journal);
            var graphs=new TaskGraphStore(bodyRoot.resolve("task-graphs-"+ExternalBodyAccess.BOT_NAME.toLowerCase(Locale.ROOT)+".bin"),System::currentTimeMillis);
            kernel=new BridgeKernel(journal,new MinecraftBodyBackend(
                    server,ExternalBodyAccess.BOT_NAME,logicalBodyId),graphs);
            kernel.tick(); // fence restored legacy work before the network endpoint becomes reachable
            http=new BridgeHttpServer(kernel,port,token);http.start();
            observedBody=null;previousHealth=Float.NaN;previousAlive=false;nextSurvivalAlertTick.clear();
            AIBotMod.LOGGER.info(
                    "AIBot external-body bridge bound to loopback port {} for {} body_id={}",
                    port,ExternalBodyAccess.BOT_NAME,logicalBodyId);
        }catch(Exception failure){
            if(http!=null)http.close();
            try{if(journal!=null)journal.close();}catch(Exception ignored){}
            SemanticWorldRegistry.stop();
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
        if(!previousAlive && bot.isAlive())kernel.publish("respawn",Map.of(
                "body_id",ExternalBodyAccess.bodyId(),
                "body_instance_id",bot.getUuid().toString(),
                "health",bot.getHealth()));
        previousAlive=bot.isAlive();previousHealth=bot.getHealth();
    }
    public static void death(AIPlayerEntity bot) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return;
        String source=bot.getRecentDamageSource()==null?"unknown":bot.getRecentDamageSource().getName();
        kernel.publish("death",Map.of(
                "body_id",ExternalBodyAccess.bodyId(),
                "body_instance_id",bot.getUuid().toString(),
                "recent_damage_source",source,
                "causal_chain_complete",false,
                "x",bot.getX(),"y",bot.getY(),"z",bot.getZ(),
                "dimension",bot.getServerWorld().getRegistryKey().getValue().toString()));
        previousAlive=false;previousHealth=0;
    }
    public static void message(AIPlayerEntity bot,String sender,String text) {
        playerMessage(bot,null,sender,"legacy_brain_handle",false,text);
    }
    public static void playerMessage(AIPlayerEntity bot,UUID senderUuid,String senderName,
                                     String channel,boolean authorizedControl,String text) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return;
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("actor_kind","player");
        payload.put("sender_uuid",senderUuid==null?"":senderUuid.toString());
        payload.put("sender_name",bounded(senderName,80));
        payload.put("sender",bounded(senderName,80)); // M0 compatibility alias; never a synthetic authority label
        payload.put("channel",bounded(channel,80));
        payload.put("authorized_control",authorizedControl);
        payload.put("text",bounded(text,2000));
        payload.put("trust","untrusted_game_text");
        kernel.publish("player_message",payload);
    }
    public static void survivalAlert(AIPlayerEntity bot,String reason) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return;
        int now=bot.getServer().getTicks();
        String key=bot.getUuid()+":"+bounded(reason,80);
        if(now<nextSurvivalAlertTick.getOrDefault(key,0))return;
        nextSurvivalAlertTick.put(key,now+200);
        kernel.publish("survival_alert",Map.of(
                "reason",bounded(reason,80),
                "body_id",ExternalBodyAccess.bodyId(),
                "body_instance_id",bot.getUuid().toString(),
                "health",bot.getHealth(),
                "food",bot.getHungerManager().getFoodLevel(),
                "action","observe_and_replan"));
    }
    public static void resourceOpportunityActionable(AIPlayerEntity bot,String opportunityId,String blockId,
                                                     net.minecraft.util.math.BlockPos pos,
                                                     net.minecraft.util.math.BlockPos seenFrom) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return;
        kernel.publish("resource_opportunity_actionable",Map.of(
                "opportunity_id",opportunityId,"block",blockId,
                "x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),
                "seen_from_x",seenFrom.getX(),"seen_from_y",seenFrom.getY(),"seen_from_z",seenFrom.getZ(),
                "dimension",bot.getServerWorld().getRegistryKey().getValue().toString(),
                "world_id",SemanticWorldRegistry.worldId()));
    }
    public static boolean resourceOpportunityConsumed(AIPlayerEntity bot,String opportunityId,String blockId,
                                                      net.minecraft.util.math.BlockPos pos) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return false;
        String dimension=bot.getServerWorld().getRegistryKey().getValue().toString();
        String world=SemanticWorldRegistry.worldId();
        Map<String,Object> payload=Map.of(
                "opportunity_id",opportunityId,"block",blockId,
                "x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),
                "resolution","inventory_gain_proven",
                "dimension",dimension,"world_id",world);
        return kernel.recordOpportunityResolution("resource_opportunity_consumed",
                opportunityId,world,dimension,payload);
    }
    /**
     * R2.1 terminal tombstone: an opportunity left the active registry without the resource ever
     * being proven into inventory (externally consumed, or a pending pickup whose drop vanished).
     * Distinct from actionable/completion events so DSH never mistakes it for success.
     */
    public static boolean resourceOpportunityStale(AIPlayerEntity bot,String opportunityId,String blockId,
                                                   net.minecraft.util.math.BlockPos pos,String reason) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return false;
        String dimension=bot.getServerWorld().getRegistryKey().getValue().toString();
        String world=SemanticWorldRegistry.worldId();
        Map<String,Object> payload=Map.of(
                "opportunity_id",opportunityId,"block",blockId,
                "x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),
                "reason",bounded(reason,120),
                "dimension",dimension,"world_id",world);
        return kernel.recordOpportunityResolution("resource_opportunity_stale",
                opportunityId,world,dimension,payload);
    }
    private static String bounded(String s,int length){return s==null?"":s.length()<=length?s:s.substring(0,length);}
    public static void stop() {
        try{if(kernel!=null)kernel.shutdown();}
        catch(RuntimeException e){AIBotMod.LOGGER.error("external body shutdown requires reconciliation",e);}
        finally {
            SemanticWorldRegistry.stop();
            if(http!=null)http.close();
            try{if(journal!=null)journal.close();}catch(Exception e){AIBotMod.LOGGER.error("external journal close failed",e);}
            http=null;journal=null;kernel=null;observedBody=null;previousHealth=Float.NaN;previousAlive=false;nextSurvivalAlertTick.clear();
        }
    }
}
