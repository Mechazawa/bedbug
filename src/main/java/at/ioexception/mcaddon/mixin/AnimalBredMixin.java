package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.VanillaEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Animal.class)
public abstract class AnimalBredMixin {
    @Inject(method = "finalizeSpawnChildFromBreeding", at = @At("HEAD"))
    private void mcaddon$bred(ServerLevel level, Animal partner, AgeableMob young, CallbackInfo info) {
        VanillaEvents.bred(young);
    }
}
