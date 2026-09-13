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
    private static RealClientScreenController screens;
    private static int heartbeatTick;
    private static boolean controlWasConnected;
    private static String controlSessionEpoch="";
    private static volatile String gameSessionEpoch="";
    private static volatile int gameSessionSeq=-1;
    private static volatile long frameSeq=-1;
    private static String autoJoinTarget;
    private static long nextAutoJoinAttemptMs;
    private static int autoJoinAttempts;
    private static String lastAutoJoinScreen="";

    private RealClientBodyClientRuntime() {}

    public static synchronized void register() {
        if(registered)return;
        registered=true;
        if(!RealClientInputIsolation.enabled())return;
        String host=System.getenv().getOrDefault(
                "AIBOT_REAL_CLIENT_HOST","127.0.0.1");
        int port=Integer.parseInt(
                System.getenv().getOrDefault(
                        "AIBOT_REAL_CLIENT_PORT","8766"));
        String token=System.getenv("AIBOT_REAL_CLIENT_TOKEN");
        String bodyId=System.getenv().getOrDefault(
                "AIBOT_REAL_CLIENT_BODY_ID","bob").trim();
        String playerName=System.getenv().getOrDefault(
                "AIBOT_REAL_CLIENT_BOT_NAME","Bob").trim();
        if(token==null || token.length()<32)
            throw new IllegalArgumentException(
                    "AIBOT_REAL_CLIENT_TOKEN_missing_or_short");
        autoJoinTarget=System.getenv(
                "AIBOT_REAL_CLIENT_AUTO_JOIN");
        transport=new RealClientClientTransport(
                host,port,token,bodyId,playerName,
                RealClientInputIsolation.modeName());
        screens=new RealClientScreenController(transport);
        actions=new RealClientActionController(
                transport,screens);
        transport.start();

        ClientTickEvents.END_CLIENT_TICK.register(
                RealClientBodyClientRuntime::tick);
        ClientPlayConnectionEvents.JOIN.register(
                (handler,sender,client)->
                        gameSessionStarted(client));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler,client)->{
                    if(actions!=null)
                        actions.disconnected(client);
                    if(screens!=null)
                        screens.disconnected();
                    if(transport!=null)
                        transport.clearGameSession();
                    gameSessionEpoch="";
                    frameSeq=-1L;
                });
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                client->{
                    if(actions!=null)
                        actions.disconnected(client);
                    if(screens!=null)
                        screens.disconnected();
                    if(transport!=null)transport.close();
                });
        AIBotMod.LOGGER.info(
                "AIBot real-client runtime enabled "
                        +"body_id={} player={} control={}:{} "
                        +"auto_join={} window_mode={}",
                bodyId,playerName,host,port,
                autoJoinTarget==null
                        ?"disabled":autoJoinTarget,
                RealClientInputIsolation.modeName());
    }

    private static void tick(MinecraftClient client) {
        if(transport==null || actions==null || screens==null)
            return;
        RealClientInputIsolation.beforeActions(client);
        maybeAutoJoin(client);

        boolean connected=transport.connected();
        String epoch=transport.sessionEpoch();
        if(controlWasConnected
                && (!connected
                || !controlSessionEpoch.equals(epoch)))
            actions.controlSessionLost(client);
        controlWasConnected=connected;
        controlSessionEpoch=epoch;

        JsonObject message;
        while((message=transport.poll())!=null) {
            String type=message.has("type")
                    ?message.get("type").getAsString():"";
            if("command".equals(type))
                actions.command(message);
            else if("control".equals(type))
                actions.control(message,client);
        }
        actions.tick(client);
        screens.tick(client);
        if(++heartbeatTick%10==0)heartbeat(client);
    }

    private static void gameSessionStarted(
            MinecraftClient client) {
        gameSessionEpoch=java.util.UUID.randomUUID().toString();
        gameSessionSeq++;
        frameSeq=-1;
        if(transport!=null)
            transport.bindGameSession(gameSessionEpoch,gameSessionSeq);
        if(actions!=null)
            actions.gameSessionStarted(client);
        if(screens!=null)
            screens.gameSessionStarted();
        RealClientInputIsolation.onGameJoin(client);
        AIBotMod.LOGGER.info(
                "AIBot real-client game session incarnation "
                        +"epoch={} seq={} window_mode={}",
                gameSessionEpoch,gameSessionSeq,
                RealClientInputIsolation.modeName());
    }

    private static void maybeAutoJoin(
            MinecraftClient client) {
        if(autoJoinTarget==null || autoJoinTarget.isBlank())
            return;
        if(client.world!=null || client.currentScreen==null)
            return;
        String screen=client.currentScreen.getClass().getName();
        if(!screen.equals(lastAutoJoinScreen)) {
            lastAutoJoinScreen=screen;
            AIBotMod.LOGGER.info(
                    "AIBot real-client auto-join watcher screen={}",
                    screen);
        }
        boolean idle=screen.endsWith("TitleScreen")
                || screen.endsWith("MultiplayerScreen")
                || screen.endsWith("SelectServerScreen")
                || screen.endsWith("DisconnectedScreen")
                || screen.endsWith(
                        "AccessibilityOnboardingScreen")
                || screen.endsWith(
                        "AccessibilityOptionsScreen")
                || screen.endsWith("LanguageOptionsScreen");
        if(!idle)return;
        long now=System.currentTimeMillis();
        if(now<nextAutoJoinAttemptMs)return;
        nextAutoJoinAttemptMs=now+5000L;
        AIBotMod.LOGGER.info(
                "AIBot real-client auto-join {} (attempt {})",
                autoJoinTarget,++autoJoinAttempts);
        net.minecraft.client.network.ServerAddress address=
                net.minecraft.client.network.ServerAddress.parse(
                        autoJoinTarget);
        net.minecraft.client.network.ServerInfo info=
                new net.minecraft.client.network.ServerInfo(
                        "aibot-real-client",autoJoinTarget,
                        net.minecraft.client.network.ServerInfo
                                .ServerType.OTHER);
        net.minecraft.client.gui.screen.multiplayer.ConnectScreen
                .connect(
                        client.currentScreen,client,address,info,
                        true,
                        new net.minecraft.client.network.CookieStorage(
                                java.util.Map.of()));
    }

    private static void heartbeat(
            MinecraftClient client) {
        if(!transport.connected()
                || !transport.gameSessionBound()
                || client.player==null
                || client.world==null)
            return;
        JsonObject heartbeat=new JsonObject();
        heartbeat.addProperty("type","heartbeat");
        heartbeat.addProperty("frame_seq",++frameSeq);
        heartbeat.addProperty(
                "player_uuid",client.player.getUuidAsString());
        heartbeat.addProperty("x",client.player.getX());
        heartbeat.addProperty("y",client.player.getY());
        heartbeat.addProperty("z",client.player.getZ());
        heartbeat.addProperty(
                "yaw",client.player.getHeadYaw());
        heartbeat.addProperty(
                "pitch",client.player.getPitch());
        heartbeat.addProperty(
                "selected_slot",
                client.player.getInventory().selectedSlot);
        if(client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType()==HitResult.Type.BLOCK) {
            var state=client.world.getBlockState(
                    hit.getBlockPos());
            heartbeat.addProperty(
                    "crosshair_present",true);
            heartbeat.addProperty(
                    "crosshair_x",hit.getBlockPos().getX());
            heartbeat.addProperty(
                    "crosshair_y",hit.getBlockPos().getY());
            heartbeat.addProperty(
                    "crosshair_z",hit.getBlockPos().getZ());
            heartbeat.addProperty(
                    "crosshair_block",
                    Registries.BLOCK.getId(
                            state.getBlock()).toString());
            heartbeat.addProperty(
                    "crosshair_side",hit.getSide().name());
        } else {
            heartbeat.addProperty(
                    "crosshair_present",false);
        }
        transport.send(heartbeat);
    }
}
