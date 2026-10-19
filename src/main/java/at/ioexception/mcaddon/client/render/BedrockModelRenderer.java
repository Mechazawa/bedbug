package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.BakedBone;
import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.model.BakedQuad;
import at.ioexception.mcaddon.bedrock.model.BakedVertex;
import at.ioexception.mcaddon.bedrock.model.UvShift;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Draws a baked Bedrock model, posed by its animator.
 *
 * Everything below the buffer is version independent: the quads were converted to Java model space
 * at load time, so this only walks the bone tree and hands corners to the buffer.
 */
public final class BedrockModelRenderer {
    private static final float PIXEL = 1f / 16f;

    private BedrockModelRenderer() {
    }

    /**
     * Draws the model from the pose the stack holds.
     *
     * `anchors` gives a frame, in the same space, for each bone hung off another model: such a bone
     * and its children are drawn from that frame in place of its parent's, and left out where its
     * parent would have drawn it. The bone keeps its pivot's offset from its parent's pivot, which for
     * a root is the offset from the model's origin 24 pixels above the feet.
     */
    public static void render(
        BakedGeometry geometry,
        EntityAnimator animator,
        Predicate<String> hidden,
        Predicate<String> drawn,
        Map<String, Matrix4f> anchors,
        UvShift shift,
        PoseStack poseStack,
        VertexConsumer buffer,
        int light,
        int overlay,
        int colour
    ) {
        Walk walk = new Walk(animator, hidden, drawn, anchors, shift, poseStack, buffer, light, overlay, colour);
        geometry.roots().stream().filter(root -> !anchors.containsKey(root.name()))
            .forEach(root -> walk.bone(root, new Quaternionf()));
        anchors.forEach((name, anchor) -> {
            BakedBone bone = geometry.bones().get(name);
            if (bone == null) return;

            poseStack.pushPose();
            Platform.mulPose(poseStack, anchor);
            walk.bone(bone, new Quaternionf());
            poseStack.popPose();
        });
    }

    /** One drawing of a model: everything each bone is drawn with but the bone and the turn above it. */
    private record Walk(EntityAnimator animator, Predicate<String> hidden, Predicate<String> drawn,
        Map<String, Matrix4f> anchors, UvShift shift, PoseStack poseStack, VertexConsumer buffer,
        int light, int overlay, int colour) {

        void bone(BakedBone bone, Quaternionf parentTurn) {
            if (hidden.test(bone.name())) return;

            poseStack.pushPose();
            Quaternionf turn = poseBone(bone, parentTurn, animator, poseStack);

            PoseStack.Pose last = poseStack.last();
            if (drawn.test(bone.name())) {
                for (BakedQuad quad : bone.quads()) emitQuad(quad, shift, last, buffer, light, overlay, colour);
            }

            bone.children().stream().filter(child -> !anchors.containsKey(child.name()))
                .forEach(child -> bone(child, turn));
            poseStack.popPose();
        }
    }

    /**
     * Moves the stack from the bone's parent onto the bone itself, posed for this frame.
     *
     * `parentTurn` is every rotation the bones above this one put on the model, and the answer is
     * the same for this bone. An animation stating `relative_to: {rotation: entity}` gives the bone's
     * rotation in the entity's frame, so the bones above it still carry its pivot along while their
     * turn is taken back off before its own goes on. That is what lets a head keep looking at a
     * target while the body it sits on leans and sways.
     */
    private static Quaternionf poseBone(BakedBone bone, Quaternionf parentTurn, EntityAnimator animator,
        PoseStack poseStack) {
        BonePose pose = animator == null ? null : animator.poseOf(bone.name());

        poseStack.translate(bone.pivot().x * PIXEL, bone.pivot().y * PIXEL, bone.pivot().z * PIXEL);
        if (pose != null) {
            // A position channel is in Bedrock's axes, so its Y runs the other way.
            poseStack.translate(pose.position.x * PIXEL, -pose.position.y * PIXEL, pose.position.z * PIXEL);
        }

        Vector3f rotation = bone.rotation();
        float xRot = rotation.x;
        float yRot = rotation.y;
        float zRot = rotation.z;
        if (pose != null) {
            xRot += (float) Math.toRadians(pose.rotation.x);
            yRot += (float) Math.toRadians(-pose.rotation.y);
            zRot += (float) Math.toRadians(pose.rotation.z);
        }
        Quaternionf own = new Quaternionf().rotationZYX(zRot, yRot, xRot);
        boolean fromEntity = pose != null && pose.rotationRelativeToEntity;
        if (fromEntity) poseStack.mulPose(new Quaternionf(parentTurn).conjugate());
        if (xRot != 0 || yRot != 0 || zRot != 0) poseStack.mulPose(own);
        if (pose != null && !pose.scale.equals(1, 1, 1)) {
            poseStack.scale(pose.scale.x, pose.scale.y, pose.scale.z);
        }
        return fromEntity ? own : new Quaternionf(parentTurn).mul(own);
    }

    /**
     * Where one bone of a posed model sits, in the space the model itself is drawn in.
     *
     * This is what a Bedrock model states a hand or a mounting point with: the bone carries no boxes
     * and exists to be hung off. Null where the model has no bone of that name. Bedrock matches such
     * a name without regard to case, which is why `q.item_slot_to_bone_name` answers `rightitem` for
     * a model whose bone is written `rightItem`.
     */
    public static Matrix4f anchorOf(BakedGeometry geometry, EntityAnimator animator, String bone) {
        PoseStack poseStack = new PoseStack();
        for (BakedBone root : geometry.roots()) {
            Matrix4f found = searchBone(root, new Quaternionf(), animator, bone, poseStack);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Where a locator sits in a posed model, in the space the model itself is drawn in.
     *
     * A locator is written under one bone and moves with it. A name that is a bone rather than a
     * locator answers with the bone's own place. Null where the model has neither.
     */
    public static Vector3f locatorOf(BakedGeometry geometry, EntityAnimator animator, String locator) {
        PoseStack poseStack = new PoseStack();
        for (BakedBone root : geometry.roots()) {
            Vector3f found = searchLocator(root, new Quaternionf(), animator, locator, poseStack);
            if (found != null) return found;
        }
        return null;
    }

    private static Vector3f searchLocator(BakedBone bone, Quaternionf parentTurn, EntityAnimator animator,
        String locator, PoseStack poseStack) {
        poseStack.pushPose();
        Quaternionf turn = poseBone(bone, parentTurn, animator, poseStack);

        Vector3f offset = bone.locators().get(locator);
        if (offset == null && bone.name().equalsIgnoreCase(locator)) offset = new Vector3f();
        Vector3f found = offset == null ? null : poseStack.last().pose()
            .transformPosition(offset.x * PIXEL, offset.y * PIXEL, offset.z * PIXEL, new Vector3f());
        for (BakedBone child : bone.children()) {
            if (found != null) break;
            found = searchLocator(child, turn, animator, locator, poseStack);
        }
        poseStack.popPose();
        return found;
    }

    private static Matrix4f searchBone(BakedBone bone, Quaternionf parentTurn, EntityAnimator animator,
        String name, PoseStack poseStack) {
        poseStack.pushPose();
        Quaternionf turn = poseBone(bone, parentTurn, animator, poseStack);

        Matrix4f found = bone.name().equalsIgnoreCase(name) ? new Matrix4f(poseStack.last().pose()) : null;
        for (BakedBone child : bone.children()) {
            if (found != null) break;
            found = searchBone(child, turn, animator, name, poseStack);
        }
        poseStack.popPose();
        return found;
    }

    private static void emitQuad(
        BakedQuad quad, UvShift shift, PoseStack.Pose pose,
        VertexConsumer buffer, int light, int overlay, int colour
    ) {
        Matrix4f position = pose.pose();
        Vector3f normal = new Matrix3f(pose.normal()).transform(new Vector3f(quad.normal()));

        for (BakedVertex vertex : quad.vertices()) {
            Vector3f point = position.transformPosition(
                vertex.x() * PIXEL, vertex.y() * PIXEL, vertex.z() * PIXEL, new Vector3f());
            addVertex(buffer, point, vertex, shift, normal, light, overlay, colour);
        }
    }

    private static void addVertex(
        VertexConsumer buffer, Vector3f point, BakedVertex vertex, UvShift shift, Vector3f normal,
        int light, int overlay, int colour
    ) {
        float u = shift.mapU(vertex.u());
        float v = shift.mapV(vertex.v());

        //? if >=1.21 {
        buffer.addVertex(point.x, point.y, point.z)
            .setColor(colour)
            .setUv(u, v)
            .setOverlay(overlay)
            .setLight(light)
            .setNormal(normal.x, normal.y, normal.z);
        //?} else {
        /*buffer.vertex(point.x, point.y, point.z)
            .color(colour >> 16 & 0xFF, colour >> 8 & 0xFF, colour & 0xFF, colour >>> 24)
            .uv(u, v)
            .overlayCoords(overlay)
            .uv2(light)
            .normal(normal.x, normal.y, normal.z)
            .endVertex();
        *///?}
    }
}
