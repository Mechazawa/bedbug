package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

    /** The parts a clip turns in the entity's frame, each with the turn it is to have in that frame. */
    private final Map<ModelPart, Quaternionf> inEntityFrame = new IdentityHashMap<>();

    /** Where a part stood before a clip moved it. */
    private record Saved(ModelPart part, float x, float y, float z, float xRot, float yRot, float zRot,
        float xScale, float yScale, float zScale) {
        static Saved of(ModelPart part) {
            return new Saved(part, part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
                part.xScale, part.yScale, part.zScale);
        }

        /** The transform the part stood at, the way `ModelPart.translateAndRotate` builds it. */
        Matrix4f matrix() {
            return new Matrix4f().translate(x, y, z).rotateZYX(zRot, yRot, xRot).scale(xScale, yScale, zScale);
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
     *
     * A pose whose rotation is relative to the entity keeps the turn the part has here for
     * `keepEntityFrame` to hold once every other part has moved.
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
        if (pose.rotationRelativeToEntity) {
            inEntityFrame.put(part, new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
        }
    }

    /**
     * Turns each part a clip gave a rotation relative to the entity by that rotation in the entity's
     * frame, the way `BedrockModelRenderer.poseBone` turns such a bone.
     *
     * The parts above it, and the body that carries it, still carry its pivot along; their turn is
     * taken off its own, so a head keeps looking where the clip says while the body under it leans.
     * A part nearer the root is settled first, because the parts under it take its turn.
     */
    void keepEntityFrame() {
        if (inEntityFrame.isEmpty()) return;

        inEntityFrame.entrySet().stream()
            .map(entry -> Map.entry(pathTo(root, entry.getKey()), entry.getValue()))
            .filter(entry -> !entry.getKey().isEmpty())
            .sorted(java.util.Comparator.comparingInt(entry -> entry.getKey().size()))
            .forEach(entry -> turnInEntityFrame(entry.getKey(), entry.getValue()));
        inEntityFrame.clear();
    }

    private void turnInEntityFrame(List<ModelPart> path, Quaternionf wanted) {
        Quaternionf above = new Quaternionf();
        path.subList(0, path.size() - 1).forEach(part ->
            above.mul(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot)));

        ModelPart part = path.get(path.size() - 1);
        saved.add(Saved.of(part));
        Vector3f turn = above.conjugate().mul(wanted).getEulerAnglesZYX(new Vector3f());
        part.xRot = turn.x;
        part.yRot = turn.y;
        part.zRot = turn.z;
    }

    /**
     * Moves every part Java draws beside `body` by what the clips did to the body.
     *
     * Bedrock's own model of some mobs hangs every other bone off the body, where Java's model
     * draws the same pieces beside it, so a clip shrinking or turning the body moves the whole mob
     * in Bedrock and only the torso in Java. Each part outside the body that does not lead to it is
     * moved the way the body moved, about the body's own pivot, which is the pivot the body itself
     * was turned about, so whatever it carries stays where it sat on it.
     */
    void carryWith(ModelPart body) {
        Saved rest = saved.stream().filter(entry -> entry.part() == body).findFirst().orElse(null);
        if (rest == null) return;

        Matrix4f before = rest.matrix();
        if (before.determinant() == 0f) return;

        Matrix4f carry = Saved.of(body).matrix().mul(before.invert());
        List<ModelPart> path = pathTo(root, body);
        for (int index = 0; index + 1 < path.size(); index++) {
            ModelPart onPath = path.get(index + 1);
            for (ModelPart beside : ((PartTree) (Object) path.get(index)).mcaddon$children().values()) {
                if (beside != onPath) carry(beside, carry);
            }
        }
    }

    /** The parts from `from` down to `to`, both included, or nothing where `to` is not under it. */
    private static List<ModelPart> pathTo(ModelPart from, ModelPart to) {
        if (from == to) return new ArrayList<>(List.of(from));

        for (ModelPart child : ((PartTree) (Object) from).mcaddon$children().values()) {
            List<ModelPart> below = pathTo(child, to);
            if (!below.isEmpty()) {
                below.add(0, from);
                return below;
            }
        }
        return new ArrayList<>();
    }

    /** A part shrunk to nothing has no direction left, so it keeps the turn it had. */
    private static final float NOTHING = 1e-6f;

    private void carry(ModelPart part, Matrix4f carry) {
        Saved own = Saved.of(part);
        saved.add(own);
        MOVED.add(this);

        Matrix4f moved = new Matrix4f(carry).mul(own.matrix());
        Vector3f at = moved.getTranslation(new Vector3f());
        Vector3f scale = moved.getScale(new Vector3f());
        part.x = at.x;
        part.y = at.y;
        part.z = at.z;
        part.xScale = scale.x;
        part.yScale = scale.y;
        part.zScale = scale.z;
        if (scale.x < NOTHING || scale.y < NOTHING || scale.z < NOTHING) return;

        Vector3f turn = moved.get3x3(new Matrix3f()).scale(1 / scale.x, 1 / scale.y, 1 / scale.z)
            .getUnnormalizedRotation(new Quaternionf()).getEulerAnglesZYX(new Vector3f());
        part.xRot = turn.x;
        part.yRot = turn.y;
        part.zRot = turn.z;
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
