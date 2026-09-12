package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * One animator per entity on screen, advanced once per frame.
 *
 * Keyed by network id and dropped when the entity goes, because a map keyed by entity that nothing
 * removes from grows for the whole session.
 */
public final class Animators {
    private record Entry(EntityAnimator animator, AddonQueries queries, float[] lastTime) {
    }

    private static final Map<String, Entry> ANIMATORS = new HashMap<>();

    private Animators() {
    }

    /** Null when the entity's pack never described how it looks. */
    public static EntityAnimator of(Entity entity, String identifier, float partialTick, float ageInTicks) {
        return of(entity, AddonClientAssets.of(identifier), identifier, partialTick, ageInTicks);
    }

    /**
     * One animator per entity and per thing drawn on it.
     *
     * A worn attachable runs its own animation script beside the entity's, so it needs a clock and a
     * set of variables of its own rather than sharing the wearer's.
     */
    public static EntityAnimator of(Entity entity, AddonClientAssets.Binding binding, String key,
        float partialTick, float ageInTicks) {
        if (binding == null) return null;

        Entry entry = ANIMATORS.computeIfAbsent(entity.getId() + "/" + key, id -> {
            EntityAnimator animator = new EntityAnimator(binding.models().assets(), binding.definition());
            AddonQueries queries = new AddonQueries(entity, animator);
            animator.environment().setQueries(queries);
            animator.setEffects(AddonEffects.of(entity, binding.models().converted().namespace()));
            return new Entry(animator, queries, new float[] {ageInTicks});
        });

        float now = ageInTicks + partialTick;
        float delta = Math.max(0, now - entry.lastTime()[0]) / 20f;
        entry.lastTime()[0] = now;
        entry.queries().setPartialTick(partialTick);
        entry.animator().tick(delta, now / 20f);
        return entry.animator();
    }

    /**
     * Starts a clip a script asked for, on whichever animator is already drawing the entity.
     *
     * Nothing happens when the entity is not being drawn, which is the same as Bedrock: an animation
     * is a client effect and a client that cannot see the entity has nothing to run it on.
     */
    public static void play(int entityId, String animation, String stopExpression, float blendOut) {
        ANIMATORS.forEach((key, entry) -> {
            if (key.startsWith(entityId + "/")) {
                entry.animator().playDirect(animation, stopExpression, blendOut);
            }
        });
    }

    /** The animator already drawing an entity, without starting one, for an effect to anchor to. */
    public static EntityAnimator existing(Entity entity) {
        for (Map.Entry<String, Entry> each : ANIMATORS.entrySet()) {
            if (each.getKey().startsWith(entity.getId() + "/")) return each.getValue().animator();
        }
        return null;
    }

    public static void forget(int entityId) {
        ANIMATORS.keySet().removeIf(key -> key.startsWith(entityId + "/"));
    }

    public static void clear() {
        ANIMATORS.clear();
    }
}
