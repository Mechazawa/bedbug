package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.BakedBone;
import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.model.BakedQuad;
import at.ioexception.mcaddon.bedrock.model.BakedVertex;
import at.ioexception.mcaddon.bedrock.model.UvShift;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

    public static void render(
        BakedGeometry geometry,
        EntityAnimator animator,
        Predicate<String> hidden,
        UvShift shift,
        PoseStack poseStack,
        VertexConsumer buffer,
        int light,
        int overlay,
        int colour
    ) {
        for (BakedBone bone : geometry.roots()) {
            renderBone(bone, animator, hidden, shift, poseStack, buffer, light, overlay, colour);
        }
    }

    private static void renderBone(
        BakedBone bone,
        EntityAnimator animator,
        Predicate<String> hidden,
        UvShift shift,
        PoseStack poseStack,
        VertexConsumer buffer,
        int light,
        int overlay,
        int colour
    ) {
        if (hidden.test(bone.name())) return;

        BonePose pose = animator == null ? null : animator.poseOf(bone.name());

        poseStack.pushPose();
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
        if (xRot != 0 || yRot != 0 || zRot != 0) {
            poseStack.mulPose(new Quaternionf().rotationZYX(zRot, yRot, xRot));
        }
        if (pose != null && !pose.scale.equals(1, 1, 1)) {
            poseStack.scale(pose.scale.x, pose.scale.y, pose.scale.z);
        }

        PoseStack.Pose last = poseStack.last();
        for (BakedQuad quad : bone.quads()) emitQuad(quad, shift, last, buffer, light, overlay, colour);

        for (BakedBone child : bone.children()) {
            renderBone(child, animator, hidden, shift, poseStack, buffer, light, overlay, colour);
        }
        poseStack.popPose();
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
        float u = vertex.u() + shift.u();
        float v = vertex.v() + shift.v();

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
