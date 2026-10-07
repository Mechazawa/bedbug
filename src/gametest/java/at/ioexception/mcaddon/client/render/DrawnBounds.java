package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;

/**
 * The box around every vertex the renderer draws for an entity, relative to the entity's feet.
 *
 * Built from the same render state and the same model space the renderer submits, with a buffer
 * that keeps the positions instead of drawing them, so a test can ask where a model landed.
 */
public final class DrawnBounds {
    private DrawnBounds() {
    }

    /** Null when the renderer would draw nothing for the entity. */
    public static AABB of(AddonEntity entity) {
        AddonEntityRenderer renderer = (AddonEntityRenderer) Minecraft.getInstance()
            .getEntityRenderDispatcher().getRenderer(entity);
        AddonEntityRenderer.State state = renderer.createRenderState();
        renderer.extractRenderState(entity, state, 0f);
        if (state.drawing.passes().isEmpty()) return null;

        Recording recording = new Recording();
        PoseStack poseStack = new PoseStack();
        AddonEntityRenderer.applyModelSpace(poseStack, state.bodyRotation, state.scale);
        for (Attachables.Drawn each : state.drawing.passes()) {
            AddonModels.Pass pass = each.pass();
            poseStack.pushPose();
            if (each.anchor() != null) Platform.mulPose(poseStack, each.anchor());
            BedrockModelRenderer.render(pass.geometry(), each.animator(), pass.visibility()::hides,
                pass.shift(), poseStack, recording, 0, OverlayTexture.NO_OVERLAY, pass.colour());
            poseStack.popPose();
        }
        return recording.box();
    }

    private static final class Recording implements VertexConsumer {
        private double minX = Double.MAX_VALUE;
        private double minY = Double.MAX_VALUE;
        private double minZ = Double.MAX_VALUE;
        private double maxX = -Double.MAX_VALUE;
        private double maxY = -Double.MAX_VALUE;
        private double maxZ = -Double.MAX_VALUE;

        AABB box() {
            return minX > maxX ? null : new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int colour) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
