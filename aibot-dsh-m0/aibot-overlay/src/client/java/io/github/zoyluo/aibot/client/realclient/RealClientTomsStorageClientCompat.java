package io.github.zoyluo.aibot.client.realclient;

import io.github.zoyluo.aibot.AIBotMod;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exact, bounded compatibility layer for Tom's Simple Storage terminal UI.
 *
 * <p>No compile-time dependency and no generic reflection API are exposed. Only the pinned exact
 * classes and three known public members are read. Mutation still uses the normal ScreenHandler
 * QUICK_MOVE path owned by DepositAction.</p>
 */
final class RealClientTomsStorageClientCompat {
    static final String SCREEN_CLASS=
            "com.tom.storagemod.screen.StorageTerminalScreen";
    static final String HANDLER_CLASS=
            "com.tom.storagemod.menu.StorageTerminalMenu";
    static final String ITEM_CLASS=
            "com.tom.storagemod.inventory.TerminalItemStack";
    private static final int MAX_ITEMS=128;
    private static final AtomicBoolean WARNED=new AtomicBoolean();

    private RealClientTomsStorageClientCompat() {}

    static boolean matches(HandledScreen<?> screen) {
        return SCREEN_CLASS.equals(screen.getClass().getName())
                && HANDLER_CLASS.equals(
                        screen.getScreenHandler().getClass().getName());
    }

    static List<RealClientScreenAdapterRegistry.StorageItemSnapshot>
            readStorageItems(HandledScreen<?> screen) {
        if(!matches(screen))return List.of();
        try {
            Object handler=screen.getScreenHandler();
            Field listField=handler.getClass().getField(
                    "itemListClientSorted");
            Object value=listField.get(handler);
            if(!(value instanceof List<?> list))return List.of();

            List<RealClientScreenAdapterRegistry.StorageItemSnapshot> out=
                    new ArrayList<>();
            for(Object entry:list) {
                if(entry==null
                        ||!ITEM_CLASS.equals(entry.getClass().getName()))
                    continue;
                Method getStack=entry.getClass().getMethod("getStack");
                Method getQuantity=entry.getClass().getMethod("getQuantity");
                Object stackValue=getStack.invoke(entry);
                Object countValue=getQuantity.invoke(entry);
                if(!(stackValue instanceof ItemStack stack)
                        ||!(countValue instanceof Number count))
                    continue;
                long quantity=count.longValue();
                if(stack.isEmpty() || quantity<=0)continue;
                out.add(new RealClientScreenAdapterRegistry.StorageItemSnapshot(
                        Registries.ITEM.getId(stack.getItem()).toString(),
                        quantity));
                if(out.size()>=MAX_ITEMS)break;
            }
            return List.copyOf(out);
        } catch(ReflectiveOperationException | RuntimeException failure) {
            if(WARNED.compareAndSet(false,true))
                AIBotMod.LOGGER.warn(
                        "Tom's Storage terminal adapter read failed closed: {}",
                        failure.toString());
            return List.of();
        }
    }
}
