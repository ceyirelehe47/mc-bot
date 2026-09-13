package io.github.zoyluo.aibot.client.realclient;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/** Opt-in real-player body runtime. Disabled for ordinary PCL2/human clients. */
public final class RealClientBodyClientRuntime {
    private static boolean registered;
    private static RealClientClientTransport transport;
    private static RealClientActionController actions;
    private static int heartbeatTick;
    private static boolean controlWasConnected;
    private static String controlSessionEpoch="";
    // Minecraft 游戏连接 incarnation:每次实际 JOIN 生成新 epoch 并递增单调计数。
    // 它与控制 TCP 的 transport epoch 分离,服务端用它做 physical session fencing。
    private static volatile String gameSessionEpoch="";
    private static volatile int gameSessionSeq=-1;
    private static volatile long frameSeq=-1;
    // 本地 Loom/DLI dev 客户端里 vanilla --quickPlayMultiplayer 不会触发自动连接
    // (TitleScreen 路径未消费该参数);supervisor 重启闭环需要程序化重连,
    // 故以显式环境变量 opt-in 直连。普通玩家客户端不设此变量,行为不变。
    private static String autoJoinTarget;
    private static long nextAutoJoinAttemptMs;
    private static int autoJoinAttempts;

    private RealClientBodyClientRuntime() {}

    public static synchronized void register() {
        if(registered)return;
        registered=true;
        if(!"1".equals(System.getenv().getOrDefault("AIBOT_REAL_CLIENT","0")))return;
        String host=System.getenv().getOrDefault("AIBOT_REAL_CLIENT_HOST","127.0.0.1");
        int port=Integer.parseInt(System.getenv().getOrDefault(
                "AIBOT_REAL_CLIENT_PORT","8766"));
        String token=System.getenv("AIBOT_REAL_CLIENT_TOKEN");
        String bodyId=System.getenv().getOrDefault("AIBOT_REAL_CLIENT_BODY_ID","bob").trim();
        String playerName=System.getenv().getOrDefault("AIBOT_REAL_CLIENT_BOT_NAME","Bob").trim();
        if(token==null || token.length()<32)
            throw new IllegalArgumentException("AIBOT_REAL_CLIENT_TOKEN_missing_or_short");
        autoJoinTarget=System.getenv("AIBOT_REAL_CLIENT_AUTO_JOIN");
        transport=new RealClientClientTransport(host,port,token,bodyId,playerName);
        actions=new RealClientActionController(transport);
        transport.start();
        ClientTickEvents.END_CLIENT_TICK.register(
                RealClientBodyClientRuntime::tick);
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->
                gameSessionStarted(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{
            if(actions!=null)actions.disconnected(client);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->{
            if(actions!=null)actions.disconnected(client);
            if(transport!=null)transport.close();
        });
        AIBotMod.LOGGER.info(
                "AIBot real-client runtime enabled body_id={} player={} control={}:{} auto_join={}",
                bodyId,playerName,host,port,
                autoJoinTarget==null?"disabled":autoJoinTarget);
    }

    private static void tick(MinecraftClient client) {
        if(transport==null || actions==null)return;
        maybeAutoJoin(client);
        boolean connected=transport.connected();
        String epoch=transport.sessionEpoch();
        if(controlWasConnected && (!connected || !controlSessionEpoch.equals(epoch)))
            actions.controlSessionLost(client);
        controlWasConnected=connected;
        controlSessionEpoch=epoch;
        JsonObject message;
        while((message=transport.poll())!=null) {
            String type=message.has("type")?message.get("type").getAsString():"";
            if("command".equals(type))actions.command(message);
            else if("control".equals(type))actions.control(message,client);
        }
        actions.tick(client);
        if(++heartbeatTick%10==0)heartbeat(client);
    }

    private static String lastAutoJoinScreen="";

    /** 每次实际 Minecraft JOIN 开启新游戏 incarnation:旧 action 不跨会话存续,帧序号重置。 */
    private static void gameSessionStarted(MinecraftClient client) {
        gameSessionEpoch=java.util.UUID.randomUUID().toString();
        gameSessionSeq++;
        frameSeq=-1;
        if(transport!=null)transport.bindGameSession(gameSessionEpoch,gameSessionSeq);
        if(actions!=null)actions.gameSessionStarted(client);
        AIBotMod.LOGGER.info(
                "AIBot real-client game session incarnation epoch={} seq={}",
                gameSessionEpoch,gameSessionSeq);
    }

    /** 显式 opt-in 的游戏服直连:仅空闲界面触发、5 秒节流,避免打断连接/登录流程。 */
    private static void maybeAutoJoin(MinecraftClient client) {
        if(autoJoinTarget==null || autoJoinTarget.isBlank())return;
        if(client.world!=null || client.currentScreen==null)return;
        String screen=client.currentScreen.getClass().getName();
        if(!screen.equals(lastAutoJoinScreen)) {
            lastAutoJoinScreen=screen;
            AIBotMod.LOGGER.info("AIBot real-client auto-join watcher screen={}",screen);
        }
        boolean idle=screen.endsWith("TitleScreen") || screen.endsWith("MultiplayerScreen")
                || screen.endsWith("SelectServerScreen") || screen.endsWith("DisconnectedScreen")
                // DLI dev 客户端偶尔无视 options.txt 的 onboardAccessibility:false 而停在
                // 首启 onboarding 系列;直接从该屏发起连接即可替换它,无需任何人工输入。
                || screen.endsWith("AccessibilityOnboardingScreen")
                || screen.endsWith("AccessibilityOptionsScreen")
                || screen.endsWith("LanguageOptionsScreen");
        if(!idle)return;
        long now=System.currentTimeMillis();
        if(now<nextAutoJoinAttemptMs)return;
        nextAutoJoinAttemptMs=now+5000L;
        AIBotMod.LOGGER.info("AIBot real-client auto-join {} (attempt {})",
                autoJoinTarget,++autoJoinAttempts);
        net.minecraft.client.network.ServerAddress address=
                net.minecraft.client.network.ServerAddress.parse(autoJoinTarget);
        net.minecraft.client.network.ServerInfo info=new net.minecraft.client.network.ServerInfo(
                "aibot-real-client",autoJoinTarget,
                net.minecraft.client.network.ServerInfo.ServerType.OTHER);
        net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(
                client.currentScreen,client,address,info,true,
                new net.minecraft.client.network.CookieStorage(java.util.Map.of()));
    }

    private static void heartbeat(MinecraftClient client) {
        if(!transport.connected() || client.player==null || client.world==null)return;
        if(gameSessionEpoch.isBlank())return;
        JsonObject heartbeat=new JsonObject();
        heartbeat.addProperty("type","heartbeat");
        heartbeat.addProperty("game_session",gameSessionEpoch);
        heartbeat.addProperty("game_session_seq",gameSessionSeq);
        heartbeat.addProperty("frame_seq",++frameSeq);
        heartbeat.addProperty("player_uuid",client.player.getUuidAsString());
        heartbeat.addProperty("x",client.player.getX());
        heartbeat.addProperty("y",client.player.getY());
        heartbeat.addProperty("z",client.player.getZ());
        // 客户端 crosshair 由 getRotationVec(1F)=fromPolar(pitch,headYaw) 计算,
        // 同帧重建必须报告 crosshair 真正使用的视线,而不是 body yaw 字段。
        heartbeat.addProperty("yaw",client.player.getHeadYaw());
        heartbeat.addProperty("pitch",client.player.getPitch());
        heartbeat.addProperty("selected_slot",client.player.getInventory().selectedSlot);
        if(client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType()==HitResult.Type.BLOCK) {
            var state=client.world.getBlockState(hit.getBlockPos());
            heartbeat.addProperty("crosshair_present",true);
            heartbeat.addProperty("crosshair_x",hit.getBlockPos().getX());
            heartbeat.addProperty("crosshair_y",hit.getBlockPos().getY());
            heartbeat.addProperty("crosshair_z",hit.getBlockPos().getZ());
            heartbeat.addProperty(
                    "crosshair_block",Registries.BLOCK.getId(state.getBlock()).toString());
            heartbeat.addProperty("crosshair_side",hit.getSide().name());
        } else {
            heartbeat.addProperty("crosshair_present",false);
        }
        // LIVE 诊断:每 20 次心跳汇报一次 crosshair 状态,定位传感器链路。
        if(heartbeatTick%200==0) {
            AIBotMod.LOGGER.info(
                    "AIBot real-client heartbeat diag crosshair_present={} target={}",
                    heartbeat.has("crosshair_present")&&heartbeat.get("crosshair_present").getAsBoolean(),
                    client.crosshairTarget==null?"null":client.crosshairTarget.getType()+":"
                            +(client.crosshairTarget instanceof BlockHitResult b?b.getBlockPos():"-"));
        }
        transport.send(heartbeat);
    }
}
