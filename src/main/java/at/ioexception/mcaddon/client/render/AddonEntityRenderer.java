package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.List;

//? if >=1.21.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
*///?}

/**
 * Draws an add-on's entity from its Bedrock model.
 *
 * The geometry is baked into Java's model space, which is the space vanilla sets up for a mob: the
 * body rotation applied, the axes flipped, and the origin a block and a half above the feet. Those
 * three steps are reproduced here so a baked model lands where the entity is.
 *
 * Rendering was rewritten in 1.21.2 around a render state extracted before drawing, so the class has
 * two whole forms rather than a branch inside one.
 */
//? if >=1.21.2 {
public class AddonEntityRenderer extends EntityRenderer<AddonEntity, AddonEntityRenderer.State> {
//?} else {
/*public class AddonEntityRenderer extends EntityRenderer<AddonEntity> {
*///?}

    /** How far the model origin sits above the feet, which is what a scale turns about. */
    private static final float MODEL_ORIGIN_BLOCKS = 1.501f;

    public AddonEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    private static String identifierOf(AddonEntity entity) {
        return entity.definition() == null ? "" : entity.definition().identifier();
    }

    //? if >=1.21.2 {
    /** What drawing needs, pulled off the entity while the entity is still safe to touch. */
    public static class State extends EntityRenderState {
        EntityAnimator animator;
        List<AddonModels.Pass> passes = List.of();
        float bodyRotation;
        float scale = 1;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(AddonEntity entity, State state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);

        String identifier = identifierOf(entity);
        AddonClientAssets.Binding binding = AddonClientAssets.of(identifier);
        state.animator = binding == null
            ? null
            : Animators.of(entity, identifier, partialTick, entity.tickCount + partialTick);
        state.passes = state.animator == null ? List.of() : binding.models().resolve(state.animator);
        state.bodyRotation = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        state.scale = state.animator == null ? 1 : state.animator.scale();
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        if (state.animator == null || state.passes.isEmpty()) return;

        poseStack.pushPose();
        applyModelSpace(poseStack, state.bodyRotation, state.scale);

        EntityAnimator animator = state.animator;
        int light = state.lightCoords;
        for (AddonModels.Pass pass : state.passes) {
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(pass.texture()),
                (pose, buffer) -> {
                    PoseStack local = new PoseStack();
                    local.last().set(pose);
                    BedrockModelRenderer.render(pass.geometry(), animator, pass::hides, pass.shift(),
                        local, buffer, light, OverlayTexture.NO_OVERLAY, -1);
                });
        }
        poseStack.popPose();
    }
    //?} else {
    /*@Override
    public Identifier getTextureLocation(AddonEntity entity) {
        return Platform.id("minecraft", "textures/misc/white.png");
    }

    @Override
    public void render(AddonEntity entity, float yaw, float partialTick, PoseStack poseStack,
        MultiBufferSource buffers, int light) {
        super.render(entity, yaw, partialTick, poseStack, buffers, light);

        String identifier = identifierOf(entity);
        AddonClientAssets.Binding binding = AddonClientAssets.of(identifier);
        if (binding == null) return;

        EntityAnimator animator = Animators.of(entity, identifier, partialTick, entity.tickCount + partialTick);
        if (animator == null) return;

        poseStack.pushPose();
        applyModelSpace(poseStack, Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot), animator.scale());
        for (AddonModels.Pass pass : binding.models().resolve(animator)) {
            VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(pass.texture()));
            BedrockModelRenderer.render(pass.geometry(), animator, pass::hides, pass.shift(),
                poseStack, buffer, light, OverlayTexture.NO_OVERLAY, -1);
        }
        poseStack.popPose();
    }
    *///?}

    /**
     * The transform vanilla puts a mob's model into, reproduced so a baked Bedrock model lands in it.
     *
     * A scale turns about the model origin rather than the feet, so it has to be compensated, or the
     * entity floats above or sinks into the hitbox it can still be hit in.
     */
    private static void applyModelSpace(PoseStack poseStack, float bodyRotation, float scale) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - bodyRotation));
        poseStack.scale(-1f, -1f, 1f);
        poseStack.scale(scale, scale, scale);
        poseStack.translate(0, -MODEL_ORIGIN_BLOCKS / scale, 0);
    }
}
