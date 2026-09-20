package io.github.zoyluo.mc2aui.fixture.client;

import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;

/**
 * Deliberately custom client Screen over the vanilla GenericContainerScreenHandler.
 * The extra widget is inert; the production adapter may introspect it but may not click it.
 */
public final class FixtureGenericContainerScreen extends GenericContainerScreen {
    public FixtureGenericContainerScreen(
            GenericContainerScreenHandler handler,
            PlayerInventory inventory,Text title) {
        super(handler,inventory,title);
    }

    @Override protected void init() {
        super.init();
        addDrawableChild(ButtonWidget.builder(
                Text.literal("Fixture Inspect"),
                button -> Mc2aUiFixtureClient.LOGGER.info(
                        "Fixture Inspect button clicked by a human"))
                .dimensions(x+backgroundWidth-92,y+5,88,20)
                .build());
    }
}
