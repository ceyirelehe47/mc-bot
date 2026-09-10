package io.github.zoyluo.aibot.external;

import com.google.gson.*;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.perception.PerceptionCollector;
import io.github.zoyluo.aibot.persist.BotPersistence;
import io.github.zoyluo.aibot.runtime.IntentController;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Integration adapter only: this is the part requiring the real Fabric/Minecraft classpath. */
public final class MinecraftBodyBackend implements BodyBackend {
    private static final Gson GSON=new Gson();
    private final MinecraftServer server;
    private AIPlayerEntity bot, prepared;
    private Task managed;
    private JsonElement perception;
    private int perceptionTick;
    private String perceptionDimension="";
    private JsonElement semantic;
    private int semanticTick;
    private String semanticDimension="";
    private final String name;
    public MinecraftBodyBackend(MinecraftServer server,String name) { this.server=server; this.name=name; }
    private void onThread() { if(!server.isOnThread())throw new IllegalStateException("minecraft_access_off_server_thread"); }
    @Override public boolean ready() {
        onThread();
        bot=AIPlayerManager.INSTANCE.all().stream().filter(b->name.equalsIgnoreCase(b.getGameProfile().getName())).findFirst().orElse(null);
        if(bot==null || !bot.isAlive()) return false;
        if(prepared!=bot) {
            // Explicit external-mode startup fences saved legacy work before admitting commands.
            ExternalBodyAccess.dispatch(()->{
                IntentController.INSTANCE.cancelAll(bot,IntentController.ControlOrigin.SYSTEM,"external_mode_enter");
                BrainCoordinator.INSTANCE.reset(bot);
                return null;
            });
            prepared=bot; managed=null; perception=null; semantic=null;
        }
        return true;
    }
    @Override public String bodyId() { onThread();return bot==null?"":bot.getUuid().toString(); }
    @Override public String observeJson() {
        onThread();
        JsonObject out=new JsonObject();
        out.addProperty("body_id",bot.getUuid().toString()); out.addProperty("name",bot.getGameProfile().getName());
        out.addProperty("profile",AIBotConfig.get().profile().configValue());
        out.addProperty("health",bot.getHealth()); out.addProperty("food",bot.getHungerManager().getFoodLevel());
        out.addProperty("dimension",bot.getServerWorld().getRegistryKey().getValue().toString());
        JsonObject position=new JsonObject(); position.addProperty("x",bot.getX());position.addProperty("y",bot.getY());position.addProperty("z",bot.getZ());out.add("position",position);
        out.add("inventory",GSON.toJsonTree(inventory()));
        String dimension=bot.getServerWorld().getRegistryKey().getValue().toString();
        int tick=server.getTicks();
        if(perception==null || tick-perceptionTick>=10 || tick<perceptionTick || !dimension.equals(perceptionDimension)) {
            perception=JsonParser.parseString(PerceptionCollector.collect(bot).toJson());
            perceptionTick=tick;perceptionDimension=dimension;
        }
        if(semantic==null || tick-semanticTick>=10 || tick<semanticTick || !dimension.equals(semanticDimension)) {
            semantic=SemanticWorldRegistry.observe(bot); semanticTick=tick; semanticDimension=dimension;
        }
        out.add("semantic_world",semantic);
        out.addProperty("semantic_age_ticks",Math.max(0,tick-semanticTick));
        out.add("perception",perception);
        out.addProperty("perception_age_ticks",Math.max(0,tick-perceptionTick));
        out.addProperty("safety_active",TaskManager.INSTANCE.activeOrigin(bot).map(TaskOrigin::safety).orElse(false));
        out.addProperty("user_paused",TaskManager.INSTANCE.isUserPaused(bot));
        out.addProperty("current_task",TaskManager.INSTANCE.status(bot).name());
        out.addProperty("paused_depth",TaskManager.INSTANCE.pausedDepth(bot));
        return GSON.toJson(out);
    }
    private Map<String,Integer> inventory() {
        Map<String,Integer> counts=new TreeMap<>();
        for(int i=0;i<bot.getInventory().size();i++) {
            ItemStack stack=bot.getInventory().getStack(i);
            if(!stack.isEmpty())counts.merge(Registries.ITEM.getId(stack.getItem()).toString(),stack.getCount(),Integer::sum);
        }
        return counts;
    }
    @Override public Handle start(String operation,String raw) {
        onThread();
        if(bot==null || !bot.isAlive())throw new BridgeFault(409,"body_unavailable");
        if(!"strict_survival".equals(AIBotConfig.get().profile().configValue()))throw new BridgeFault(403,"strict_survival_required");
        JsonObject args=parse(raw);
        Set<String> allowed=switch(operation) {
            case "goto" -> Set.of("x","y","z","allow_terrain_changes");
            case "gather","craft" -> Set.of("item","count");
            case "smelt" -> Set.of("input_item","output_item","count");
            case "say" -> Set.of("message");
            case "register_home" -> Set.of("name","radius","below","above");
            case "capture_home","repair_home" -> Set.of("name");
            case "register_farm" -> Set.of("name","radius","crop");
            case "tend_farm" -> Set.of("name");
            case "mine_opportunity" -> Set.of("id");
            case "eat","set_base","deposit" -> Set.of();
            default -> throw new BridgeFault(400,"unsupported_operation");
        };
        if(!allowed.containsAll(args.keySet()))throw new BridgeFault(400,"unknown_argument");
        Task task;Item target=null;int count=0;BlockPos goal=null;String semanticId=null;
        switch(operation) {
            case "goto" -> {
                if(!args.has("allow_terrain_changes") || !args.get("allow_terrain_changes").isJsonPrimitive()
                        || !args.getAsJsonPrimitive("allow_terrain_changes").isBoolean() || !args.get("allow_terrain_changes").getAsBoolean())
                    throw new BridgeFault(400,"goto_can_dig_explicit_allow_terrain_changes_required");
                goal=new BlockPos(integer(args,"x",-29999984,29999984),integer(args,"y",bot.getServerWorld().getBottomY(),bot.getServerWorld().getBottomY()+bot.getServerWorld().getHeight()-1),integer(args,"z",-29999984,29999984));
                if(bot.getBlockPos().getSquaredDistance(goal)>128*128)throw new BridgeFault(400,"goto_distance_limit_128");
                task=new MoveTask(bot,goal);
            }
            case "gather" -> {target=item(args,"item");count=integer(args,"count",1,256);task=new GatherQuotaTask(target,count);}
            case "craft" -> {target=item(args,"item");count=integer(args,"count",1,64);task=new CraftTask(target,count);}
            case "smelt" -> {target=item(args,"output_item");count=integer(args,"count",1,64);task=new SmeltTask(item(args,"input_item"),target,count);}
            case "eat" -> task=new EatTask();
            case "register_home" -> {
                try {
                    var registration=SemanticWorldRegistry.registerHome(bot,
                            optionalString(args,"name","home",32),
                            optionalInt(args,"radius",6,2,16),
                            optionalInt(args,"below",1,0,8),
                            optionalInt(args,"above",6,1,16));
                    return semanticRegistrationHandle(registration);
                } catch (IllegalArgumentException | IllegalStateException semanticFailure) {
                    throw semanticFault(semanticFailure);
                }
            }
            case "capture_home" -> {
                try {
                    var registration=SemanticWorldRegistry.captureHome(bot,optionalString(args,"name","home",48));
                    return semanticRegistrationHandle(registration);
                } catch (IllegalArgumentException | IllegalStateException semanticFailure) {
                    throw semanticFault(semanticFailure);
                }
            }
            case "repair_home" -> {
                try {
                    semanticId=optionalString(args,"name","home",48);
                    var plan=SemanticWorldRegistry.homeRepairPlan(bot,semanticId);
                    if(plan.wrong()>0) throw new BridgeFault(409,"home_repair_v1_conflicting_cells:"+plan.wrong());
                    if(plan.missing()==0) return ()->new Snapshot("completed",1,"home_desired_cells_already_satisfied");
                    task=new BuildTask(plan.blueprint(),plan.anchor(),false,false);
                } catch (BridgeFault bridgeFault) {
                    throw bridgeFault;
                } catch (IllegalArgumentException | IllegalStateException semanticFailure) {
                    throw semanticFault(semanticFailure);
                }
            }
            case "register_farm" -> {
                try {
                    var registration=SemanticWorldRegistry.registerFarm(bot,
                            optionalString(args,"name","farm",32),
                            optionalInt(args,"radius",6,1,16),
                            optionalString(args,"crop","",64));
                    return semanticRegistrationHandle(registration);
                } catch (IllegalArgumentException | IllegalStateException semanticFailure) {
                    throw semanticFault(semanticFailure);
                }
            }
            case "tend_farm" -> {
                String farmId=optionalString(args,"name","farm",48);
                var farm=SemanticWorldRegistry.farm(bot,farmId)
                        .orElseThrow(()->new BridgeFault(404,"farm_not_registered_or_wrong_dimension_or_needs_reregister"));
                task=new FarmTask(farm.center(),farm.radius(),farm.seed(),farm.crop(),false,false);
            }
            case "mine_opportunity" -> {
                String opportunityId=string(args,"id",48);
                var opportunity=SemanticWorldRegistry.opportunity(bot,opportunityId)
                        .orElseThrow(()->new BridgeFault(404,"resource_opportunity_not_found_in_current_dimension"));
                if(!"ACTIONABLE".equals(opportunity.status()))
                    throw new BridgeFault(409,"resource_opportunity_blocked:"+opportunity.blockedReason());
                task=new KnownResourceTask(opportunity);
            }
            case "deposit" -> task=new StockpileTask(true);
            case "set_base" -> {
                BotMemoryStore.INSTANCE.of(bot.getUuid()).markPlace("base",bot.getServerWorld(),bot.getBlockPos());
                BotPersistence.INSTANCE.markDirty(server);
                return ()->new Snapshot("completed",1,"base_marker_saved_operational_state_only");
            }
            case "say" -> {
                String message=string(args,"message",1000);
                BrainCoordinator.INSTANCE.sendPanelChat(bot,"bot",message);
                server.getPlayerManager().broadcast(net.minecraft.text.Text.literal("<"+bot.getGameProfile().getName()+"> "+message),false);
                return ()->new Snapshot("completed",1,"sent_to_aibot_panel_and_global_chat");
            }
            default -> throw new BridgeFault(400,"unsupported_operation");
        }
        if(TaskManager.INSTANCE.getActive(bot).isPresent() || TaskManager.INSTANCE.hasPaused(bot)
                || bot.getActionPack().hasActiveActions())throw new BridgeFault(409,"body_busy_safety_or_legacy_work");
        if(TaskManager.INSTANCE.isUserPaused(bot))throw new BridgeFault(409,"body_user_paused");
        AIPlayerEntity executionBody=bot;
        Item expected=target; int quota=count; BlockPos destination=goal; String semantic=semanticId;
        ExternalBodyAccess.dispatch(()->{
            TaskManager.INSTANCE.assign(executionBody,task,TaskOrigin.of(TaskOrigin.Kind.LLM_TOOL,"external_dsh"));return null;
        });
        managed=task;
        return ()->{
            onThread();
            if(bot!=executionBody)return new Snapshot("outcome_unknown",task.progress(),"body_instance_replaced");
            TaskStatus s=TaskStatus.from(task);
            String state=switch(s.state()) {
                case PENDING,RUNNING -> "running";
                case PAUSED -> "paused";
                case COMPLETED -> "completed";
                case FAILED -> "failed";
                case CANCELLED -> "cancelled";
            };
            String reason=s.failureReason()==null?"":s.failureReason();
            if(state.equals("running") && TaskManager.INSTANCE.getActive(bot).orElse(null)!=task)
                return new Snapshot("outcome_unknown",s.progress(),"tracked_task_no_longer_owns_execution");
            if(state.equals("paused"))reason=TaskManager.INSTANCE.isUserPaused(bot)?"user_or_lease_pause":"safety_preempted";
            if(state.equals("completed")) {
                if(operation.equals("gather")) {
                    int actual=GatherQuotaTask.acceptedInventoryCount(bot,expected);
                    if(actual<quota)return new Snapshot("failed",s.progress(),"gather_postcondition_missing:accepted="+actual+":quota="+quota);
                    reason="inventory_family_or_exact_quota_verified:accepted="+actual;
                } else if(operation.equals("goto")) {
                    if(bot.getBlockPos().getSquaredDistance(destination)>9)return new Snapshot("failed",s.progress(),"goto_postcondition_not_within_3_blocks");
                    reason="arrival_within_3_blocks_verified";
                } else if(operation.equals("repair_home")) {
                    try {
                        var verify=SemanticWorldRegistry.homeRepairPlan(bot,semantic);
                        if(verify.missing()>0 || verify.wrong()>0)
                            return new Snapshot("failed",s.progress(),"home_repair_postcondition_missing="+verify.missing()+":wrong="+verify.wrong());
                        reason="home_missing_only_repair_verified";
                    } catch(RuntimeException verifyFailure) {
                        return new Snapshot("failed",s.progress(),"home_repair_postcondition_error:"+verifyFailure.getClass().getSimpleName());
                    }
                } else reason="upstream_task_completed_reobserve_for_user_goal_verification";
            }
            return new Snapshot(state,s.progress(),reason);
        };
    }
    @Override public void pause() {
        onThread();
        if(bot!=null && managed!=null && (managed.state()==TaskState.RUNNING || managed.state()==TaskState.PAUSED))
            ExternalBodyAccess.dispatch(()->IntentController.INSTANCE.pause(bot,IntentController.ControlOrigin.SYSTEM,"external_pause"));
    }
    @Override public void resume() {
        onThread();if(bot!=null)ExternalBodyAccess.dispatch(()->IntentController.INSTANCE.resume(bot,IntentController.ControlOrigin.SYSTEM,"external_resume"));
    }
    @Override public void cancel(String reason) {
        onThread();if(bot!=null)ExternalBodyAccess.dispatch(()->IntentController.INSTANCE.cancelAll(bot,IntentController.ControlOrigin.SYSTEM,reason));
    }
    private static Handle semanticRegistrationHandle(SemanticWorldRegistry.Registration registration) {
        return () -> {
            if (!registration.persisted().isDone()) return new Snapshot("running",0.5D,"persisting_semantic_registry");
            try {
                registration.persisted().join();
                return new Snapshot("completed",1.0D,registration.payload());
            } catch (RuntimeException persistenceFailure) {
                return new Snapshot("failed",0.5D,"semantic_registry_persistence_failed:"+persistenceFailure.getClass().getSimpleName());
            }
        };
    }
    private static BridgeFault semanticFault(RuntimeException failure) {
        String reason=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
        int status=reason.startsWith("semantic_registry_failed_closed") || reason.equals("semantic_registry_not_started") ? 503 : 400;
        return new BridgeFault(status,reason.replaceAll("[^A-Za-z0-9:._-]","_"));
    }
    private static int optionalInt(JsonObject o,String key,int fallback,int min,int max) {
        return o.has(key)?integer(o,key,min,max):fallback;
    }
    private static String optionalString(JsonObject o,String key,String fallback,int max) {
        if(!o.has(key))return fallback;
        JsonElement v=o.get(key);
        if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString())throw new BridgeFault(400,"string_required:"+key);
        String s=v.getAsString().trim();if(s.isEmpty() || s.length()>max)throw new BridgeFault(400,"invalid_string_length:"+key);return s;
    }

    private static JsonObject parse(String raw) {
        try { JsonElement value=JsonParser.parseString(raw);if(!value.isJsonObject())throw new IllegalArgumentException();return value.getAsJsonObject(); }
        catch(RuntimeException bad){throw new BridgeFault(400,"invalid_arguments_json_object");}
    }
    private static String string(JsonObject o,String key,int max) {
        JsonElement v=o.get(key);
        if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString())throw new BridgeFault(400,"string_required:"+key);
        String s=v.getAsString();if(s.isBlank() || s.length()>max)throw new BridgeFault(400,"invalid_string_length:"+key);return s;
    }
    private static int integer(JsonObject o,String key,int min,int max) {
        try {
            JsonElement v=o.get(key);if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();
            int n=v.getAsBigDecimal().intValueExact();if(n<min || n>max)throw new IllegalArgumentException();return n;
        }catch(RuntimeException bad){throw new BridgeFault(400,"integer_out_of_range:"+key);}
    }
    private static Item item(JsonObject o,String key) {
        String name=string(o,key,128);Identifier id=Identifier.tryParse(name);
        if(id==null || !Registries.ITEM.containsId(id) || name.equals("minecraft:air"))throw new BridgeFault(400,"unknown_item:"+key);
        return Registries.ITEM.get(id);
    }
}
