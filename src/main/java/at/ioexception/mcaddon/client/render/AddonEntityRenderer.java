package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.MaterialDefinition;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import org.joml.Vector3f;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;

//? if >=1.21.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
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

    static String identifierOf(AddonEntity entity) {
        return entity.definition() == null ? "" : entity.definition().identifier();
    }

    /**
     * Whether the entity's model is drawn at all.
     *
     * An invisible entity is drawn by nothing in either edition, which is what a potion of
     * invisibility and `minecraft:is_hidden_when_invisible` both ask for.
     * `minecraft:renders_when_invisible` is a pack asking for the opposite, for something whose
     * shape is the point of it.
     */
    private static boolean visible(AddonEntity entity) {
        return !entity.isInvisible() || (entity.definition() != null
            && entity.declares("minecraft:renders_when_invisible"));
    }

    /**
     * Everything drawn on the entity: its own model, then whatever it is wearing or holding.
     *
     * What it is wearing is collected first, because an attachable writes into the entity's own
     * variables and its model has to be resolved after that rather than before.
     */
    private static List<Attachables.Drawn> drawn(AddonEntity entity, AddonClientAssets.Binding binding,
        EntityAnimator animator, float partialTick) {
        List<Attachables.Drawn> worn = Attachables.on(entity, animator, partialTick);

        List<Attachables.Drawn> passes = new ArrayList<>();
        for (AddonModels.Pass pass : binding.models().resolve(animator)) {
            passes.add(new Attachables.Drawn(pass, animator));
        }
        passes.addAll(worn);
        return passes;
    }


    /**
     * The render type a pass draws with, taken from the material its pack named.
     *
     * Without a material the pack meant Bedrock's default for an entity, which is an alpha test with
     * back faces kept.
     */
    private static RenderType renderTypeOf(AddonModels.Pass pass) {
        MaterialDefinition material = pass.material();
        return Platform.renderType(pass.texture(),
            material != null && material.blends(),
            material != null && material.culls(),
            material != null && material.emissive());
    }

    //? if >=1.21.2 {
    /** What drawing needs, pulled off the entity while the entity is still safe to touch. */
    public static class State extends EntityRenderState {
        EntityAnimator animator;
        List<Attachables.Drawn> passes = new ArrayList<>();
        float bodyRotation;
        Vector3f scale = new Vector3f(1, 1, 1);
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
        state.passes = new ArrayList<>();
        if (state.animator == null || !visible(entity)) return;

        state.passes = drawn(entity, binding, state.animator, partialTick);
        state.bodyRotation = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        state.scale = state.animator.scale();
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, poseStack, collector, camera);
        if (state.passes.isEmpty()) return;

        poseStack.pushPose();
        applyModelSpace(poseStack, state.bodyRotation, state.scale);

        int light = state.lightCoords;
        for (Attachables.Drawn each : state.passes) {
            AddonModels.Pass pass = each.pass();
            EntityAnimator animator = each.animator();
            collector.submitCustomGeometry(poseStack, renderTypeOf(pass),
                (pose, buffer) -> {
                    PoseStack local = new PoseStack();
                    local.last().set(pose);
                    BedrockModelRenderer.render(pass.geometry(), animator, pass::hides, pass.shift(),
                        local, buffer, pass.light(light), OverlayTexture.NO_OVERLAY, pass.colour());
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
        if (binding == null || !visible(entity)) return;

        EntityAnimator animator = Animators.of(entity, identifier, partialTick, entity.tickCount + partialTick);
        if (animator == null) return;

        poseStack.pushPose();
        applyModelSpace(poseStack, Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot), animator.scale());
        for (Attachables.Drawn each : drawn(entity, binding, animator, partialTick)) {
            AddonModels.Pass pass = each.pass();
            VertexConsumer buffer = buffers.getBuffer(renderTypeOf(pass));
            BedrockModelRenderer.render(pass.geometry(), each.animator(), pass::hides, pass.shift(),
                poseStack, buffer, pass.light(light), OverlayTexture.NO_OVERLAY, pass.colour());
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
    static void applyModelSpace(PoseStack poseStack, float bodyRotation, Vector3f scale) {
        poseStack.mulPose(Axis.YP.rotationDegrees(180f - bodyRotation));
        poseStack.scale(-1f, -1f, 1f);
        poseStack.scale(scale.x, scale.y, scale.z);
        poseStack.translate(0, -MODEL_ORIGIN_BLOCKS / scale.y, 0);
    }
}
