package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells a pack's script when an effect lands on something, and when health moves.
 *
 * Fabric has a callback for the effect only from 26.2, and none at all for healing, and the mod runs
 * on four versions, so both are watched here. A pack refusing either is honoured by stopping the call
 * before the game carries it out.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    /** The health the entity carried when `setHealth` was entered, which is gone by the time it ends. */
    @Unique
    private float mcaddonHealthBefore;

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

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true, require = 0)
    private void mcaddon$beforeHeal(float amount, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) return;

        if (WorldEvents.healRefused(self, amount)) info.cancel();
    }

    @Inject(method = "heal", at = @At("RETURN"), require = 0)
    private void mcaddon$afterHeal(float amount, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.level().isClientSide()) WorldEvents.healed(self, amount);
    }

    @Inject(method = "setHealth", at = @At("HEAD"), require = 0)
    private void mcaddon$beforeHealth(float health, CallbackInfo info) {
        mcaddonHealthBefore = ((LivingEntity) (Object) this).getHealth();
    }

    /**
     * The server thread is asked for, because the game sets health outside a tick as well as in one.
     *
     * An entity read back off the disk has its health set by the worker thread that loaded the
     * chunk, before it is in the world at all. A script belongs to the server thread and would
     * refuse the call, so what Bedrock does not call a health change is not offered as one.
     */
    @Inject(method = "setHealth", at = @At("RETURN"), require = 0)
    private void mcaddon$afterHealth(float health, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        MinecraftServer server = self.level().getServer();
        if (server != null && server.isSameThread()) {
            WorldEvents.healthChanged(self, mcaddonHealthBefore);
        }
    }
}
