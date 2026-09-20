package io.github.zoyluo.aibot.external;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.perception.PerceptionCollector;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.TaskManager;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Current server-side body backend.
 *
 * <p>It owns body discovery, physical binding and server observations. All bounded mutation/task
 * mechanics are delegated to ServerFakePlayerExecutionDriver.</p>
 */
public final class MinecraftBodyBackend implements BodyBackend {
    private static final Gson GSON=new Gson();

    private final MinecraftServer server;
    private final String name;
    private final String logicalBodyId;

    private AIPlayerEntity bot;
    private AIPlayerEntity prepared;
    private PhysicalExecutionDriver executionDriver;
    private Binding binding;

    private JsonElement perception;
    private int perceptionTick;
    private String perceptionDimension="";
    private JsonElement semantic;
    private int semanticTick;
    private String semanticDimension="";

    public MinecraftBodyBackend(MinecraftServer server,String name) {
        this(server,name,name.toLowerCase(Locale.ROOT));
    }

    public MinecraftBodyBackend(MinecraftServer server,String name,String logicalBodyId) {
        this.server=server;
        this.name=name;
        if(logicalBodyId==null
                || !logicalBodyId.matches("[a-z0-9][a-z0-9._:-]{0,79}"))
            throw new IllegalArgumentException("invalid_AIBOT_EXTERNAL_BODY_ID");
        this.logicalBodyId=logicalBodyId;
    }

    private void onThread() {
        if(!server.isOnThread())
            throw new IllegalStateException("minecraft_access_off_server_thread");
    }

    @Override public boolean ready() {
        onThread();
        bot=AIPlayerManager.INSTANCE.all().stream()
                .filter(candidate->name.equalsIgnoreCase(
                        candidate.getGameProfile().getName()))
                .findFirst().orElse(null);
        if(bot==null || !bot.isAlive())return false;

        if(prepared!=bot) {
            PhysicalExecutionDriver next=
                    new ServerFakePlayerExecutionDriver(server,bot,()->this.bot);
            prepared=bot;
            executionDriver=next;
            binding=new Binding(
                    logicalBodyId,
                    "server_fake_player",
                    bot.getUuid().toString(),
                    UUID.randomUUID().toString());
            perception=null;
            semantic=null;
            perceptionDimension="";
            semanticDimension="";
        }
        return true;
    }

    @Override public String bodyId() {
        return logicalBodyId;
    }

    @Override public Binding binding() {
        onThread();
        if(binding==null)throw new IllegalStateException("body_binding_unavailable");
        return binding;
    }

    @Override public String observeJson() {
        onThread();
        Binding current=binding();
        JsonObject out=new JsonObject();
        out.addProperty("body_id",current.bodyId());
        out.addProperty("backend_kind",current.backendKind());
        out.addProperty("body_instance_id",current.instanceId());
        out.addProperty("body_session_epoch",current.sessionEpoch());
        out.addProperty("name",bot.getGameProfile().getName());
        out.addProperty("minecraft_profile_uuid",bot.getUuid().toString());
        out.addProperty("profile",AIBotConfig.get().profile().configValue());
        out.addProperty("health",bot.getHealth());
        out.addProperty("food",bot.getHungerManager().getFoodLevel());
        out.addProperty(
                "dimension",
                bot.getServerWorld().getRegistryKey().getValue().toString());
        JsonObject position=new JsonObject();
        position.addProperty("x",bot.getX());
        position.addProperty("y",bot.getY());
        position.addProperty("z",bot.getZ());
        out.add("position",position);
        out.add("inventory",GSON.toJsonTree(inventory()));

        refreshCaches();
        int tick=server.getTicks();
        out.add("semantic_world",semantic);
        out.addProperty("semantic_age_ticks",Math.max(0,tick-semanticTick));
        out.add("perception",perception);
        out.addProperty("perception_age_ticks",Math.max(0,tick-perceptionTick));
        out.addProperty(
                "safety_active",
                TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false));
        out.addProperty("user_paused",TaskManager.INSTANCE.isUserPaused(bot));
        out.addProperty("current_task",TaskManager.INSTANCE.status(bot).name());
        out.addProperty("paused_depth",TaskManager.INSTANCE.pausedDepth(bot));
        return GSON.toJson(out);
    }

    private void refreshCaches() {
        String dimension=
                bot.getServerWorld().getRegistryKey().getValue().toString();
        int tick=server.getTicks();
        if(perception==null || tick-perceptionTick>=10 || tick<perceptionTick
                || !dimension.equals(perceptionDimension)) {
            perception=JsonParser.parseString(
                    PerceptionCollector.collect(bot).toJson());
            perceptionTick=tick;
            perceptionDimension=dimension;
        }
        if(semantic==null || tick-semanticTick>=10 || tick<semanticTick
                || !dimension.equals(semanticDimension)) {
            semantic=SemanticWorldRegistry.observeBounded(bot);
            semanticTick=tick;
            semanticDimension=dimension;
        }
    }

    @Override public io.github.zoyluo.aibot.external.cognition.CognitiveSnapshot.Snapshot cognitiveSnapshot(
            BridgeJournal journal) {
        onThread();
        if(bot==null || !bot.isAlive())return null;
        refreshCaches();
        JsonObject semanticSnapshot=
                semantic!=null && semantic.isJsonObject()
                        ?semantic.getAsJsonObject():null;
        return io.github.zoyluo.aibot.external.cognition.CognitiveViewBuilder
                .build(bot,journal,semanticSnapshot);
    }

    @Override public String inspectLocalJson(int radius,String detail) {
        onThread();
        if(bot==null || !bot.isAlive())
            throw new BridgeFault(409,"body_unavailable");
        return io.github.zoyluo.aibot.external.cognition.CognitiveInspector
                .inspectLocalJson(bot,radius,detail);
    }

    @Override public String materializeEvidence(
            String ref,String detail,long gameTime) {
        onThread();
        if(bot==null || !bot.isAlive())
            throw new BridgeFault(409,"body_unavailable");
        refreshCaches();
        JsonObject semanticSnapshot=
                semantic!=null && semantic.isJsonObject()
                        ?semantic.getAsJsonObject():null;
        return io.github.zoyluo.aibot.external.cognition.CognitiveInspector
                .materialize(bot,semanticSnapshot,gameTime,ref,detail);
    }

    @Override public long serverTick() {
        onThread();
        return server.getTicks();
    }

    @Override public GraphPostconditionResult verifyGraphPostcondition(
            TaskGraphStore.Postcondition postcondition) {
        onThread();
        if(bot==null || !bot.isAlive())
            return GraphPostconditionResult.unknown("body_unavailable");
        TaskGraphStore.SpatialRef ref=postcondition.subject();
        if(!SemanticWorldRegistry.worldId().equals(ref.worldId()))
            return GraphPostconditionResult.unknown("world_scope_mismatch");
        String dimension=
                bot.getServerWorld().getRegistryKey().getValue().toString();
        if(!dimension.equals(ref.dimensionId()))
            return GraphPostconditionResult.unknown("dimension_not_current");
        if("OPPORTUNITY_RESOLVED".equals(postcondition.kind())) {
            var remaining=SemanticWorldRegistry.opportunity(bot,ref.objectId());
            if(remaining.isPresent())
                return GraphPostconditionResult.unsatisfied(
                        "opportunity_still_"
                                +remaining.get().status().toLowerCase(Locale.ROOT));
            return GraphPostconditionResult.unknown(
                    "opportunity_absent_without_durable_resolution_receipt");
        }
        return GraphPostconditionResult.unknown(
                "unsupported_graph_postcondition");
    }

    @Override public Handle start(String operation,String argumentsJson) {
        return start("",operation,argumentsJson);
    }

    @Override public Handle start(
            String executionId,String operation,String argumentsJson) {
        onThread();
        return requireExecutionDriver().start(
                new PhysicalExecutionDriver.Request(
                        executionId,operation,argumentsJson));
    }

    @Override public void pause() {
        onThread();
        if(executionDriver!=null)executionDriver.pause();
    }

    @Override public void resume() {
        onThread();
        if(executionDriver!=null)executionDriver.resume();
    }

    @Override public void cancel(String reason) {
        onThread();
        if(executionDriver!=null)executionDriver.cancel(reason);
    }

    private PhysicalExecutionDriver requireExecutionDriver() {
        if(bot==null || !bot.isAlive() || executionDriver==null)
            throw new BridgeFault(409,"body_unavailable");
        return executionDriver;
    }

    private Map<String,Integer> inventory() {
        Map<String,Integer> counts=new TreeMap<>();
        for(int i=0;i<bot.getInventory().size();i++) {
            ItemStack stack=bot.getInventory().getStack(i);
            if(!stack.isEmpty())
                counts.merge(
                        Registries.ITEM.getId(stack.getItem()).toString(),
                        stack.getCount(),
                        Integer::sum);
        }
        return counts;
    }
}
