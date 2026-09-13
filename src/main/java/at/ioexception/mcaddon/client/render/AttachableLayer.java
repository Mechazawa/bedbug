package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.MaterialDefinition;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;

//? if >=1.21.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
*///?}

/**
 * Draws what an entity is wearing or holding, when an add-on describes it.
 *
 * An attachable names itself after the item it belongs to, so the models come from whatever the
 * entity has equipped. It hangs off the vanilla renderer as a layer, which is what keeps this
 * working for players and vanilla mobs rather than only for an add-on's own entities.
 */
//? if >=1.21.2 {
public class AttachableLayer<S extends LivingEntityRenderState, M extends EntityModel<? super S>>
    extends RenderLayer<S, M> {
//?} else {
/*public class AttachableLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
*///?}

    public AttachableLayer(RenderLayerParent<?, ?> parent) {
        super(cast(parent));
    }

    @SuppressWarnings("unchecked")
    private static <A, B> B cast(A value) {
        return (B) value;
    }


    /**
     * Puts the pose into the space a pack's own entity is drawn in, and says whether it did.
     *
     * A vanilla renderer has already flipped and dropped the pose by the time its layers run, so a
     * hat worn by a player lands on the player's head. A pack's own entity has no vanilla model to
     * do that, and its renderer applies the transform around its own drawing instead, which a layer
     * is not inside. Without this the hat is drawn a model's height above the head it belongs on.
     */
    private static boolean intoModelSpace(PoseStack poseStack, LivingEntity entity, float partialTick) {
        if (!(entity instanceof at.ioexception.mcaddon.entity.AddonEntity addon)) return false;

        EntityAnimator animator = Animators.of(addon, AddonEntityRenderer.identifierOf(addon),
            partialTick, addon.tickCount + partialTick);
        poseStack.pushPose();
        AddonEntityRenderer.applyModelSpace(poseStack,
            net.minecraft.util.Mth.rotLerp(partialTick, addon.yBodyRotO, addon.yBodyRot),
            animator == null ? new Vector3f(1, 1, 1) : animator.scale());
        return true;
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

    /**
     * Everything the entity has on that an add-on described.
     *
     * The wearer is drawn from a Java model wherever this layer runs, except for a pack's own
     * entity, which carries an animator an attachable can reach and write into.
     */
    private static List<Attachables.Drawn> worn(LivingEntity entity, float partialTick) {
        if (entity == null || !AddonClientAssets.anyAttachables()) return List.of();

        return Attachables.on(entity, Animators.rootOf(entity.getId()), partialTick);
    }

    //? if >=1.21.2 {
    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, S state,
        float yRot, float xRot) {
        LivingEntity entity = RenderStates.of(state);
        if (entity == null) return;

        List<Attachables.Drawn> worn = worn(entity, 0);
        if (worn.isEmpty()) return;

        boolean pushed = intoModelSpace(poseStack, entity, 0);
        for (Attachables.Drawn each : worn) {
            AddonModels.Pass pass = each.pass();
            collector.submitCustomGeometry(poseStack, renderTypeOf(pass),
                (pose, buffer) -> {
                    PoseStack local = new PoseStack();
                    local.last().set(pose);
                    BedrockModelRenderer.render(pass.geometry(), each.animator(), pass::hides,
                        pass.shift(), local, buffer, pass.light(light), OverlayTexture.NO_OVERLAY, pass.colour());
                });
        }
        if (pushed) poseStack.popPose();
    }
    //?} else {
    /*@Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, T entity,
        float limbSwing, float limbSwingAmount, float partialTick,
        float ageInTicks, float netHeadYaw, float headPitch) {
        List<Attachables.Drawn> worn = worn(entity, partialTick);
        if (worn.isEmpty()) return;

        boolean pushed = intoModelSpace(poseStack, entity, partialTick);
        for (Attachables.Drawn each : worn) {
            AddonModels.Pass pass = each.pass();
            VertexConsumer buffer = buffers.getBuffer(renderTypeOf(pass));
            BedrockModelRenderer.render(pass.geometry(), each.animator(), pass::hides, pass.shift(),
                poseStack, buffer, pass.light(light), OverlayTexture.NO_OVERLAY, pass.colour());
        }
        if (pushed) poseStack.popPose();
    }
    *///?}
}
