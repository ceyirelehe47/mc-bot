package io.github.zoyluo.aibot.mixin;

import io.github.zoyluo.aibot.external.realclient.RealClientEatWitness;
import net.minecraft.component.type.ConsumableComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MC-RCF-1-R3 F04: observe the game's own consumption-completion processing.
 *
 * <p>{@code ConsumableComponent#finishConsumption} is the single normal path
 * the game runs when a started use actually finishes (food eaten, potion
 * drunk, ...). Injecting at HEAD on the dedicated server for real players
 * gives a read-only fact that no inventory-subtraction arithmetic can
 * fabricate: external clears/drops never execute this method. The hook never
 * mutates anything — it only records a {@link RealClientEatWitness}
 * entry.</p>
 */
@Mixin(ConsumableComponent.class)
abstract class ConsumableComponentEatWitnessMixin {

    @Inject(method="finishConsumption",
            at=@At("HEAD"))
    private void aibot$witnessNormalConsumption(
            World world, LivingEntity user, ItemStack stack,
            CallbackInfoReturnable<ItemStack> cir) {
        // Read-only observation; runs inside the server tick that performs
        // the real completion. Client-side invocations are ignored.
        if(world.isClient)
            return;
        if(!(user instanceof ServerPlayerEntity player))
            return;
        if(stack.isEmpty())
            return;
        Hand hand;
        try {
            hand=user.getActiveHand();
        } catch(RuntimeException noActiveUse) {
            hand=Hand.MAIN_HAND;
        }
        RealClientEatWitness.record(
                player.getUuidAsString(),
                Registries.ITEM.getId(stack.getItem()).toString(),
                hand.name(),
                world.getRegistryKey().getValue().toString(),
                world.getTime());
    }
}
