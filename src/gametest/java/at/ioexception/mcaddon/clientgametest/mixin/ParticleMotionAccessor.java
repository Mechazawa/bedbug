package at.ioexception.mcaddon.clientgametest.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A particle's velocity, which Java keeps in blocks a tick and gives no getter for. */
@Mixin(Particle.class)
public interface ParticleMotionAccessor {
    @Accessor("xd")
    double mcaddon$xd();

    @Accessor("yd")
    double mcaddon$yd();

    @Accessor("zd")
    double mcaddon$zd();
}
