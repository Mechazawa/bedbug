package at.ioexception.mcaddon.client.render;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Which entity a render state was extracted from.
 *
 * From 1.21.2 a renderer draws from a state object rather than from the entity, and that state
 * carries only what vanilla's own layers need. An add-on's worn models need the entity itself, so
 * the pairing is recorded as the state is filled in and read back while drawing.
 *
 * Weakly keyed: a state that the renderer has thrown away takes its entry with it.
 */
public final class RenderStates {
    private static final Map<Object, LivingEntity> ENTITIES = new WeakHashMap<>();

    private RenderStates() {
    }

    public static void remember(Object state, LivingEntity entity) {
        ENTITIES.put(state, entity);
    }

    /** Null on the versions that draw from the entity directly and never record anything here. */
    public static LivingEntity of(Object state) {
        return ENTITIES.get(state);
    }
}
