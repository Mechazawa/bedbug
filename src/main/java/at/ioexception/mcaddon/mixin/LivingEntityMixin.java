package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells a pack's script when an effect lands on something.
 *
 * Fabric has a callback for this only from 26.2, and the mod runs on four versions, so the effect is
 * watched here instead. A pack refusing the effect is honoured by stopping the call before the game
 * applies it.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void mcaddon$beforeEffect(MobEffectInstance effect, net.minecraft.world.entity.Entity source,
        CallbackInfoReturnable<Boolean> info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;

        if (WorldEvents.effectRefused(self, effect)) info.setReturnValue(false);
    }

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
        at = @At("RETURN"), require = 0)
    private void mcaddon$afterEffect(MobEffectInstance effect, net.minecraft.world.entity.Entity source,
        CallbackInfoReturnable<Boolean> info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide() || !Boolean.TRUE.equals(info.getReturnValue())) return;

        WorldEvents.effectAdded(self, effect);
    }
}
