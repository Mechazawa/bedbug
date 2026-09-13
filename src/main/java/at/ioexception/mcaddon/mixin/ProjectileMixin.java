package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells a pack's script where an arrow or a thrown thing landed, and who released it. */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Inject(method = "onHit", at = @At("HEAD"))
    private void mcaddon$hit(HitResult hit, CallbackInfo info) {
        Projectile self = (Projectile) (Object) this;
        if (self.level().isClientSide()) return;

        WorldEvents.projectileHit(self, self.getOwner(), hit);
    }
}
