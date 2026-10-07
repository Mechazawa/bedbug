package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import net.minecraft.client.model.geom.ModelPart;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A Java model's parts, found by the name a Bedrock bone gives the same piece.
 *
 * Mojang describes the vanilla mobs twice, once per edition, mostly with the same pieces. Where the
 * two spell a piece differently it is one of two ways: a word joined in camel case on one side and
 * by an underscore on the other, which comparing without case or underscores settles, or a piece
 * Bedrock numbers where Java says which one it is, which `ALIASES` settles.
 */
final class JavaModelParts {
    /**
     * The Bedrock bones Java names otherwise, each against the Java names to try in turn.
     *
     * Bedrock numbers legs from the back and from the right: `leg0` and `leg1` are a biped's two
     * legs or a quadruped's hind pair, `leg2` and `leg3` a quadruped's front pair, and a spider's
     * eight run back to front in pairs. The first name the model has wins, so a spider's `leg2` is
     * its middle hind leg while a cow's is its right front one. The cats write the side as a
     * trailing letter, and `helmet` and `brim` are the villagers' hat.
     */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
        Map.entry("leg0", List.of("right_leg", "right_hind_leg")),
        Map.entry("leg1", List.of("left_leg", "left_hind_leg")),
        Map.entry("leg2", List.of("right_middle_hind_leg", "right_front_leg")),
        Map.entry("leg3", List.of("left_middle_hind_leg", "left_front_leg")),
        Map.entry("leg4", List.of("right_middle_front_leg")),
        Map.entry("leg5", List.of("left_middle_front_leg")),
        Map.entry("leg6", List.of("right_front_leg")),
        Map.entry("leg7", List.of("left_front_leg")),
        Map.entry("backlegr", List.of("right_hind_leg")),
        Map.entry("backlegl", List.of("left_hind_leg")),
        Map.entry("frontlegr", List.of("right_front_leg")),
        Map.entry("frontlegl", List.of("left_front_leg")),
        Map.entry("helmet", List.of("hat")),
        Map.entry("brim", List.of("hat_rim")));

    /** The bone many Bedrock models hang everything else off, which is the Java model's root. */
    private static final String ROOT = "root";

    private static final Map<ModelPart, JavaModelParts> BY_ROOT = new WeakHashMap<>();

    /** The models with a part a clip has moved and not yet put back. */
    private static final Set<JavaModelParts> MOVED = Collections.newSetFromMap(new IdentityHashMap<>());

    private final ModelPart root;
    private final Map<String, ModelPart> byName = new HashMap<>();
    private final List<Saved> saved = new ArrayList<>();

    /** Where a part stood before a clip moved it. */
    private record Saved(ModelPart part, float x, float y, float z, float xRot, float yRot, float zRot,
        float xScale, float yScale, float zScale) {
        static Saved of(ModelPart part) {
            return new Saved(part, part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                part.xScale, part.yScale, part.zScale);
        }

        void restore() {
            part.x = x;
            part.y = y;
            part.z = z;
            part.xRot = xRot;
            part.yRot = yRot;
            part.zRot = zRot;
            part.xScale = xScale;
            part.yScale = yScale;
            part.zScale = zScale;
        }
    }

    private JavaModelParts(ModelPart root) {
        this.root = root;

        // Breadth first, so where two parts share a name the one nearer the root answers to it.
        Deque<ModelPart> pending = new ArrayDeque<>(List.of(root));
        while (!pending.isEmpty()) {
            ((PartTree) (Object) pending.poll()).mcaddon$children().forEach((name, child) -> {
                byName.putIfAbsent(normalise(name), child);
                pending.add(child);
            });
        }
    }

    static JavaModelParts of(ModelPart root) {
        return BY_ROOT.computeIfAbsent(root, JavaModelParts::new);
    }

    /** Null when the model has no part answering to that bone. */
    ModelPart part(String bone) {
        ModelPart exact = byName.get(normalise(bone));
        if (exact != null) return exact;

        for (String alias : ALIASES.getOrDefault(bone.toLowerCase(Locale.ROOT), List.of())) {
            ModelPart part = byName.get(normalise(alias));
            if (part != null) return part;
        }
        return ROOT.equalsIgnoreCase(bone) ? root : null;
    }

    /**
     * Moves a part by what a clip did to the bone of the same name, on top of the model's own pose.
     *
     * The arithmetic is `BedrockModelRenderer`'s, because a Java model part is posed in the same
     * space and in the same order as a baked Bedrock bone: a position whose Y runs the other way,
     * degrees turned to radians with Y negated, and a scale that multiplies.
     */
    void move(ModelPart part, BonePose pose) {
        saved.add(Saved.of(part));
        MOVED.add(this);

        part.x += pose.position.x;
        part.y -= pose.position.y;
        part.z += pose.position.z;
        part.xRot += (float) Math.toRadians(pose.rotation.x);
        part.yRot -= (float) Math.toRadians(pose.rotation.y);
        part.zRot += (float) Math.toRadians(pose.rotation.z);
        part.xScale *= pose.scale.x;
        part.yScale *= pose.scale.y;
        part.zScale *= pose.scale.z;
    }

    /**
     * Puts back every part a clip moved on this root, latest first.
     *
     * One model draws every entity of its kind, so a part left where a clip put it would carry one
     * entity's pose onto the next one drawn with that model.
     */
    static void restore(ModelPart root) {
        if (MOVED.isEmpty()) return;

        JavaModelParts parts = BY_ROOT.get(root);
        if (parts == null || !MOVED.remove(parts)) return;

        for (int index = parts.saved.size() - 1; index >= 0; index--) parts.saved.get(index).restore();
        parts.saved.clear();
    }

    static boolean anyMoved() {
        return !MOVED.isEmpty();
    }

    private static String normalise(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }
}
