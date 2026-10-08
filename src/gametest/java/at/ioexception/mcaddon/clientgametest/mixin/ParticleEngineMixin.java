package at.ioexception.mcaddon.clientgametest.mixin;

import at.ioexception.mcaddon.clientgametest.Born;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Writes down every particle the client adds, as it was the moment before its first tick. */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void mcaddon$recordBirth(Particle particle, CallbackInfo info) {
        Born.record(particle);
    }
}
