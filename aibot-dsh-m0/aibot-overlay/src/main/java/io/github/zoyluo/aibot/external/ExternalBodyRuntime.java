package io.github.zoyluo.aibot.external;

import io.github.zoyluo.aibot.AIBotMod;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.realclient.RealClientBodyBackend;
import io.github.zoyluo.aibot.external.realclient.RealClientOpportunityTracker;
import io.github.zoyluo.aibot.external.realclient.RealClientPlacementWitness;
import io.github.zoyluo.aibot.external.realclient.RealClientServerTransport;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Lifecycle wiring. One selected physical authority; no second LLM loop or async Minecraft access. */
public final class ExternalBodyRuntime {
    private static BridgeKernel kernel;
    private static BridgeJournal journal;
    private static BridgeHttpServer http;
    private static RealClientServerTransport realClientTransport;
    private static UUID observedBody;
    private static float previousHealth=Float.NaN;
    private static boolean previousAlive;
    private static int autoRespawnAtTick=-1;
    private static double lastDeathX,lastDeathY,lastDeathZ;
    private static ServerWorld lastDeathWorld;
    private static final Map<String,Integer> nextSurvivalAlertTick=new HashMap<>();

    private ExternalBodyRuntime() {}

    public static void start(MinecraftServer server) {
        if(!ExternalBodyAccess.enabled())return;
        ExternalBodyAccess.activateReservation();
        try {
            if(!ExternalBodyAccess.BOT_NAME.matches("[A-Za-z0-9_]{1,16}"))
                throw new IllegalArgumentException("invalid_AIBOT_EXTERNAL_BOT");
            String logicalBodyId=ExternalBodyAccess.configureBodyId();
            String backendKind=ExternalBodyAccess.configureBackendKind();
            SemanticWorldRegistry.start(server,ExternalBodyAccess.BOT_NAME);
            String token=System.getenv("AIBOT_BRIDGE_TOKEN");
            int port=Integer.parseInt(System.getenv().getOrDefault(
                    "AIBOT_BRIDGE_PORT","8765"));
            if(port<1024 || port>65535)throw new IllegalArgumentException("invalid_bridge_port");
            var bodyRoot=server.getSavePath(WorldSavePath.ROOT).resolve("aibot");
            journal=new BridgeJournal(bodyRoot.resolve(
                    "external-body-"+ExternalBodyAccess.BOT_NAME.toLowerCase(Locale.ROOT)
                            +".journal"),System::currentTimeMillis);
            SemanticWorldRegistry.reconcileOpportunityLifecycleReceipts(journal);
            var graphs=new TaskGraphStore(bodyRoot.resolve(
                    "task-graphs-"+ExternalBodyAccess.BOT_NAME.toLowerCase(Locale.ROOT)
                            +".bin"),System::currentTimeMillis);

            BodyBackend backend;
            if("real_client".equals(backendKind)) {
                if(AIPlayerManager.INSTANCE.all().stream().anyMatch(candidate->
                        ExternalBodyAccess.BOT_NAME.equalsIgnoreCase(
                                candidate.getGameProfile().getName())))
                    throw new IllegalStateException(
                            "real_client_fake_player_authority_conflict");
                String realToken=System.getenv().getOrDefault(
                        "AIBOT_REAL_CLIENT_TOKEN",token==null?"":token);
                int realPort=Integer.parseInt(System.getenv().getOrDefault(
                        "AIBOT_REAL_CLIENT_PORT","8766"));
                boolean requireOffline=!"0".equals(System.getenv().getOrDefault(
                        "AIBOT_REAL_CLIENT_REQUIRE_OFFLINE_UUID","1"));
                // R2/R07:place 完成归因需要服务器侧"本次交互"见证。
                RealClientPlacementWitness.register();
                realClientTransport=new RealClientServerTransport(
                        logicalBodyId,ExternalBodyAccess.BOT_NAME,realToken,realPort);
                RealClientOpportunityTracker tracker=new RealClientOpportunityTracker(journal);
                backend=new RealClientBodyBackend(
                        server,ExternalBodyAccess.BOT_NAME,logicalBodyId,
                        realClientTransport,tracker,journal,requireOffline);
                realClientTransport.start();
            } else {
                backend=new MinecraftBodyBackend(
                        server,ExternalBodyAccess.BOT_NAME,logicalBodyId);
            }

            kernel=new BridgeKernel(journal,backend,graphs);
            kernel.tick();
            http=new BridgeHttpServer(kernel,port,token);
            http.start();
            observedBody=null;
            previousHealth=Float.NaN;
            previousAlive=false;
            nextSurvivalAlertTick.clear();
            AIBotMod.LOGGER.info(
                    "AIBot external-body bridge bound to loopback port {} for {} body_id={} backend={}",
                    port,ExternalBodyAccess.BOT_NAME,logicalBodyId,backendKind);
        } catch(Exception failure) {
            if(http!=null)http.close();
            if(realClientTransport!=null)realClientTransport.close();
            try { if(journal!=null)journal.close(); } catch(Exception ignored) {}
            SemanticWorldRegistry.stop();
            http=null;journal=null;kernel=null;realClientTransport=null;
            throw new IllegalStateException("external_bridge_start_failed_closed",failure);
        }
    }

    public static void tick(MinecraftServer server) {
        if(kernel==null)return;
        kernel.tick();
        ServerPlayerEntity physical;
        if("server_fake_player".equals(ExternalBodyAccess.backendKind())) {
            physical=AIPlayerManager.INSTANCE.all().stream()
                    .filter(ExternalBodyAccess::reserved).findFirst().orElse(null);
        } else {
            physical=server.getPlayerManager().getPlayerList().stream()
                    .filter(player->ExternalBodyAccess.BOT_NAME.equalsIgnoreCase(
                            player.getGameProfile().getName()))
                    .filter(player->!(player instanceof AIPlayerEntity))
                    .findFirst().orElse(null);
        }
        if(physical==null) {
            observedBody=null;previousHealth=Float.NaN;previousAlive=false;return;
        }
        if(!physical.getUuid().equals(observedBody)) {
            observedBody=physical.getUuid();previousHealth=physical.getHealth();
            previousAlive=physical.isAlive();return;
        }
        if(previousAlive && physical.isAlive() && physical.getHealth()<previousHealth)
            kernel.publish("damage",Map.of(
                    "previous_health",previousHealth,"health",physical.getHealth(),
                    "source","health_delta_not_causal_attribution"));
        if(!previousAlive && physical.isAlive())kernel.publish("respawn",Map.of(
                "body_id",ExternalBodyAccess.bodyId(),
                "body_instance_id",physical.getUuid().toString(),
                "health",physical.getHealth()));
        if(previousAlive && !physical.isAlive()) {
            String source=physical.getRecentDamageSource()==null
                    ?"unknown":physical.getRecentDamageSource().getName();
            kernel.publish("death",Map.of(
                    "body_id",ExternalBodyAccess.bodyId(),
                    "body_instance_id",physical.getUuid().toString(),
                    "recent_damage_source",source,
                    "causal_chain_complete",false,
                    "x",physical.getX(),"y",physical.getY(),"z",physical.getZ(),
                    "dimension",physical.getServerWorld().getRegistryKey().getValue().toString()));
            lastDeathX=physical.getX();lastDeathY=physical.getY();lastDeathZ=physical.getZ();
            lastDeathWorld=physical.getServerWorld();
            autoRespawnAtTick=server.getTicks()+20;
        }
        if(autoRespawnAtTick<=0 && !physical.isAlive()) {
            // 启动/重连时已处于死亡态(客户端卡死亡屏幕):同样调度自动重生
            autoRespawnAtTick=server.getTicks()+40;
        }
        if(autoRespawnAtTick>0 && server.getTicks()>=autoRespawnAtTick && !physical.isAlive()) {
            if(lastDeathWorld!=null && hostileNearby(lastDeathWorld)) {
                // 重生点仍有敌对生物:推迟重生,避免复活即死的无限循环
                autoRespawnAtTick=server.getTicks()+100;
            } else {
                autoRespawnAtTick=-1;
                AIBotMod.LOGGER.info("[AIBot] LIFECYCLE event=auto_respawn bot=- {body_instance_id={}}",
                        physical.getUuid());
                try {
                    server.getPlayerManager().respawnPlayer(
                            physical,false,net.minecraft.entity.Entity.RemovalReason.KILLED);
                } catch(RuntimeException respawnFailure) {
                    AIBotMod.LOGGER.warn("[AIBot] auto_respawn failed: {}",
                            respawnFailure.toString());
                }
            }
        }
        previousAlive=physical.isAlive();previousHealth=physical.getHealth();
    }

    private static boolean hostileNearby(ServerWorld world) {
        net.minecraft.util.math.Box area=net.minecraft.util.math.Box.of(
                new net.minecraft.util.math.Vec3d(lastDeathX,lastDeathY,lastDeathZ),16,12,16);
        return !world.getEntitiesByClass(
                net.minecraft.entity.mob.HostileEntity.class,area,
                hostile->hostile.isAlive()).isEmpty();
    }

    public static void death(AIPlayerEntity bot) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return;
        String source=bot.getRecentDamageSource()==null
                ?"unknown":bot.getRecentDamageSource().getName();
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

    public static void playerMessage(
            AIPlayerEntity bot,UUID senderUuid,String senderName,
            String channel,boolean authorizedControl,String text) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return;
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("actor_kind","player");
        payload.put("sender_uuid",senderUuid==null?"":senderUuid.toString());
        payload.put("sender_name",bounded(senderName,80));
        payload.put("sender",bounded(senderName,80));
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

    public static void resourceOpportunityActionable(
            AIPlayerEntity bot,String opportunityId,String blockId,
            net.minecraft.util.math.BlockPos pos,
            net.minecraft.util.math.BlockPos seenFrom) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return;
        kernel.publish("resource_opportunity_actionable",Map.of(
                "opportunity_id",opportunityId,"block",blockId,
                "x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),
                "seen_from_x",seenFrom.getX(),"seen_from_y",seenFrom.getY(),
                "seen_from_z",seenFrom.getZ(),
                "dimension",bot.getServerWorld().getRegistryKey().getValue().toString(),
                "world_id",SemanticWorldRegistry.worldId()));
    }

    public static boolean resourceOpportunityConsumed(
            AIPlayerEntity bot,String opportunityId,String blockId,
            net.minecraft.util.math.BlockPos pos) {
        if(kernel==null || !ExternalBodyAccess.reserved(bot))return false;
        String dimension=bot.getServerWorld().getRegistryKey().getValue().toString();
        String world=SemanticWorldRegistry.worldId();
        Map<String,Object> payload=Map.of(
                "opportunity_id",opportunityId,"block",blockId,
                "x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),
                "resolution","inventory_gain_proven",
                "dimension",dimension,"world_id",world);
        return kernel.recordOpportunityResolution(
                "resource_opportunity_consumed",opportunityId,world,dimension,payload);
    }

    public static boolean resourceOpportunityStale(
            AIPlayerEntity bot,String opportunityId,String blockId,
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

    private static String bounded(String value,int length) {
        return value==null?"":value.length()<=length?value:value.substring(0,length);
    }

    public static void stop() {
        try { if(kernel!=null)kernel.shutdown(); }
        catch(RuntimeException failure) {
            AIBotMod.LOGGER.error("external body shutdown requires reconciliation",failure);
        } finally {
            SemanticWorldRegistry.stop();
            if(http!=null)http.close();
            if(realClientTransport!=null)realClientTransport.close();
            try { if(journal!=null)journal.close(); }
            catch(Exception failure) {
                AIBotMod.LOGGER.error("external journal close failed",failure);
            }
            http=null;journal=null;kernel=null;realClientTransport=null;
            observedBody=null;previousHealth=Float.NaN;previousAlive=false;
            nextSurvivalAlertTick.clear();
        }
    }
}
