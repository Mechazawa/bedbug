package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.AnimationPlayer;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonParticles;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;

import java.util.List;

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
    public static AnimationPlayer.EffectSink of(Entity entity, EntityAnimator animator, AddonModels models) {
        String namespace = models.converted().namespace();
        return new AnimationPlayer.EffectSink() {
            @Override
            public void sound(String soundId) {
                play(entity, namespace, soundId);
            }

            @Override
            public void particle(String particleId, String locator) {
                spawn(entity, animator, models, particleId, locator);
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
     * Shows a particle effect at the locator it is anchored to, or at the entity when it names none.
     *
     * A pack's own particle definitions have no Java counterpart, so what can be shown is the vanilla
     * effect a Bedrock id names. Anything else is counted and reported by `/mcaddon entities` rather
     * than silently doing nothing.
     */
    private static void spawn(Entity entity, EntityAnimator animator, AddonModels models, String particleId,
        String locator) {
        if (particleId == null || entity.level() == null) return;

        Vector3f at = anchor(entity, animator, models, locator);
        if (AddonParticles.definitionOf(particleId) != null) {
            AddonEmitters.start(particleId, entity,
                new net.minecraft.world.phys.Vec3(at.x() - entity.getX(), at.y() - entity.getY(),
                    at.z() - entity.getZ()));
            return;
        }

        ParticleOptions options = BedrockParticles.vanilla(particleId);
        if (options == null) {
            AddonEntities.unsupported("particle " + particleId);
            return;
        }
        entity.level().addParticle(options, at.x(), at.y(), at.z(), 0, 0, 0);
    }

    /**
     * Where the effect is born: the locator where the drawn model puts it, else the entity's feet.
     *
     * The model is the one the entity's first pass draws, since a render controller may pick another
     * geometry than the one the queries read. The locator is carried through its bone's pose and
     * then through the space the renderer draws the model in, which turns it with the body and
     * scales it with the entity, so the point lands where the drawn model shows it.
     */
    private static Vector3f anchor(Entity entity, EntityAnimator animator, AddonModels models, String locator) {
        Vector3f feet = new Vector3f((float) entity.getX(), (float) entity.getY(), (float) entity.getZ());
        List<AddonModels.Pass> passes = locator == null ? List.of() : models.resolve(animator);
        Vector3f placed = passes.isEmpty() ? null
            : BedrockModelRenderer.locatorOf(passes.get(0).geometry(), animator, locator);
        if (placed == null) return feet;

        PoseStack modelSpace = new PoseStack();
        AddonEntityRenderer.applyModelSpace(modelSpace,
            entity instanceof LivingEntity living ? living.yBodyRot : entity.getYRot(),
            entity instanceof AddonEntity addon ? AddonEntityRenderer.drawnScale(addon, animator) : animator.scale());
        return feet.add(modelSpace.last().pose().transformPosition(placed));
    }
}
