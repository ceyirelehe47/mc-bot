package io.github.zoyluo.aibot.client.realclient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.screen.GenericContainerScreenHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Trusted exact adapter registry.
 *
 * <p>Minecraft classes are matched by remappable type tokens rather than Yarn class-name strings.
 * Third-party classes use their own stable package names and bounded exact compatibility helpers.</p>
 */
final class RealClientScreenAdapterRegistry {
    static final String VANILLA_GENERIC_STORAGE="vanilla_generic_storage_v1";
    static final String FIXTURE_STORAGE="mc2a_fixture_storage_v1";
    static final String TOMS_STORAGE_TERMINAL="toms_storage_terminal_v1";
    static final String UNRECOGNIZED="unrecognized";

    private static final String FIXTURE_SCREEN=
            "io.github.zoyluo.mc2aui.fixture.client.FixtureGenericContainerScreen";
    private static final int MAX_WIDGETS=32;

    record WidgetSnapshot(
            int widgetIndex,String widgetClass,String message,
            String messageOrigin,String messageTrust,
            boolean active,boolean visible) {}

    record StorageItemSnapshot(String itemId,long count) {}

    record Description(
            String adapterId,Set<String> capabilities,
            List<WidgetSnapshot> widgets,
            List<StorageItemSnapshot> storageItems) {}

    private RealClientScreenAdapterRegistry() {}

    static Description describe(
            MinecraftClient client,HandledScreen<?> screen) {
        String screenClass=screen.getClass().getName();
        String adapterId;
        Set<String> capabilities;
        List<StorageItemSnapshot> storageItems=List.of();

        if(RealClientTomsStorageClientCompat.matches(screen)) {
            adapterId=TOMS_STORAGE_TERMINAL;
            capabilities=Set.of(
                    "deposit_quick_move",
                    "network_storage_read",
                    "widget_introspection");
            storageItems=RealClientTomsStorageClientCompat.readStorageItems(screen);
        } else if(FIXTURE_SCREEN.equals(screenClass)
                && screen.getScreenHandler()
                        instanceof GenericContainerScreenHandler) {
            adapterId=FIXTURE_STORAGE;
            capabilities=Set.of(
                    "deposit_quick_move","widget_introspection");
        } else if(screen.getClass()==GenericContainerScreen.class
                && screen.getScreenHandler()
                        instanceof GenericContainerScreenHandler) {
            adapterId=VANILLA_GENERIC_STORAGE;
            capabilities=Set.of("deposit_quick_move");
        } else {
            adapterId=UNRECOGNIZED;
            capabilities=Set.of();
        }

        List<WidgetSnapshot> widgets=new ArrayList<>();
        int index=0;
        for(Element element:screen.children()) {
            if(element instanceof ClickableWidget widget) {
                widgets.add(new WidgetSnapshot(
                        index,widget.getClass().getName(),
                        widget.getMessage().getString(),
                        "mod_ui","untrusted_data",
                        widget.active,widget.visible));
                if(widgets.size()>=MAX_WIDGETS)break;
            }
            index++;
        }
        return new Description(
                adapterId,capabilities,List.copyOf(widgets),
                List.copyOf(storageItems));
    }

    static boolean supportsDeposit(String adapterId) {
        return VANILLA_GENERIC_STORAGE.equals(adapterId)
                || FIXTURE_STORAGE.equals(adapterId)
                || TOMS_STORAGE_TERMINAL.equals(adapterId);
    }
}
