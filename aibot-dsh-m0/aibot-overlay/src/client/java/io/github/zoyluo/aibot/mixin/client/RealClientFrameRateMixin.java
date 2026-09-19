package io.github.zoyluo.aibot.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.zoyluo.aibot.client.realclient.RealClientInputIsolation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Bob 的真实客户端无人值守运行时窗口 inactive,Minecraft 会把帧率压到极低。
 * 低帧率下 CONFIGURATION 阶段(入服与死亡重生都要重走)在 30s 服务器预算内处理不完,
 * 触发 "Took too long to log in" 断连死循环。无人值守模式保持 30fps 下限保证协议推进。
 */
@Mixin(RenderSystem.class)
public abstract class RealClientFrameRateMixin {
    @ModifyVariable(
            method = "limitDisplayFPS",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private static int aibot$keepFrameRate(int fps) {
        if (!RealClientInputIsolation.enabled()) {
            return fps;
        }
        return Math.max(fps, 30);
    }
}
