package io.github.zoyluo.aibot.external.realclient;

import io.github.zoyluo.aibot.external.BridgeFault;
import io.github.zoyluo.aibot.external.BridgeJournal;
import io.github.zoyluo.aibot.external.cognition.CanonicalJson;
import io.github.zoyluo.aibot.external.cognition.CognitiveSnapshot;
import io.github.zoyluo.aibot.external.cognition.EvidenceRef;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Compact read model for a normal ServerPlayerEntity controlled by the real-client backend. */
public final class RealClientCognitiveViewBuilder {
    private RealClientCognitiveViewBuilder() {}

    public static CognitiveSnapshot.Snapshot build(
            String bodyId,ServerPlayerEntity player,RealClientOpportunityTracker tracker,
            BridgeJournal journal,RealClientServerTransport.ScreenSnapshot screen,
            RealClientOmnidirectionalPerception.Snapshot perception) {
        String worldId=io.github.zoyluo.aibot.external.SemanticWorldRegistry.worldId();
        String dimension=player.getServerWorld().getRegistryKey().getValue().toString();
        long gameTime=player.getServerWorld().getTime();
        Map<String,Object> scene=CanonicalJson.object();
        scene.put("world",Map.of("world_id",worldId,"dimension",dimension));
        scene.put("self",self(bodyId,player));
        scene.put("environment",environment(player,perception));
        scene.put("ui",screen(screen));

        List<Map<String,Object>> cards=new ArrayList<>();
        final boolean[] opportunities_truncated={false};
        final long[] opportunities_omitted={0L};
        Map<String,CognitiveSnapshot.EvidenceDescriptor> index=new LinkedHashMap<>();
        for(RealClientOpportunityTracker.Opportunity opportunity:tracker.opportunities(player)) {
            String ref=EvidenceRef.format(
                    worldId,dimension,"opportunity",opportunity.id());
            long age=Math.max(0L,gameTime-opportunity.lastSeenGameTime());
            Map<String,Object> summary=CanonicalJson.object();
            summary.put("block",opportunity.blockId());
            summary.put("status","ACTIONABLE");
            summary.put("required_tool",opportunity.requiredTool());
            summary.put("distance_blocks",Math.round(
                    player.getPos().distanceTo(opportunity.pos().toCenterPos())));
            Map<String,Object> card=CanonicalJson.object();
            card.put("evidence_ref",ref);
            card.put("kind","opportunity");
            card.put("object_id",opportunity.id());
            card.put("knowledge","LAST_KNOWN");
            card.put("freshness",age<=40?"LIVE":age<=2400?"RECENT":"STALE");
            card.put("summary",summary);
            cards.add(card);
            index.put(ref,new CognitiveSnapshot.EvidenceDescriptor(
                    ref,"opportunity",opportunity.id(),"resource_opportunity"));
        }
        cards.sort(Comparator.comparing(card->String.valueOf(card.get("object_id"))));
        // 认知视图预算:机会卡片按距离截断(泥土机会放宽后全列会超 VIEW_MAX_BYTES 硬限)。
        if(cards.size()>40) {
            java.util.Map<String,Object> player0=CanonicalJson.object();
            cards.sort((a,b)->Long.compare(
                    ((Number)((Map<String,Object>)a.get("summary")).get("distance_blocks")).longValue(),
                    ((Number)((Map<String,Object>)b.get("summary")).get("distance_blocks")).longValue()));
            long omitted=cards.size()-40L;
            while(cards.size()>40)cards.remove(cards.size()-1);
            opportunities_truncated[0]=true;
            opportunities_omitted[0]=omitted;
        }
        Map<String,Object> opportunities=CanonicalJson.object();
        opportunities.put("items",cards);
        opportunities.put("total",(long)cards.size());
        opportunities.put("truncated",opportunities_truncated[0]);
        opportunities.put("omitted_count",opportunities_omitted[0]);
        Map<String,Object> empty=Map.of(
                "items",List.of(),"total",0L,"truncated",false,"omitted_count",0L);
        Map<String,Object> semantic=CanonicalJson.object();
        semantic.put("structures",empty);
        semantic.put("farms",empty);
        semantic.put("resource_opportunities",opportunities);
        scene.put("semantic_objects",semantic);
        Map<String,Object> execution=CanonicalJson.object();
        execution.put("state","BACKEND_MANAGED");
        execution.put("current_task",null);
        execution.put("progress_bucket",0L);
        execution.put("safety_active",false);
        execution.put("user_paused",false);
        execution.put("paused_depth",0L);
        scene.put("execution",execution);
        scene.put("recent_significant_events",recentEvents(journal));
        scene.put("uncertainty",uncertainty(
                worldId,dimension,perception));
        String sceneJson=CanonicalJson.write(scene);
        if(sceneJson.getBytes(StandardCharsets.UTF_8).length>CognitiveSnapshot.VIEW_MAX_BYTES)
            throw new BridgeFault(500,"cognitive_view_exceeds_hard_limit");
        return new CognitiveSnapshot.Snapshot(sceneJson,sha256(sceneJson),gameTime,index);
    }

    private static Map<String,Object> self(String bodyId,ServerPlayerEntity player) {
        Map<String,Long> inventory=new TreeMap<>();
        for(int slot=0;slot<player.getInventory().size();slot++) {
            ItemStack stack=player.getInventory().getStack(slot);
            if(!stack.isEmpty())inventory.merge(
                    Registries.ITEM.getId(stack.getItem()).toString(),
                    (long)stack.getCount(),Long::sum);
        }
        Map<String,Object> position=Map.of(
                "x",(long)player.getBlockX(),
                "y",(long)player.getBlockY(),
                "z",(long)player.getBlockZ());
        Map<String,Object> self=CanonicalJson.object();
        self.put("body_id",bodyId);
        self.put("name",player.getGameProfile().getName());
        self.put("minecraft_profile_uuid",player.getUuidAsString());
        self.put("block_position",position);
        self.put("health",(double)player.getHealth());
        self.put("food",(long)player.getHungerManager().getFoodLevel());
        self.put("inventory",inventory);
        self.put("facing",Map.of(
                "body_yaw_degrees",(double)player.getYaw(),
                "head_yaw_degrees",(double)player.getHeadYaw(),
                "pitch_degrees",(double)player.getPitch(),
                "precise_interaction_requires_crosshair",true));
        return self;
    }

    private static Map<String,Object> environment(
            ServerPlayerEntity player,
            RealClientOmnidirectionalPerception.Snapshot perception) {
        long day=player.getServerWorld().getTimeOfDay()%24000L;
        Map<String,Object> environment=CanonicalJson.object();
        environment.put("day_phase",day<1000?"MORNING":day<11000?"DAY":day<13000?"DUSK":day<23000?"NIGHT":"DAWN");
        environment.put("weather",player.getServerWorld().isThundering()
                ?"THUNDER":player.getServerWorld().isRaining()?"RAIN":"CLEAR");
        environment.put("local_light",(long)player.getServerWorld().getLightLevel(player.getBlockPos()));
        environment.put("nearby",perception.summaryWire());
        environment.put("spatial_awareness",perception.sceneWire());
        return environment;
    }

    private static List<Map<String,Object>> uncertainty(
            String worldId,String dimension,
            RealClientOmnidirectionalPerception.Snapshot perception) {
        List<Map<String,Object>> uncertainty=new ArrayList<>();
        String scope="mc://"+worldId+"/"+dimension;
        if(perception.entityTruncated())uncertainty.add(Map.of(
                "scope_ref",scope,
                "field","spatial_awareness.entities",
                "reason","bounded_entity_perception_truncated"));
        if(perception.blockTruncated())uncertainty.add(Map.of(
                "scope_ref",scope,
                "field","spatial_awareness.visible_blocks",
                "reason","bounded_block_perception_truncated"));
        if(perception.config().mode()
                ==RealClientOmnidirectionalPerception.Mode.STRICT_PLAYER_FOV)
            uncertainty.add(Map.of(
                    "scope_ref",scope,
                    "field","spatial_awareness.coverage_degrees",
                    "reason","strict_player_fov_mode"));
        return uncertainty;
    }

    private static Map<String,Object> screen(
            RealClientServerTransport.ScreenSnapshot screen) {
        if(screen==null || !screen.present())
            return Map.of("present",false);
        List<Map<String,Object>> slots=new ArrayList<>();
        for(var slot:screen.slots()) {
            if("minecraft:air".equals(slot.itemId())
                    ||slot.count()==0)continue;
            Map<String,Object> item=CanonicalJson.object();
            item.put("slot_id",(long)slot.slotId());
            item.put("inventory_kind",slot.inventoryKind());
            item.put("inventory_index",(long)slot.inventoryIndex());
            item.put("item",slot.itemId());
            item.put("count",(long)slot.count());
            slots.add(item);
            if(slots.size()>=64)break;
        }
        List<Map<String,Object>> widgets=new ArrayList<>();
        for(var widget:screen.widgets()) {
            Map<String,Object> item=CanonicalJson.object();
            item.put("widget_index",(long)widget.widgetIndex());
            item.put("widget_class",widget.widgetClass());
            item.put("message",Map.of(
                    "text",widget.message(),
                    "origin",widget.messageOrigin(),
                    "trust",widget.messageTrust()));
            item.put("active",widget.active());
            item.put("visible",widget.visible());
            widgets.add(item);
            if(widgets.size()>=32)break;
        }
        List<Map<String,Object>> storageItems=new ArrayList<>();
        for(var stored:screen.storageItems()) {
            Map<String,Object> item=CanonicalJson.object();
            item.put("item",stored.itemId());
            item.put("count",stored.count());
            item.put("origin","mod_state");
            item.put("trust","structured_data");
            storageItems.add(item);
            if(storageItems.size()>=128)break;
        }
        Map<String,Object> ui=CanonicalJson.object();
        ui.put("present",true);
        ui.put("game_session",screen.gameSession());
        ui.put("screen_seq",screen.screenSeq());
        ui.put("screen_epoch",screen.screenEpoch());
        ui.put("adapter_id",screen.adapterId());
        ui.put("capabilities",screen.capabilities());
        ui.put("screen_class",screen.screenClass());
        ui.put("handler_class",screen.handlerClass());
        ui.put("title",Map.of(
                "text",screen.title(),
                "origin",screen.titleOrigin(),
                "trust",screen.titleTrust()));
        ui.put("sync_id",(long)screen.syncId());
        ui.put("widgets",widgets);
        ui.put("slots",slots);
        ui.put("storage_items",storageItems);
        ui.put("slot_count",(long)screen.slotCount());
        ui.put("truncated",screen.truncated()
                ||screen.slots().size()>64
                ||screen.widgets().size()>32
                ||screen.storageItems().size()>128);
        return ui;
    }

    private static Map<String,Object> recentEvents(BridgeJournal journal) {
        List<Map<String,Object>> significant=new ArrayList<>();
        for(BridgeJournal.Frame frame:journal.replay()) {
            String kind=frame.fields().getOrDefault("kind","");
            boolean keep=switch(kind) {
                case "death","damage","respawn","survival_alert",
                        "resource_opportunity_actionable","resource_opportunity_stale",
                        "control_acquired","control_lost","body_changed","body_session_changed" -> true;
                case "execution" -> List.of(
                        "completed","failed","cancelled","outcome_unknown")
                        .contains(frame.fields().getOrDefault("state",""));
                default -> false;
            };
            if(!keep)continue;
            Map<String,Object> item=CanonicalJson.object();
            item.put("kind",kind);
            item.put("sequence",frame.sequence());
            String executionId=frame.fields().get("execution_id");
            if(executionId!=null && !executionId.isBlank())
                item.put("execution_id",executionId);
            significant.add(item);
        }
        int from=Math.max(0,significant.size()-12);
        List<Map<String,Object>> items=new ArrayList<>(significant.subList(from,significant.size()));
        return Map.of(
                "availability","AVAILABLE",
                "items",items,
                "truncated",from>0);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
