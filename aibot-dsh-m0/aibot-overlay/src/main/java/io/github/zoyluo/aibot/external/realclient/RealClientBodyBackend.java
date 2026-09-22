package io.github.zoyluo.aibot.external.realclient;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.external.BodyBackend;
import io.github.zoyluo.aibot.external.BridgeFault;
import io.github.zoyluo.aibot.external.BridgeJournal;
import io.github.zoyluo.aibot.external.JsonOutput;
import io.github.zoyluo.aibot.external.PhysicalExecutionDriver;
import io.github.zoyluo.aibot.external.SemanticWorldRegistry;
import io.github.zoyluo.aibot.external.TaskGraphStore;
import io.github.zoyluo.aibot.external.cognition.CognitiveSnapshot;
import io.github.zoyluo.aibot.external.cognition.EvidenceRef;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Server-authoritative view of Bob's normal remote Minecraft client. */
public final class RealClientBodyBackend implements BodyBackend {
    /** MC-RCF-1-R3 F05:hard budget for local-view opportunity entries. */
    private static final int MAX_LOCAL_OPPORTUNITIES=24;
    private final MinecraftServer server;
    private final String playerName;
    private final String logicalBodyId;
    private final RealClientServerTransport transport;
    private final RealClientOpportunityTracker tracker;
    private final BridgeJournal journal;
    private final RealClientOmnidirectionalPerception perception;
    private final boolean requireOfflineUuid;

    private ServerPlayerEntity player;
    private String preparedSession="";
    private Binding binding;
    private PhysicalExecutionDriver driver;

    public RealClientBodyBackend(
            MinecraftServer server,String playerName,String logicalBodyId,
            RealClientServerTransport transport,RealClientOpportunityTracker tracker,
            BridgeJournal journal,boolean requireOfflineUuid) {
        this.server=server;
        this.playerName=playerName;
        this.logicalBodyId=logicalBodyId;
        this.transport=transport;
        this.tracker=tracker;
        this.journal=journal;
        this.perception=new RealClientOmnidirectionalPerception();
        this.requireOfflineUuid=requireOfflineUuid;
    }

    @Override public boolean ready() {
        onThread();
        if(AIPlayerManager.INSTANCE.all().stream().anyMatch(candidate->
                playerName.equalsIgnoreCase(candidate.getGameProfile().getName()))) {
            unavailable("real_client_fake_player_authority_conflict");
            throw new IllegalStateException("real_client_fake_player_authority_conflict");
        }
        var session=transport.session().orElse(null);
        if(session==null || !session.fresh(System.currentTimeMillis()))
            return unavailable("body_session_changed");
        ServerPlayerEntity candidate=server.getPlayerManager().getPlayerList().stream()
                .filter(p->playerName.equalsIgnoreCase(p.getGameProfile().getName()))
                .findFirst().orElse(null);
        if(candidate==null || !candidate.isAlive() || candidate instanceof AIPlayerEntity)
            return unavailable("body_session_changed");
        var sensor=session.sensor();
        if(sensor==null || !candidate.getUuidAsString().equalsIgnoreCase(sensor.playerUuid()))
            return unavailable("body_session_changed");
        // v4 wire:快照必须已绑定一次 Minecraft 游戏 JOIN incarnation 才算身体就绪。
        if(sensor.gameSession().isBlank())
            return unavailable("body_session_changed");
        if(requireOfflineUuid && !candidate.getUuid().equals(offlineUuid(playerName)))
            return unavailable("body_session_changed");
        player=candidate;
        tracker.observe(player,sensor);
        // MC-RCF-1-R3 F05:lifecycle decay (TTL expiry + capacity) runs ONLY
        // here on the per-tick write path — never inside read endpoints.
        if((server.getTicks()&31)==0)
            tracker.maintain(player.getServerWorld().getTime());
        // Physical identity follows the Minecraft game-session incarnation, not the control
        // TCP epoch: a same-process game reconnect must still fence the old session.
        if(!preparedSession.equals(sensor.gameSession())) {
            preparedSession=sensor.gameSession();
            perception.clear();
            binding=new Binding(
                    logicalBodyId,"real_client",player.getUuidAsString(),preparedSession);
            driver=new RealClientExecutionDriver(
                    server,transport,tracker,()->this.player);
        }
        perception.tick(player,sensor.gameSession());
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

    @Override public Set<String> supportedOperations() {
        return RealClientExecutionDriver.OPERATIONS;
    }

    @Override public String observeJson() {
        onThread();
        ServerPlayerEntity current=requirePlayer();
        Binding currentBinding=binding();
        Map<String,Object> position=Map.of(
                "x",current.getX(),"y",current.getY(),"z",current.getZ());
        Map<String,Object> out=new LinkedHashMap<>();
        out.putAll(currentBinding.wire());
        out.put("name",current.getGameProfile().getName());
        out.put("minecraft_profile_uuid",current.getUuidAsString());
        out.put("profile","strict_survival_real_client");
        out.put("health",current.getHealth());
        out.put("food",current.getHungerManager().getFoodLevel());
        var session=transport.session().orElse(null);
        out.put("client_window_mode",session==null?"unknown":session.windowMode());
        out.put("client_mod_jar_sha256",
                session==null?"unknown":session.modJarSha256());
        out.put("control_session_epoch",
                session==null?"unknown":session.sessionEpoch());
        out.put("dimension",current.getServerWorld().getRegistryKey().getValue().toString());
        out.put("position",position);
        out.put("inventory",inventory(current));
        out.put("sensor","client_crosshair_server_validated");
        out.put("screen",screenWire(session==null?null:session.screen()));
        out.put("perception",perception.snapshot(current).summaryWire());
        out.put("supported_operations",supportedOperations().stream().sorted().toList());
        out.put("safety_active",false);
        out.put("user_paused",false);
        out.put("current_task","real_client_remote_actuator");
        out.put("paused_depth",0);
        return JsonOutput.encode(out);
    }

    @Override public CognitiveSnapshot.Snapshot cognitiveSnapshot(BridgeJournal ignored) {
        onThread();
        ServerPlayerEntity current=requirePlayer();
        var screen=transport.session()
                .map(RealClientServerTransport.SessionSnapshot::screen).orElse(null);
        return RealClientCognitiveViewBuilder.build(
                logicalBodyId,current,tracker,journal,screen,
                perception.snapshot(current));
    }

    @Override public String inspectLocalJson(int radius,String detail) {
        onThread();
        ServerPlayerEntity current=requirePlayer();
        // MC-RCF-1-R2 R02:inspect-local 是纯读接口——不得调用
        // tracker.observe 补机会(读一次顺带触发持久出生是旁路)。
        // 传感器帧→机会出生只由服务端 tick 的 ready() 后台链驱动。
        if(radius<1 || radius>16)throw new BridgeFault(400,"radius_out_of_range_1_16");
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("schema","mc.local_view.v1");
        out.put("detail",detail==null?"summary":detail);
        out.put("radius",radius);
        out.put("body_position",Map.of(
                "x",current.getBlockX(),"y",current.getBlockY(),"z",current.getBlockZ()));
        // MC-RCF-1-R3 F05:local opportunities are HISTORY-scoped memory with
        // provenance — never a live dimension dump. Apply the requested
        // radius, a hard entry budget and stable (distance,id) ordering; the
        // truncated flag covers only in-radius truncation so it cannot leak
        // the existence of out-of-sight remembered targets.
        long gameTime=player.getServerWorld().getTime();
        int bx=current.getBlockX(),by=current.getBlockY(),bz=current.getBlockZ();
        record LocalOpp(double dist2,String id,String block,int x,int y,int z,long age) {}
        List<LocalOpp> localOpps=tracker.opportunities(current).stream()
                .map(o->{
                    int dx=o.pos().getX()-bx,dy=o.pos().getY()-by,dz=o.pos().getZ()-bz;
                    double dist2=dx*(double)dx+dy*(double)dy+dz*(double)dz;
                    long age=Math.max(0L,gameTime-o.lastSeenGameTime());
                    return new LocalOpp(dist2,o.id(),o.blockId(),
                            o.pos().getX(),o.pos().getY(),o.pos().getZ(),age);
                })
                .filter(o->Math.abs(o.x()-bx)<=radius&&Math.abs(o.y()-by)<=radius
                        &&Math.abs(o.z()-bz)<=radius)
                .sorted(java.util.Comparator.<LocalOpp>comparingDouble(o->o.dist2())
                        .thenComparing(o->o.id()))
                .limit(MAX_LOCAL_OPPORTUNITIES+1)
                .toList();
        boolean oppsTruncated=localOpps.size()>MAX_LOCAL_OPPORTUNITIES;
        if(oppsTruncated)
            localOpps=localOpps.subList(0,MAX_LOCAL_OPPORTUNITIES);
        List<Map<String,Object>> oppWire=localOpps.stream()
                .map(o->{
                    Map<String,Object> m=new LinkedHashMap<>();
                    m.put("object_id",o.id());
                    m.put("block",o.block());
                    m.put("x",o.x());
                    m.put("y",o.y());
                    m.put("z",o.z());
                    m.put("age_ticks",o.age());
                    m.put("freshness",o.age()<=40?"LIVE":o.age()<=2400?"RECENT":"STALE");
                    m.put("sensor","client_crosshair_server_validated");
                    m.put("source","remembered_observation");
                    return m;
                }).toList();
        Map<String,Object> oppOut=new LinkedHashMap<>();
        oppOut.put("entries",oppWire);
        if(oppsTruncated)
            oppOut.put("truncated",true);
        oppOut.put("note","bounded_local_history_view;entries_within_radius_only;"
                +"out_of_radius_memory_is_not_enumerated");
        out.put("opportunities",oppOut);
        Map<String,Object> local=perception.snapshot(current)
                .localWire(radius,detail==null?"summary":detail);
        out.put("perception",local.get("perception"));
        out.put("blocks",local.get("blocks"));
        out.put("entities",local.get("entities"));
        out.put("note",
                "omnidirectional_semantic_awareness_is_read_only;"
                        +"precise_mutation_requires_directional_crosshair");
        return JsonOutput.encode(out);
    }

    @Override public String materializeEvidence(String ref,String detail,long gameTime) {
        onThread();
        ServerPlayerEntity current=requirePlayer();
        EvidenceRef.Parsed parsed=EvidenceRef.parse(ref);
        if(!SemanticWorldRegistry.worldId().equals(parsed.worldId())
                || !current.getServerWorld().getRegistryKey().getValue().toString()
                .equals(parsed.dimension())
                || !"opportunity".equals(parsed.kind()))
            return null;
        return tracker.opportunity(current,parsed.objectId())
                .map(opportunity->tracker.materialize(opportunity,gameTime).toString())
                .orElse(null);
    }

    @Override public long serverTick() {
        onThread();
        return server.getTicks();
    }

    @Override public GraphPostconditionResult verifyGraphPostcondition(
            TaskGraphStore.Postcondition postcondition) {
        onThread();
        ServerPlayerEntity current=player;
        if(current==null)return GraphPostconditionResult.unknown("body_unavailable");
        TaskGraphStore.SpatialRef ref=postcondition.subject();
        if(!SemanticWorldRegistry.worldId().equals(ref.worldId()))
            return GraphPostconditionResult.unknown("world_scope_mismatch");
        String dimension=current.getServerWorld().getRegistryKey().getValue().toString();
        if(!dimension.equals(ref.dimensionId()))
            return GraphPostconditionResult.unknown("dimension_not_current");
        if("OPPORTUNITY_RESOLVED".equals(postcondition.kind())) {
            if(tracker.opportunity(current,ref.objectId()).isPresent())
                return GraphPostconditionResult.unsatisfied("opportunity_still_actionable");
            return GraphPostconditionResult.unknown(
                    "opportunity_absent_without_durable_resolution_receipt");
        }
        return GraphPostconditionResult.unknown("unsupported_graph_postcondition");
    }

    @Override public Handle start(String operation,String argumentsJson) {
        return start("",operation,argumentsJson);
    }

    @Override public Handle start(String executionId,String operation,String argumentsJson) {
        onThread();
        if(driver==null)throw new BridgeFault(409,"body_unavailable");
        return driver.start(new PhysicalExecutionDriver.Request(
                executionId,operation,argumentsJson));
    }

    @Override public void pause() {
        onThread();
        if(driver!=null)driver.pause();
    }

    @Override public void resume() {
        onThread();
        if(driver!=null)driver.resume();
    }

    @Override public void cancel(String reason) {
        onThread();
        if(driver!=null)driver.cancel(reason);
    }

    private boolean unavailable(String reason) {
        PhysicalExecutionDriver previous=driver;
        driver=null;
        preparedSession="";
        player=null;
        perception.clear();
        if(previous!=null) {
            try { previous.cancel(reason); } catch(RuntimeException ignored) {}
        }
        return false;
    }

    private ServerPlayerEntity requirePlayer() {
        if(player==null || !player.isAlive())throw new BridgeFault(409,"body_unavailable");
        return player;
    }

    private void onThread() {
        if(!server.isOnThread())throw new IllegalStateException("minecraft_access_off_server_thread");
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(
                ("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String,Integer> inventory(ServerPlayerEntity player) {
        Map<String,Integer> counts=new TreeMap<>();
        for(int slot=0;slot<player.getInventory().size();slot++) {
            ItemStack stack=player.getInventory().getStack(slot);
            if(!stack.isEmpty())counts.merge(
                    Registries.ITEM.getId(stack.getItem()).toString(),
                    stack.getCount(),Integer::sum);
        }
        return counts;
    }

    private static Map<String,Object> screenWire(
            RealClientServerTransport.ScreenSnapshot screen) {
        if(screen==null || !screen.present())
            return Map.of("present",false,
                    // No open handler ⇒ cursor stack cannot exist (vanilla
                    // returns it on close). Vacuously empty by construction.
                    "cursor_item","minecraft:air",
                    "cursor_count",0);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("game_session",screen.gameSession());
        out.put("screen_seq",screen.screenSeq());
        out.put("cursor_item",screen.cursorItem());
        out.put("cursor_count",screen.cursorCount());
        out.put("screen_epoch",screen.screenEpoch());
        out.put("adapter_id",screen.adapterId());
        out.put("screen_class",screen.screenClass());
        out.put("handler_class",screen.handlerClass());
        out.put("title",Map.of(
                "text",screen.title(),
                "origin",screen.titleOrigin(),
                "trust",screen.titleTrust()));
        out.put("sync_id",screen.syncId());
        out.put("capabilities",screen.capabilities());
        out.put("widget_count",screen.widgets().size());
        out.put("storage_item_count",screen.storageItems().size());
        out.put("slot_count",screen.slotCount());
        out.put("truncated",screen.truncated());
        return out;
    }

    /** Avoids claiming hidden scans in the MVP response while keeping the wrapper shape stable. */
    private static final class ListSupport {
        static java.util.List<Object> emptyIfUnsupported(String detail,String field) {
            return java.util.List.of();
        }
    }
}
