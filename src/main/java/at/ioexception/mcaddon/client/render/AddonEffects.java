package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.AnimationPlayer;
import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;

/**
 * The sounds and particles an entity's animations ask for, played where the entity is.
 *
 * Both are client effects in Bedrock: they are fired by the animation as it runs rather than sent by
 * a server, which is why a player who cannot see the entity hears and sees nothing.
 */
public final class AddonEffects {
    private AddonEffects() {
    }

    /** Binds an animator to the entity it is drawn on, so its effects land in the world. */
    public static AnimationPlayer.EffectSink of(Entity entity, EntityAnimator animator, String namespace) {
        return new AnimationPlayer.EffectSink() {
            @Override
            public void sound(String soundId) {
                play(entity, namespace, soundId);
            }

            @Override
            public void particle(String particleId, String locator) {
                spawn(entity, animator, particleId, locator);
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

        Names.Identifier name = Names.soundEvent(soundId, namespace);
        SoundEvent event = BuiltInRegistries.SOUND_EVENT
            .getOptional(Platform.id(name.namespace(), name.path())).orElse(null);
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
     *
     * A locator names a bone, and the animator drawing the entity is the one holding that bone.
     */
    private static void spawn(Entity entity, EntityAnimator animator, String particleId, String locator) {
        if (particleId == null || entity.level() == null) return;

        ParticleOptions options = BedrockParticles.vanilla(particleId);
        if (options == null) {
            AddonEntities.unsupported("particle " + particleId);
            return;
        }

        Vector3f at = anchor(entity, animator, locator);
        entity.level().addParticle(options, at.x(), at.y(), at.z(), 0, 0, 0);
    }

    /** Where the effect is born: the bone's current position when one is named, else the entity. */
    private static Vector3f anchor(Entity entity, EntityAnimator animator, String locator) {
        Vector3f position = new Vector3f((float) entity.getX(), (float) entity.getY(), (float) entity.getZ());
        if (locator == null) return position;

        BonePose pose = animator.poseOf(locator);
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
