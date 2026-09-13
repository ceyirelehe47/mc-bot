package io.github.zoyluo.mc2aui.fixture.client.mixin;

import io.github.zoyluo.mc2aui.fixture.client.FixtureGenericContainerScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only the exact vanilla generic container screen; the handler and server stay vanilla. */
@Mixin(MinecraftClient.class)
abstract class MinecraftClientScreenMixin {
    @Inject(method="setScreen",at=@At("HEAD"),cancellable=true)
    private void mc2a$replaceGenericContainer(
            Screen screen,CallbackInfo ci) {
        if(screen==null
                ||screen.getClass()!=GenericContainerScreen.class)
            return;
        MinecraftClient client=(MinecraftClient)(Object)this;
        if(client.player==null)return;
        var handler=((HandledScreen<?>)screen).getScreenHandler();
        if(!(handler instanceof GenericContainerScreenHandler generic))
            return;
        client.setScreen(new FixtureGenericContainerScreen(
                generic,client.player.getInventory(),screen.getTitle()));
        ci.cancel();
    }
}
