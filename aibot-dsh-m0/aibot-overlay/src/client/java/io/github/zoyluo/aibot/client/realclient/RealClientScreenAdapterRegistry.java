package io.github.zoyluo.aibot.client.realclient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ClickableWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Trusted client-side Screen adapter registry.
 *
 * <p>Adapters are exact class-name allowlists. They describe semantic roles and permitted bounded
 * mutations; they never expose arbitrary screen coordinates or a generic widget activation API.</p>
 */
final class RealClientScreenAdapterRegistry {
    static final String VANILLA_GENERIC_STORAGE="vanilla_generic_storage_v1";
    static final String FIXTURE_STORAGE="mc2a_fixture_storage_v1";
    static final String UNRECOGNIZED="unrecognized";

    private static final String VANILLA_SCREEN=
            "net.minecraft.client.gui.screen.ingame.GenericContainerScreen";
    private static final String GENERIC_HANDLER=
            "net.minecraft.screen.GenericContainerScreenHandler";
    private static final String FIXTURE_SCREEN=
            "io.github.zoyluo.mc2aui.fixture.client.FixtureGenericContainerScreen";
    private static final int MAX_WIDGETS=32;

    record WidgetSnapshot(
            int widgetIndex,String widgetClass,String message,
            boolean active,boolean visible) {}

    record Description(
            String adapterId,Set<String> capabilities,
            List<WidgetSnapshot> widgets) {}

    private RealClientScreenAdapterRegistry() {}

    static Description describe(
            MinecraftClient client,HandledScreen<?> screen) {
        String screenClass=screen.getClass().getName();
        String handlerClass=screen.getScreenHandler().getClass().getName();
        String adapterId;
        Set<String> capabilities;
        if(FIXTURE_SCREEN.equals(screenClass)
                && GENERIC_HANDLER.equals(handlerClass)) {
            adapterId=FIXTURE_STORAGE;
            capabilities=Set.of("deposit_quick_move","widget_introspection");
        } else if(VANILLA_SCREEN.equals(screenClass)
                && GENERIC_HANDLER.equals(handlerClass)) {
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
                        widget.active,widget.visible));
                if(widgets.size()>=MAX_WIDGETS)break;
            }
            index++;
        }
        return new Description(
                adapterId,capabilities,List.copyOf(widgets));
    }

    static boolean supportsDeposit(String adapterId) {
        return VANILLA_GENERIC_STORAGE.equals(adapterId)
                || FIXTURE_STORAGE.equals(adapterId);
    }
}
