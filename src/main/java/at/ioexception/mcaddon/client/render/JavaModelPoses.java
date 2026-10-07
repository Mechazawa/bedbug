package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bedrock clips played on entities Java draws with a model of its own.
 *
 * A pack plays a clip on a vanilla mob naming the bones of Bedrock's own model of it, and the same
 * pieces exist in Java's model under the names `JavaModelParts` matches them by. Each such entity
 * gets an animator per pack whose clips it runs, advanced once a frame as the entity is drawn, and
 * the pose it works out moves the model's parts after the model has posed itself, for that one draw.
 *
 * An entity is dropped as soon as no clip is left on it, so the model is back to its own pose on
 * the next frame and an entity nothing was played on costs one map lookup.
 */
public final class JavaModelPoses {
    /** Per entity, one animator for each pack whose clips are playing on it. */
    private static final Map<Integer, Map<AddonModels, Animators.Entry>> POSED = new HashMap<>();

    /** The bones already named as having no part, per kind of entity, so each is counted once. */
    private static final Set<String> REPORTED = new HashSet<>();

    private JavaModelPoses() {
    }

    static void play(Entity entity, String animation, String stopExpression, float blendOut) {
        AddonModels models = AddonClientAssets.withAnimation(animation);
        if (models == null) {
            report("animation " + animation + ", which no resource pack defines");
            return;
        }

        POSED.computeIfAbsent(entity.getId(), id -> new LinkedHashMap<>())
            .computeIfAbsent(models, pack -> Animators.Entry.of(entity,
                new AddonClientAssets.Binding(pack, ClientEntityDefinition.drawnByJava(typeOf(entity))),
                false, entity.tickCount))
            .animator().playDirect(animation, stopExpression, blendOut);
    }

    /**
     * Moves the entity's clips on to this frame and lets go of any that have finished.
     *
     * Whether a clip is still running afterwards, which is whether the entity's model is to be posed.
     */
    public static boolean advance(Entity entity, float partialTick) {
        Map<AddonModels, Animators.Entry> entries = POSED.get(entity.getId());
        if (entries == null) return false;

        for (Animators.Entry entry : entries.values()) entry.advance(partialTick, entity.tickCount, "");
        entries.values().removeIf(entry -> entry.animator().player().playing().isEmpty());
        if (!entries.isEmpty()) return true;

        POSED.remove(entity.getId());
        return false;
    }

    /**
     * Moves the parts of the model drawing this entity to where its clips put the bones.
     *
     * Called right after the model posed itself, and paired with `restore` once that pose is drawn.
     */
    public static void pose(Entity entity, ModelPart root) {
        if (entity == null || root == null || POSED.isEmpty()) return;

        Map<AddonModels, Animators.Entry> entries = POSED.get(entity.getId());
        if (entries == null) return;

        JavaModelParts parts = JavaModelParts.of(root);
        for (Animators.Entry entry : entries.values()) {
            for (Map.Entry<String, BonePose> bone : entry.animator().player().poses().entrySet()) {
                ModelPart part = parts.part(bone.getKey());
                if (part == null) {
                    report("bone " + bone.getKey() + " of a clip played on " + typeOf(entity)
                        + ", which Java's model of it has no part for");
                } else if (!bone.getValue().isIdentity()) {
                    parts.move(part, bone.getValue());
                }
            }
        }
    }

    public static void restore(ModelPart root) {
        JavaModelParts.restore(root);
    }

    //? if <1.21.2 {
    /*private static final Map<Object, ModelPart> ROOTS = new java.util.WeakHashMap<>();

    public static void restore(net.minecraft.client.model.EntityModel<?> model) {
        if (JavaModelParts.anyMoved()) restore(rootOf(model));
    }

    // The part every other part of this model hangs off, or null for a model holding no part. A
    // model before 1.21.2 keeps whichever parts it poses in fields of its own and need not keep its
    // root at all, so any one of those fields leads back up to it.
    public static ModelPart rootOf(net.minecraft.client.model.EntityModel<?> model) {
        return ROOTS.computeIfAbsent(model, JavaModelPoses::findRoot);
    }

    private static ModelPart findRoot(Object model) {
        for (Class<?> type = model.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (field.getType() != ModelPart.class
                    || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    ModelPart part = (ModelPart) field.get(model);
                    if (part == null) continue;
                    while (((PartTree) (Object) part).mcaddon$parent() != null) {
                        part = ((PartTree) (Object) part).mcaddon$parent();
                    }
                    return part;
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    // A field this cannot open is passed over for the next one.
                }
            }
        }
        return null;
    }
    *///?}

    public static void forget(int entityId) {
        POSED.remove(entityId);
    }

    public static void clear() {
        POSED.clear();
    }

    private static String typeOf(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static void report(String what) {
        if (REPORTED.add(what)) AddonEntities.unsupported(what);
    }
}
