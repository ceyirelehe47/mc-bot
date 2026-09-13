package io.github.zoyluo.aibot.client.realclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.UUID;

/** Bounded read-only native Screen/ScreenHandler snapshot publisher. */
final class RealClientScreenController {
    private static final int MAX_SLOTS=128;
    private static final int RESEND_TICKS=40;

    record CurrentScreen(
            long screenSeq,String screenEpoch,boolean present,
            String adapterId,int syncId,String screenClass,
            String handlerClass) {
        static CurrentScreen absent() {
            return new CurrentScreen(
                    -1L,"",false,"",-1,"","");
        }
    }

    private record Built(
            JsonObject message,CurrentScreen current,String openKey) {}

    private final RealClientClientTransport transport;
    private long screenSeq=-1;
    private String lastFingerprint="";
    private String lastOpenKey="absent";
    private String screenEpoch="";
    private int resendTicks;
    private CurrentScreen current=CurrentScreen.absent();

    RealClientScreenController(
            RealClientClientTransport transport) {
        this.transport=transport;
    }

    CurrentScreen current() {
        return current;
    }

    void gameSessionStarted() {
        screenSeq=-1;
        lastFingerprint="";
        lastOpenKey="absent";
        screenEpoch="";
        resendTicks=0;
        current=CurrentScreen.absent();
    }

    void disconnected() {
        gameSessionStarted();
    }

    void tick(MinecraftClient client) {
        // No Minecraft-state message exists before JOIN. This prevents the v3 pre-JOIN
        // present=false reconnect storm and keeps Screen state tied to one game incarnation.
        if(!transport.connected() || !transport.gameSessionBound())return;
        Built built=build(client);
        String fingerprint=built.message().toString();
        if(fingerprint.equals(lastFingerprint)
                && ++resendTicks<RESEND_TICKS) {
            current=built.current();
            return;
        }
        lastFingerprint=fingerprint;
        resendTicks=0;
        JsonObject snapshot=built.message();
        long nextSeq=++screenSeq;
        snapshot.addProperty("type","screen");
        snapshot.addProperty("screen_seq",nextSeq);
        current=new CurrentScreen(
                nextSeq,built.current().screenEpoch(),
                built.current().present(),
                built.current().adapterId(),
                built.current().syncId(),
                built.current().screenClass(),
                built.current().handlerClass());
        transport.send(snapshot);
    }

    private Built build(MinecraftClient client) {
        JsonObject out=new JsonObject();
        if(!(client.currentScreen instanceof HandledScreen<?> handled)
                || client.player==null) {
            if(!"absent".equals(lastOpenKey)) {
                lastOpenKey="absent";
                screenEpoch="";
            }
            out.addProperty("present",false);
            return new Built(
                    out,CurrentScreen.absent(),"absent");
        }
        ScreenHandler handler=handled.getScreenHandler();
        if(handler==client.player.playerScreenHandler) {
            if(!"absent".equals(lastOpenKey)) {
                lastOpenKey="absent";
                screenEpoch="";
            }
            out.addProperty("present",false);
            return new Built(
                    out,CurrentScreen.absent(),"absent");
        }

        String screenClass=client.currentScreen.getClass().getName();
        String handlerClass=handler.getClass().getName();
        // The visual screen may be replaced by a client mod while the same handler remains open.
        // Ownership follows handler incarnation, not cosmetic Screen subclass replacement.
        String openKey=handlerClass+"#"+handler.syncId;
        if(!openKey.equals(lastOpenKey)) {
            lastOpenKey=openKey;
            screenEpoch=UUID.randomUUID().toString();
        }

        var adapter=RealClientScreenAdapterRegistry.describe(
                client,handled);
        out.addProperty("present",true);
        out.addProperty("screen_epoch",screenEpoch);
        out.addProperty("adapter_id",adapter.adapterId());
        out.addProperty("screen_class",screenClass);
        out.addProperty("handler_class",handlerClass);
        out.addProperty(
                "title",client.currentScreen.getTitle().getString());
        out.addProperty("title_origin","mod_ui");
        out.addProperty("title_trust","untrusted_data");
        out.addProperty("sync_id",handler.syncId);

        JsonArray capabilities=new JsonArray();
        adapter.capabilities().stream().sorted()
                .forEach(capabilities::add);
        out.add("capabilities",capabilities);

        JsonArray widgets=new JsonArray();
        for(var widget:adapter.widgets()) {
            JsonObject item=new JsonObject();
            item.addProperty(
                    "widget_index",widget.widgetIndex());
            item.addProperty(
                    "widget_class",widget.widgetClass());
            item.addProperty("message",widget.message());
            item.addProperty("message_origin",widget.messageOrigin());
            item.addProperty("message_trust",widget.messageTrust());
            item.addProperty("active",widget.active());
            item.addProperty("visible",widget.visible());
            widgets.add(item);
        }
        out.add("widgets",widgets);

        JsonArray storageItems=new JsonArray();
        for(var storageItem:adapter.storageItems()) {
            JsonObject item=new JsonObject();
            item.addProperty("item",storageItem.itemId());
            item.addProperty("count",storageItem.count());
            storageItems.add(item);
        }
        out.add("storage_items",storageItems);

        JsonArray slots=new JsonArray();
        int limit=Math.min(MAX_SLOTS,handler.slots.size());
        for(int i=0;i<limit;i++) {
            Slot slot=handler.slots.get(i);
            JsonObject item=new JsonObject();
            item.addProperty("slot_id",slot.id);
            item.addProperty(
                    "inventory_index",slot.getIndex());
            item.addProperty(
                    "inventory_kind",
                    slot.inventory==client.player.getInventory()
                            ?"player":"container");
            var stack=slot.getStack();
            item.addProperty(
                    "item",stack.isEmpty()
                            ?"minecraft:air"
                            :Registries.ITEM.getId(
                                    stack.getItem()).toString());
            item.addProperty(
                    "count",stack.isEmpty()?0:stack.getCount());
            item.addProperty(
                    "can_take",slot.canTakeItems(client.player));
            slots.add(item);
        }
        out.add("slots",slots);
        out.addProperty("slot_count",handler.slots.size());
        out.addProperty(
                "truncated",handler.slots.size()>MAX_SLOTS);

        return new Built(
                out,
                new CurrentScreen(
                        screenSeq,screenEpoch,true,
                        adapter.adapterId(),handler.syncId,
                        screenClass,handlerClass),
                openKey);
    }
}
