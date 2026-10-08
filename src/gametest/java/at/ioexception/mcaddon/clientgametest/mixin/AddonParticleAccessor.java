package at.ioexception.mcaddon.clientgametest.mixin;

import at.ioexception.mcaddon.bedrock.particle.ParticleDefinition;
import at.ioexception.mcaddon.client.render.AddonParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which effect a particle belongs to and how far through its own life it is, in seconds. */
@Mixin(AddonParticle.class)
public interface AddonParticleAccessor {
    @Accessor("definition")
    ParticleDefinition mcaddon$definition();

    @Accessor("age")
    float mcaddon$age();

    @Accessor("maxLifetime")
    float mcaddon$maxLifetime();
}
