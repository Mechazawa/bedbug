package at.ioexception.mcaddon.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The flag Java sets on a particle the first time a block stops it moving up or down, after which
 * its `move` does nothing. A Bedrock particle bounces and slides on, so it is cleared before each move.
 */
@Mixin(Particle.class)
public interface ParticleCollisionAccessor {
    @Accessor("stoppedByCollision")
    void mcaddon$setStoppedByCollision(boolean stopped);
}
