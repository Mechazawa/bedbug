package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.MaterialDefinition;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;

//? if >=1.21.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
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
     * Everything the entity has on that an add-on described, each pass hung off the part of the
     * wearer's model its binding named.
     *
     * The wearer is drawn from a Java model wherever this layer runs, so its parts are what a
     * binding finds, and an attachable has none of the wearer's variables to write into.
     */
    private static List<Attachables.Drawn> worn(LivingEntity entity, float partialTick, ModelPart root) {
        if (entity == null || !AddonClientAssets.anyAttachables()) return List.of();

        List<Attachables.Drawn> drawn = Attachables.on(entity, Animators.rootOf(entity.getId()), partialTick);
        if (root == null) return drawn;

        JavaModelParts parts = JavaModelParts.of(root);
        return drawn.stream().map(each -> each.hungFrom(parts)).toList();
    }

    //? if >=1.21.2 {
    /**
     * Leaves out Java's own drawing of every item an attachable draws in its place, as Bedrock does.
     *
     * The head layer's item and skull, the armour and the wings, and the item in each hand are all
     * read from the render state by the layers after this one. This layer is added as
     * `LivingEntityRenderer`'s constructor returns, before any renderer extending it adds its own,
     * so it runs first and the state it clears is the one those layers read.
     */
    private static void hideCovered(LivingEntityRenderState state, List<Attachables.Drawn> worn) {
        worn.stream().map(Attachables.Drawn::slot).distinct().forEach(slot -> {
            switch (slot) {
                case HEAD -> {
                    state.headItem.clear();
                    state.wornHeadType = null;
                    state.wornHeadProfile = null;
                    if (state instanceof HumanoidRenderState humanoid) humanoid.headEquipment = ItemStack.EMPTY;
                }
                case CHEST -> {
                    if (state instanceof HumanoidRenderState humanoid) humanoid.chestEquipment = ItemStack.EMPTY;
                }
                case LEGS -> {
                    if (state instanceof HumanoidRenderState humanoid) humanoid.legsEquipment = ItemStack.EMPTY;
                }
                case FEET -> {
                    if (state instanceof HumanoidRenderState humanoid) humanoid.feetEquipment = ItemStack.EMPTY;
                }
                case MAINHAND, OFFHAND -> {
                    if (state instanceof ArmedEntityRenderState armed) clearHand(armed, slot);
                }
                default -> {
                }
            }
        });
    }

    private static void clearHand(ArmedEntityRenderState state, EquipmentSlot slot) {
        boolean right = (slot == EquipmentSlot.MAINHAND) == (state.mainArm == HumanoidArm.RIGHT);
        if (right) {
            state.rightHandItemState.clear();
            state.rightHandItemStack = ItemStack.EMPTY;
        } else {
            state.leftHandItemState.clear();
            state.leftHandItemStack = ItemStack.EMPTY;
        }
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, S state,
        float yRot, float xRot) {
        LivingEntity entity = RenderStates.of(state);
        if (entity == null) return;

        List<Attachables.Drawn> worn = worn(entity, 0, getParentModel().root());
        if (worn.isEmpty()) return;

        hideCovered(state, worn);
        boolean pushed = intoModelSpace(poseStack, entity, 0);
        for (Attachables.Drawn each : worn) {
            AddonModels.Pass pass = each.pass();
            poseStack.pushPose();
            if (each.anchor() != null) Platform.mulPose(poseStack, each.anchor());
            collector.submitCustomGeometry(poseStack, renderTypeOf(pass),
                (pose, buffer) -> {
                    PoseStack local = new PoseStack();
                    local.last().set(pose);
                    BedrockModelRenderer.render(pass.geometry(), each.animator(), pass.visibility()::hides, pass::draws,
                        pass.shift(), local, buffer, pass.light(light), OverlayTexture.NO_OVERLAY, pass.colour());
                });
            poseStack.popPose();
        }
        if (pushed) poseStack.popPose();
    }
    //?} else {
    /*@Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, T entity,
        float limbSwing, float limbSwingAmount, float partialTick,
        float ageInTicks, float netHeadYaw, float headPitch) {
        List<Attachables.Drawn> worn = worn(entity, partialTick, JavaModelPoses.rootOf(getParentModel()));
        if (worn.isEmpty()) return;

        boolean pushed = intoModelSpace(poseStack, entity, partialTick);
        for (Attachables.Drawn each : worn) {
            AddonModels.Pass pass = each.pass();
            VertexConsumer buffer = buffers.getBuffer(renderTypeOf(pass));
            poseStack.pushPose();
            if (each.anchor() != null) Platform.mulPose(poseStack, each.anchor());
            BedrockModelRenderer.render(pass.geometry(), each.animator(), pass.visibility()::hides, pass::draws,
                pass.shift(), poseStack, buffer, pass.light(light), OverlayTexture.NO_OVERLAY, pass.colour());
            poseStack.popPose();
        }
        if (pushed) poseStack.popPose();
    }
    *///?}
}
