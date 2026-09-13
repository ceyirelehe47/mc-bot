package io.github.zoyluo.aibot.client.realclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/** Bounded read-only native Screen/ScreenHandler snapshot publisher. */
final class RealClientScreenController {
    private static final int MAX_SLOTS=128;
    private static final int RESEND_TICKS=40;

    private final RealClientClientTransport transport;
    private long screenSeq=-1;
    private String lastFingerprint="";
    private int resendTicks;

    RealClientScreenController(RealClientClientTransport transport) {
        this.transport=transport;
    }

    void gameSessionStarted() {
        screenSeq=-1;
        lastFingerprint="";
        resendTicks=0;
    }

    void disconnected() {
        gameSessionStarted();
    }

    void tick(MinecraftClient client) {
        if(!transport.connected())return;
        JsonObject snapshot=build(client);
        String fingerprint=snapshot.toString();
        if(fingerprint.equals(lastFingerprint) && ++resendTicks<RESEND_TICKS)
            return;
        lastFingerprint=fingerprint;
        resendTicks=0;
        snapshot.addProperty("type","screen");
        snapshot.addProperty("screen_seq",++screenSeq);
        transport.send(snapshot);
    }

    private static JsonObject build(MinecraftClient client) {
        JsonObject out=new JsonObject();
        if(!(client.currentScreen instanceof HandledScreen<?> handled)
                || client.player==null) {
            out.addProperty("present",false);
            return out;
        }
        ScreenHandler handler=handled.getScreenHandler();
        if(handler==client.player.playerScreenHandler) {
            out.addProperty("present",false);
            return out;
        }
        out.addProperty("present",true);
        out.addProperty("screen_class",client.currentScreen.getClass().getName());
        out.addProperty("handler_class",handler.getClass().getName());
        out.addProperty("title",client.currentScreen.getTitle().getString());
        out.addProperty("sync_id",handler.syncId);
        JsonArray slots=new JsonArray();
        int limit=Math.min(MAX_SLOTS,handler.slots.size());
        for(int i=0;i<limit;i++) {
            Slot slot=handler.slots.get(i);
            JsonObject item=new JsonObject();
            item.addProperty("slot_id",slot.id);
            item.addProperty("inventory_index",slot.getIndex());
            item.addProperty("inventory_kind",
                    slot.inventory==client.player.getInventory()
                            ?"player":"container");
            var stack=slot.getStack();
            item.addProperty("item",stack.isEmpty()
                    ?"minecraft:air"
                    :Registries.ITEM.getId(stack.getItem()).toString());
            item.addProperty("count",stack.isEmpty()?0:stack.getCount());
            item.addProperty("can_take",slot.canTakeItems(client.player));
            slots.add(item);
        }
        out.add("slots",slots);
        out.addProperty("slot_count",handler.slots.size());
        out.addProperty("truncated",handler.slots.size()>MAX_SLOTS);
        return out;
    }
}
