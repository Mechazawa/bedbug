package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The sounds and particles an entity's animations ask for, played where the entity is.
 *
 * Both are client effects in Bedrock: they are fired by the animation as it runs rather than sent by
 * a server, which is why a player who cannot see the entity hears and sees nothing.
 */
public final class AddonEffects {
    /** Particles already reported as unshowable, so one missing effect is not logged every frame. */
    private static final Set<String> MISSING = new LinkedHashSet<>();

    private AddonEffects() {
    }

    /** Binds an animator to the entity it is drawn on, so its effects land in the world. */
    public static EntityAnimator.Effects of(Entity entity, String namespace) {
        return new EntityAnimator.Effects() {
            @Override
            public void sound(String soundId) {
                play(entity, namespace, soundId);
            }

            @Override
            public void particle(String particleId, String locator) {
                spawn(entity, particleId, locator);
            }
        };
    }

    /**
     * Plays one of the pack's own sound events at the entity.
     *
     * A definition name keeps its own namespace when it has one, and otherwise belongs to the pack
     * that named it, which is the rule the conversion registered the events under.
     */
    private static void play(Entity entity, String namespace, String soundId) {
        if (soundId == null || entity.level() == null) return;

        int colon = soundId.indexOf(':');
        Identifier id = colon > 0
            ? Platform.id(Names.sanitise(soundId.substring(0, colon)),
                Names.sanitise(soundId.substring(colon + 1)))
            : Platform.id(namespace, Names.sanitise(soundId));

        SoundEvent event = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        if (event == null) return;

        entity.level().playLocalSound(entity.getX(), entity.getY(), entity.getZ(), event,
            SoundSource.NEUTRAL, 1f, 1f, false);
    }

    /**
     * Shows a particle effect at the bone it is anchored to, or at the entity when it names none.
     *
     * A pack's own particle definitions have no Java counterpart, so what can be shown is the vanilla
     * effect a Bedrock id names. Anything else is counted and reported by `/mcaddon entities` rather
     * than silently doing nothing.
     */
    private static void spawn(Entity entity, String particleId, String locator) {
        if (particleId == null || entity.level() == null) return;

        ParticleOptions options = BedrockParticles.vanilla(particleId);
        if (options == null) {
            if (MISSING.add(particleId)) {
                McAddon.LOGGER.warn("A pack asked for the particle {}, which Java has no effect for.",
                    particleId);
            }
            return;
        }

        Vector3f at = anchor(entity, locator);
        entity.level().addParticle(options, at.x(), at.y(), at.z(), 0, 0, 0);
    }

    /** Where the effect is born: the bone's current position when one is named, else the entity. */
    private static Vector3f anchor(Entity entity, String locator) {
        Vector3f position = new Vector3f((float) entity.getX(), (float) entity.getY(), (float) entity.getZ());
        if (locator == null) return position;

        EntityAnimator animator = Animators.existing(entity);
        BonePose pose = animator == null ? null : animator.poseOf(locator);
        if (pose == null) return position;

        // A bone is placed in model space, sixteen units to the block, before the entity is turned.
        float yaw = (float) Math.toRadians(entity.getYRot());
        float x = -pose.position.x() / 16f;
        float z = pose.position.z() / 16f;
        return position.add(
            x * Mth.cos(yaw) - z * Mth.sin(yaw),
            pose.position.y() / 16f,
            x * Mth.sin(yaw) + z * Mth.cos(yaw));
    }
}
