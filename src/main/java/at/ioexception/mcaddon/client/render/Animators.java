package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.animation.PlayedAnimation;
import at.ioexception.mcaddon.bedrock.entity.EntityQueries;
import at.ioexception.mcaddon.bedrock.entity.EntityReference;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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
    /** One animator, the queries it answers through and the moment it was last advanced to. */
    record Entry(EntityAnimator animator, EntityQueries queries, float[] lastTime) {
        static Entry of(Entity entity, AddonClientAssets.Binding binding, boolean attached, float ageInTicks) {
            EntityAnimator animator = new EntityAnimator(binding.models().assets(), entity, binding.definition());
            EntityQueries queries = new EntityQueries(entity, animator.player(),
                new DrawnModel(animator, binding.models(), entity.getId(), attached),
                () -> EntityProperties.of(entity.getId()), attached);
            animator.environment().setQueries(queries);
            animator.setEffects(AddonEffects.of(entity, animator, binding.models()));
            return new Entry(animator, queries, new float[] {ageInTicks});
        }

        /**
         * Moves the animator on by however far the entity's clock has gone since the furthest it
         * was moved to.
         *
         * A frame may ask for the entity at an earlier moment than the last one did, at a partial
         * tick of nothing where the previous frame was most of the way through. Such a call poses
         * the entity at its own moment and leaves the clock where it was, because winding the clock
         * back would have the next call play the same stretch of every clip a second time.
         */
        void advance(float partialTick, float ageInTicks, String itemSlot) {
            float now = ageInTicks + partialTick;
            float delta = Math.max(0, now - lastTime[0]) / 20f;
            lastTime[0] = Math.max(lastTime[0], now);
            queries.setPartialTick(partialTick);
            queries.setItemSlot(itemSlot);
            animator.tick(delta, now / 20f);
        }
    }

    private static final Map<String, Entry> ANIMATORS = new HashMap<>();

    private Animators() {
    }

    /** Null when the entity's pack never described how it looks. */
    public static EntityAnimator of(Entity entity, String identifier, float partialTick, float ageInTicks) {
        return of(entity, AddonClientAssets.of(identifier), identifier, null, partialTick, ageInTicks);
    }

    /**
     * One animator per entity and per thing drawn on it.
     *
     * A worn attachable runs its own animation script beside the entity's, so it needs a clock and a
     * set of variables of its own rather than sharing the wearer's. `itemSlot` names the slot such a
     * thing is worn in and is null for the entity itself, which is what Bedrock answers
     * `query.is_attached`, `query.owner_identifier` and `context.item_slot` from.
     */
    public static EntityAnimator of(Entity entity, AddonClientAssets.Binding binding, String key,
        String itemSlot, float partialTick, float ageInTicks) {
        if (binding == null) return null;

        boolean attached = itemSlot != null;
        Entry entry = ANIMATORS.computeIfAbsent(entity.getId() + "/" + key,
            id -> Entry.of(entity, binding, attached, ageInTicks));
        entry.advance(partialTick, ageInTicks, attached ? itemSlot : "");
        return entry.animator();
    }

    /**
     * Advances the entities whose packs asked to keep running while nobody is looking at them.
     *
     * An animator is otherwise made and advanced by the renderer, so an entity out of view holds
     * its pose, its state machine stops where it stood and its emitters fall silent. A pack writing
     * either offscreen field in its scripts block is saying that is the wrong answer for this
     * entity, which anything with a trail, an aura or a clock of its own depends on.
     *
     * An entity in view is advanced by whichever of the two runs first in a frame, because the
     * animator takes its step from how far the clock has moved since it last ran.
     */
    public static void tickOffscreen(ClientLevel level) {
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof AddonEntity addon)) continue;

            String identifier = AddonEntityRenderer.identifierOf(addon);
            AddonClientAssets.Binding binding = AddonClientAssets.of(identifier);
            if (binding == null || !binding.definition().runsOffscreen()) continue;

            of(addon, binding, identifier, null, 0f, addon.tickCount);
        }
    }

    /**
     * Starts a clip a script asked for, on whichever animator is already drawing the entity.
     *
     * An add-on entity that is not being drawn runs nothing, which is the same as Bedrock: an
     * animation is a client effect and a client that cannot see the entity has nothing to run it
     * on. An entity Java draws with a model of its own is posed through that model's parts as well.
     */
    public static void play(int entityId, PlayedAnimation request) {
        ANIMATORS.forEach((key, entry) -> {
            if (key.startsWith(entityId + "/")) entry.animator().playDirect(request);
        });

        ClientLevel level = Minecraft.getInstance().level;
        Entity entity = level == null ? null : level.getEntity(entityId);
        if (entity != null && !(entity instanceof AddonEntity)) {
            JavaModelPoses.play(entity, request);
        }
    }

    /**
     * The animator drawing the entity itself, which is what Bedrock calls the root model.
     *
     * Null for an entity Java draws with its own model, because then nothing an add-on described is
     * underneath whatever is worn on it.
     */
    public static EntityAnimator rootOf(int entityId) {
        return ANIMATORS.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(entityId + "/"))
            .map(Map.Entry::getValue)
            .filter(entry -> !entry.queries().attached())
            .map(Entry::animator)
            .findFirst()
            .orElse(null);
    }

    /**
     * What `->` reads of an entity on the client: the published scope of the animator drawing it,
     * else a scope answering its queries.
     */
    public static MolangEnvironment scopeOf(Entity entity) {
        EntityAnimator drawing = rootOf(entity.getId());
        return drawing != null ? drawing.published()
            : EntityReference.questions(entity, () -> EntityProperties.of(entity.getId()));
    }

    public static void forget(int entityId) {
        ANIMATORS.keySet().removeIf(key -> key.startsWith(entityId + "/"));
    }

    public static void clear() {
        ANIMATORS.clear();
    }
}
