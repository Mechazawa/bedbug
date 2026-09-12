package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.entity.EntityAnimator;
import at.ioexception.mcaddon.bedrock.model.MaterialDefinition;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
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

    /** One drawing pass and the animator whose bones it should be posed by. */
    private record Worn(AddonModels.Pass pass, EntityAnimator animator) {
    }

    /** Everything the entity has on that an add-on described. */
    private static List<Worn> worn(LivingEntity entity, float partialTick) {
        List<Worn> worn = new ArrayList<>();
        if (entity == null) return worn;

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack held = entity.getItemBySlot(slot);
            if (held.isEmpty()) continue;

            String item = BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            AddonClientAssets.Binding binding = AddonClientAssets.attachableFor(item);
            if (binding == null) continue;

            EntityAnimator animator = Animators.of(entity, binding, item, partialTick,
                entity.tickCount + partialTick);
            if (animator == null) continue;

            for (AddonModels.Pass pass : binding.models().resolve(animator)) {
                worn.add(new Worn(pass, animator));
            }
        }
        return worn;
    }

    //? if >=1.21.2 {
    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, S state,
        float yRot, float xRot) {
        LivingEntity entity = RenderStates.of(state);
        if (entity == null) return;

        for (Worn each : worn(entity, 0)) {
            AddonModels.Pass pass = each.pass();
            collector.submitCustomGeometry(poseStack, renderTypeOf(pass),
                (pose, buffer) -> {
                    PoseStack local = new PoseStack();
                    local.last().set(pose);
                    BedrockModelRenderer.render(pass.geometry(), each.animator(), pass::hides,
                        pass.shift(), local, buffer, light, OverlayTexture.NO_OVERLAY, -1);
                });
        }
    }
    //?} else {
    /*@Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int light, T entity,
        float limbSwing, float limbSwingAmount, float partialTick,
        float ageInTicks, float netHeadYaw, float headPitch) {
        for (Worn each : worn(entity, partialTick)) {
            AddonModels.Pass pass = each.pass();
            VertexConsumer buffer = buffers.getBuffer(renderTypeOf(pass));
            BedrockModelRenderer.render(pass.geometry(), each.animator(), pass::hides, pass.shift(),
                poseStack, buffer, light, OverlayTexture.NO_OVERLAY, -1);
        }
    }
    *///?}
}
