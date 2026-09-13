package io.github.zoyluo.aibot.mixin.client;

import io.github.zoyluo.aibot.client.realclient.RealClientInputIsolation;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents Bob's unattended client from locking or consuming the user's physical mouse. */
@Mixin(Mouse.class)
abstract class RealClientMouseMixin {
    @Inject(method="lockCursor",at=@At("HEAD"),cancellable=true)
    private void aibot$denyCursorLock(CallbackInfo ci) {
        if(RealClientInputIsolation.suppressHumanInput())ci.cancel();
    }

    @Inject(method="onCursorPos",at=@At("HEAD"),cancellable=true)
    private void aibot$ignoreHumanCursor(
            long window,double x,double y,CallbackInfo ci) {
        if(RealClientInputIsolation.suppressHumanInput())ci.cancel();
    }

    @Inject(method="onMouseButton",at=@At("HEAD"),cancellable=true)
    private void aibot$ignoreHumanMouseButton(
            long window,int button,int action,int mods,CallbackInfo ci) {
        if(RealClientInputIsolation.suppressHumanInput())ci.cancel();
    }

    @Inject(method="onMouseScroll",at=@At("HEAD"),cancellable=true)
    private void aibot$ignoreHumanMouseScroll(
            long window,double horizontal,double vertical,CallbackInfo ci) {
        if(RealClientInputIsolation.suppressHumanInput())ci.cancel();
    }
}
