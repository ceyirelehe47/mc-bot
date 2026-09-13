package io.github.zoyluo.aibot.mixin.client;

import io.github.zoyluo.aibot.client.realclient.RealClientInputIsolation;
import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents accidental real keyboard input from steering the unattended Bob window. */
@Mixin(Keyboard.class)
abstract class RealClientKeyboardMixin {
    @Inject(method="onKey",at=@At("HEAD"),cancellable=true)
    private void aibot$ignoreHumanKey(
            long window,int key,int scancode,int action,int modifiers,
            CallbackInfo ci) {
        if(RealClientInputIsolation.suppressHumanInput())ci.cancel();
    }

    @Inject(method="onChar",at=@At("HEAD"),cancellable=true)
    private void aibot$ignoreHumanChar(
            long window,int codePoint,int modifiers,CallbackInfo ci) {
        if(RealClientInputIsolation.suppressHumanInput())ci.cancel();
    }
}
