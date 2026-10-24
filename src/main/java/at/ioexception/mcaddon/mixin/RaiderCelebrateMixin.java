package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.VanillaEvents;
import net.minecraft.world.entity.raid.Raider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Raider.class)
public abstract class RaiderCelebrateMixin {
    @Inject(method = "setCelebrating", at = @At("HEAD"))
    private void mcaddon$celebrates(boolean celebrating, CallbackInfo info) {
        Raider raider = (Raider) (Object) this;
        if (celebrating && !raider.isCelebrating()) VanillaEvents.celebrating(raider);
    }
}
