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

    private static final Map<Integer, Entry> ANIMATORS = new HashMap<>();

    private Animators() {
    }

    /** Null when the entity's pack never described how it looks. */
    public static EntityAnimator of(Entity entity, String identifier, float partialTick, float ageInTicks) {
        AddonClientAssets.Binding binding = AddonClientAssets.of(identifier);
        if (binding == null) return null;

        Entry entry = ANIMATORS.computeIfAbsent(entity.getId(), id -> {
            EntityAnimator animator = new EntityAnimator(binding.models().assets(), binding.definition());
            AddonQueries queries = new AddonQueries(entity, animator);
            animator.environment().setQueries(queries);
            return new Entry(animator, queries, new float[] {ageInTicks});
        });

        float now = ageInTicks + partialTick;
        float delta = Math.max(0, now - entry.lastTime()[0]) / 20f;
        entry.lastTime()[0] = now;
        entry.queries().setPartialTick(partialTick);
        entry.animator().tick(delta, now / 20f);
        return entry.animator();
    }

    public static void forget(int entityId) {
        ANIMATORS.remove(entityId);
    }

    public static void clear() {
        ANIMATORS.clear();
    }
}
