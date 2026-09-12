package io.github.zoyluo.aibot.external;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.persist.BotPersistence;
import io.github.zoyluo.aibot.runtime.IntentController;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.*;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Current physical driver: server-resident AIBot fake player and upstream TaskManager.
 *
 * <p>All task construction, assignment, pause/resume/cancel and bounded physical postcondition
 * checks live here. MinecraftBodyBackend owns observation/binding only.</p>
 */
public final class ServerFakePlayerExecutionDriver implements PhysicalExecutionDriver {
    private final MinecraftServer server;
    private final AIPlayerEntity bot;
    private final Supplier<AIPlayerEntity> currentBody;
    private Task managed;

    public ServerFakePlayerExecutionDriver(MinecraftServer server,AIPlayerEntity bot,
                                           Supplier<AIPlayerEntity> currentBody) {
        this.server=Objects.requireNonNull(server);
        this.bot=Objects.requireNonNull(bot);
        this.currentBody=Objects.requireNonNull(currentBody);
        onThread();
        // Fence restored legacy/internal work before the external bridge admits a command.
        ExternalBodyAccess.dispatch(()->{
            IntentController.INSTANCE.cancelAll(bot,IntentController.ControlOrigin.SYSTEM,"external_mode_enter");
            BrainCoordinator.INSTANCE.reset(bot);
            return null;
        });
    }

    private void onThread() {
        if(!server.isOnThread())throw new IllegalStateException("minecraft_access_off_server_thread");
    }

    @Override public BodyBackend.Handle start(Request request) {
        onThread();
        String executionId=request.executionId();
        String operation=request.operation();
        String raw=request.argumentsJson();
        if(!bot.isAlive())throw new BridgeFault(409,"body_unavailable");
        if(!"strict_survival".equals(AIBotConfig.get().profile().configValue()))
            throw new BridgeFault(403,"strict_survival_required");

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

        Task task;
        Item target=null;
        int count=0;
        BlockPos goal=null;
        String semanticId=null;

        switch(operation) {
            case "goto" -> {
                if(!args.has("allow_terrain_changes") || !args.get("allow_terrain_changes").isJsonPrimitive()
                        || !args.getAsJsonPrimitive("allow_terrain_changes").isBoolean()
                        || !args.get("allow_terrain_changes").getAsBoolean())
                    throw new BridgeFault(400,"goto_can_dig_explicit_allow_terrain_changes_required");
                goal=new BlockPos(
                        integer(args,"x",-29999984,29999984),
                        integer(args,"y",bot.getServerWorld().getBottomY(),
                                bot.getServerWorld().getBottomY()+bot.getServerWorld().getHeight()-1),
                        integer(args,"z",-29999984,29999984));
                if(bot.getBlockPos().getSquaredDistance(goal)>128*128)
                    throw new BridgeFault(400,"goto_distance_limit_128");
                task=new MoveTask(bot,goal);
            }
            case "gather" -> {
                target=item(args,"item");
                count=integer(args,"count",1,256);
                task=new GatherQuotaTask(target,count,executionId);
            }
            case "craft" -> {
                target=item(args,"item");
                count=integer(args,"count",1,64);
                task=new CraftTask(target,count);
            }
            case "smelt" -> {
                target=item(args,"output_item");
                count=integer(args,"count",1,64);
                task=new SmeltTask(item(args,"input_item"),target,count);
            }
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
                    var registration=SemanticWorldRegistry.captureHome(
                            bot,optionalString(args,"name","home",48));
                    return semanticRegistrationHandle(registration);
                } catch (IllegalArgumentException | IllegalStateException semanticFailure) {
                    throw semanticFault(semanticFailure);
                }
            }
            case "repair_home" -> {
                try {
                    semanticId=optionalString(args,"name","home",48);
                    var plan=SemanticWorldRegistry.homeRepairPlan(bot,semanticId);
                    if(plan.wrong()>0)
                        throw new BridgeFault(409,"home_repair_v1_conflicting_cells:"+plan.wrong());
                    if(plan.missing()==0)
                        return ()->new BodyBackend.Snapshot(
                                "completed",1,"home_desired_cells_already_satisfied");
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
                        .orElseThrow(()->new BridgeFault(
                                404,"farm_not_registered_or_wrong_dimension_or_needs_reregister"));
                task=new FarmTask(farm.center(),farm.radius(),farm.seed(),farm.crop(),false,false);
            }
            case "mine_opportunity" -> {
                String opportunityId=string(args,"id",48);
                var opportunity=SemanticWorldRegistry.opportunity(bot,opportunityId)
                        .orElseThrow(()->new BridgeFault(
                                404,"resource_opportunity_not_found_in_current_dimension"));
                if("MINED_PENDING_PICKUP".equals(opportunity.status())) {
                    task=new KnownResourceTask(opportunity,true);
                } else if("UNREACHABLE".equals(opportunity.status())) {
                    if(!SemanticWorldRegistry.opportunityRevalidationReady(bot,opportunity))
                        throw new BridgeFault(409,"resource_opportunity_unreachable:"
                                +opportunity.blockedReason()
                                +":revalidation_requires_geometry_change_or_cooldown");
                    opportunity=SemanticWorldRegistry.reactivateOpportunity(bot,opportunityId)
                            .orElseThrow(()->new BridgeFault(
                                    409,"resource_opportunity_unreachable:reactivation_failed"));
                    task=new KnownResourceTask(opportunity,false);
                } else if("ACTIONABLE".equals(opportunity.status())) {
                    task=new KnownResourceTask(opportunity,false);
                } else {
                    throw new BridgeFault(
                            409,"resource_opportunity_blocked:"+opportunity.blockedReason());
                }
            }
            case "deposit" -> task=new StockpileTask(true);
            case "set_base" -> {
                BotMemoryStore.INSTANCE.of(bot.getUuid())
                        .markPlace("base",bot.getServerWorld(),bot.getBlockPos());
                BotPersistence.INSTANCE.markDirty(server);
                return ()->new BodyBackend.Snapshot(
                        "completed",1,"base_marker_saved_operational_state_only");
            }
            case "say" -> {
                String message=string(args,"message",1000);
                BrainCoordinator.INSTANCE.sendPanelChat(bot,"bot",message);
                server.getPlayerManager().broadcast(
                        net.minecraft.text.Text.literal(
                                "<"+bot.getGameProfile().getName()+"> "+message),false);
                return ()->new BodyBackend.Snapshot(
                        "completed",1,"sent_to_aibot_panel_and_global_chat");
            }
            default -> throw new BridgeFault(400,"unsupported_operation");
        }

        if(TaskManager.INSTANCE.getActive(bot).isPresent()
                || TaskManager.INSTANCE.hasPaused(bot)
                || bot.getActionPack().hasActiveActions())
            throw new BridgeFault(409,"body_busy_safety_or_legacy_work");
        if(TaskManager.INSTANCE.isUserPaused(bot))
            throw new BridgeFault(409,"body_user_paused");

        AIPlayerEntity executionBody=bot;
        Item expected=target;
        int quota=count;
        BlockPos destination=goal;
        String semantic=semanticId;
        String executionOrigin=executionId.isBlank()
                ?"external_dsh":"external_dsh:"+executionId;
        ExternalBodyAccess.dispatch(()->{
            TaskManager.INSTANCE.assign(
                    executionBody,task,
                    TaskOrigin.of(TaskOrigin.Kind.LLM_TOOL,executionOrigin));
            return null;
        });
        managed=task;

        return ()->{
            onThread();
            if(currentBody.get()!=executionBody)
                return new BodyBackend.Snapshot(
                        "outcome_unknown",task.progress(),"body_instance_replaced");
            TaskStatus status=TaskStatus.from(task);
            String state=switch(status.state()) {
                case PENDING,RUNNING -> "running";
                case PAUSED -> "paused";
                case COMPLETED -> "completed";
                case FAILED -> "failed";
                case CANCELLED -> "cancelled";
            };
            String reason=status.failureReason()==null?"":status.failureReason();
            if(state.equals("running")
                    && TaskManager.INSTANCE.getActive(executionBody).orElse(null)!=task)
                return new BodyBackend.Snapshot(
                        "outcome_unknown",status.progress(),
                        "tracked_task_no_longer_owns_execution");
            if(state.equals("paused"))
                reason=TaskManager.INSTANCE.isUserPaused(executionBody)
                        ?"user_or_lease_pause":"safety_preempted";
            if(state.equals("completed")) {
                if(operation.equals("gather")) {
                    int actual=GatherQuotaTask.acceptedInventoryCount(executionBody,expected);
                    if(actual<quota)
                        return new BodyBackend.Snapshot(
                                "failed",status.progress(),
                                "gather_postcondition_missing:accepted="+actual+":quota="+quota);
                    reason="inventory_family_or_exact_quota_verified:accepted="+actual;
                } else if(operation.equals("goto")) {
                    if(executionBody.getBlockPos().getSquaredDistance(destination)>9)
                        return new BodyBackend.Snapshot(
                                "failed",status.progress(),
                                "goto_postcondition_not_within_3_blocks");
                    reason="arrival_within_3_blocks_verified";
                } else if(operation.equals("repair_home")) {
                    try {
                        var verify=SemanticWorldRegistry.homeRepairPlan(executionBody,semantic);
                        if(verify.missing()>0 || verify.wrong()>0)
                            return new BodyBackend.Snapshot(
                                    "failed",status.progress(),
                                    "home_repair_postcondition_missing="+verify.missing()
                                            +":wrong="+verify.wrong());
                        reason="home_missing_only_repair_verified";
                    } catch(RuntimeException verifyFailure) {
                        return new BodyBackend.Snapshot(
                                "failed",status.progress(),
                                "home_repair_postcondition_error:"
                                        +verifyFailure.getClass().getSimpleName());
                    }
                } else {
                    reason="upstream_task_completed_reobserve_for_user_goal_verification";
                }
            }
            return new BodyBackend.Snapshot(state,status.progress(),reason);
        };
    }

    @Override public void pause() {
        onThread();
        if(managed!=null
                && (managed.state()==TaskState.RUNNING || managed.state()==TaskState.PAUSED))
            ExternalBodyAccess.dispatch(()->IntentController.INSTANCE.pause(
                    bot,IntentController.ControlOrigin.SYSTEM,"external_pause"));
    }

    @Override public void resume() {
        onThread();
        ExternalBodyAccess.dispatch(()->IntentController.INSTANCE.resume(
                bot,IntentController.ControlOrigin.SYSTEM,"external_resume"));
    }

    @Override public void cancel(String reason) {
        onThread();
        ExternalBodyAccess.dispatch(()->IntentController.INSTANCE.cancelAll(
                bot,IntentController.ControlOrigin.SYSTEM,reason));
    }

    private static BodyBackend.Handle semanticRegistrationHandle(
            SemanticWorldRegistry.Registration registration) {
        return ()->{
            if(!registration.persisted().isDone())
                return new BodyBackend.Snapshot(
                        "running",0.5D,"persisting_semantic_registry");
            try {
                registration.persisted().join();
                return new BodyBackend.Snapshot(
                        "completed",1.0D,registration.payload());
            } catch(RuntimeException persistenceFailure) {
                return new BodyBackend.Snapshot(
                        "failed",0.5D,
                        "semantic_registry_persistence_failed:"
                                +persistenceFailure.getClass().getSimpleName());
            }
        };
    }

    private static BridgeFault semanticFault(RuntimeException failure) {
        String reason=failure.getMessage()==null
                ?failure.getClass().getSimpleName():failure.getMessage();
        int status=reason.startsWith("semantic_registry_failed_closed")
                || reason.equals("semantic_registry_not_started") ? 503 : 400;
        return new BridgeFault(
                status,reason.replaceAll("[^A-Za-z0-9:._-]","_"));
    }

    private static int optionalInt(
            JsonObject object,String key,int fallback,int min,int max) {
        return object.has(key)?integer(object,key,min,max):fallback;
    }

    private static String optionalString(
            JsonObject object,String key,String fallback,int max) {
        if(!object.has(key))return fallback;
        JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString())
            throw new BridgeFault(400,"string_required:"+key);
        String result=value.getAsString().trim();
        if(result.isEmpty() || result.length()>max)
            throw new BridgeFault(400,"invalid_string_length:"+key);
        return result;
    }

    private static JsonObject parse(String raw) {
        try {
            JsonElement value=JsonParser.parseString(raw);
            if(!value.isJsonObject())throw new IllegalArgumentException();
            return value.getAsJsonObject();
        } catch(RuntimeException bad) {
            throw new BridgeFault(400,"invalid_arguments_json_object");
        }
    }

    private static String string(JsonObject object,String key,int max) {
        JsonElement value=object.get(key);
        if(value==null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString())
            throw new BridgeFault(400,"string_required:"+key);
        String result=value.getAsString();
        if(result.isBlank() || result.length()>max)
            throw new BridgeFault(400,"invalid_string_length:"+key);
        return result;
    }

    private static int integer(JsonObject object,String key,int min,int max) {
        try {
            JsonElement value=object.get(key);
            if(value==null || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException();
            int result=value.getAsBigDecimal().intValueExact();
            if(result<min || result>max)throw new IllegalArgumentException();
            return result;
        } catch(RuntimeException bad) {
            throw new BridgeFault(400,"integer_out_of_range:"+key);
        }
    }

    private static Item item(JsonObject object,String key) {
        String name=string(object,key,128);
        Identifier id=Identifier.tryParse(name);
        if(id==null || !Registries.ITEM.containsId(id)
                || name.equals("minecraft:air"))
            throw new BridgeFault(400,"unknown_item:"+key);
        return Registries.ITEM.get(id);
    }
}
