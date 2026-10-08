package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.AddonParticle;
import at.ioexception.mcaddon.clientgametest.mixin.AddonParticleAccessor;
import at.ioexception.mcaddon.clientgametest.mixin.ParticleMotionAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The pack particles the client made since the last time this was cleared, as each was born. */
public final class Born {
    /**
     * One particle as it was added: which effect it belongs to, where it started, its velocity in
     * blocks a tick and its lifetime in ticks. The particle itself is kept to be drawn later.
     */
    public record Particle(String effect, Vec3 at, Vec3 velocity, int lifetime, AddonParticle particle) {
        /** How far into its own life the particle is, as a share of the whole. */
        public float progress() {
            AddonParticleAccessor seconds = (AddonParticleAccessor) particle;
            return seconds.mcaddon$age() / seconds.mcaddon$maxLifetime();
        }
    }

    /** One quad as the particle hands it to the frame: its turn, its size, its patch and its colour. */
    public record Quad(Quaternionf rotation, float size, float u0, float u1, float v0, float v1, int colour) {
    }

    private static final List<Particle> PARTICLES = new CopyOnWriteArrayList<>();

    private Born() {
    }

    public static void record(net.minecraft.client.particle.Particle particle) {
        if (!(particle instanceof AddonParticle addon)) return;

        ParticleMotionAccessor motion = (ParticleMotionAccessor) particle;
        PARTICLES.add(new Particle(((AddonParticleAccessor) addon).mcaddon$definition().identifier(),
            particle.getBoundingBox().getBottomCenter(),
            new Vec3(motion.mcaddon$xd(), motion.mcaddon$yd(), motion.mcaddon$zd()),
            particle.getLifetime(), addon));
    }

    public static void clear() {
        PARTICLES.clear();
    }

    public static List<Particle> of(String effect) {
        return PARTICLES.stream().filter(born -> born.effect().equals(effect)).toList();
    }

    /** What the particle hands the frame when it is drawn from this camera. */
    public static Quad drawn(SingleQuadParticle particle, Camera camera) {
        Quad[] drawn = new Quad[1];
        particle.extract(new QuadParticleRenderState() {
            @Override
            public void add(SingleQuadParticle.Layer layer, float x, float y, float z, float xRot, float yRot,
                float zRot, float wRot, float size, float u0, float u1, float v0, float v1, int colour, int light) {
                drawn[0] = new Quad(new Quaternionf(xRot, yRot, zRot, wRot), size, u0, u1, v0, v1, colour);
            }
        }, camera, 0f);
        return drawn[0];
    }
}
