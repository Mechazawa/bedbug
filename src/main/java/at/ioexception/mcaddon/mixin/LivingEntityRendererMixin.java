package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.AddonClientAssets;
import at.ioexception.mcaddon.client.render.AttachableLayer;
import at.ioexception.mcaddon.client.render.JavaModelPoses;
import at.ioexception.mcaddon.client.render.RenderStates;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every living entity a layer that draws what an add-on says it is wearing, records which
 * entity a render state came from so that layer can find it, and poses the model with whatever
 * Bedrock clip is playing on the entity.
 *
 * Done here rather than through Fabric's feature renderer callback, which 26 removed.
 *
 * `extractRenderState` only exists from 1.21.2, and Mixin checks the exact parameter types, so the
 * injectors are written out twice. From 1.21.2 the clips advance as the state is extracted, the
 * model is posed for the layers right after `submit` poses it, and `ModelFeatureRendererMixin`
 * poses it again where the body is drawn. Before that `render` draws the body and the layers in
 * one call, handed the entity itself.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Shadow
    protected EntityModel<?> model;

    @Shadow
    protected abstract boolean addLayer(RenderLayer layer);

    @Inject(method = "<init>", at = @At("RETURN"))
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mcaddon$addAttachableLayer(CallbackInfo info) {
        addLayer(new AttachableLayer((net.minecraft.client.renderer.entity.RenderLayerParent) this));
    }

    //? if >=1.21.2 {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void mcaddon$rememberEntity(LivingEntity entity,
        net.minecraft.client.renderer.entity.state.LivingEntityRenderState state, float partialTick,
        CallbackInfo info) {
        boolean posed = JavaModelPoses.advance(entity, partialTick);
        if (posed || AddonClientAssets.anyAttachables()) RenderStates.remember(state, entity);
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
        + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V",
            shift = At.Shift.AFTER))
    private void mcaddon$poseForLayers(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state,
        com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
        net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo info) {
        JavaModelPoses.pose(RenderStates.of(state), model.root());
    }

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
        + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"))
    private void mcaddon$unposeAfterLayers(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state,
        com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector,
        net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo info) {
        JavaModelPoses.restore(model.root());
    }
    //?} else {
    /*@Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V",
            shift = At.Shift.AFTER))
    private void mcaddon$pose(LivingEntity entity, float yaw, float partialTick,
        com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffers,
        int light, CallbackInfo info) {
        if (JavaModelPoses.advance(entity, partialTick)) JavaModelPoses.pose(entity, JavaModelPoses.rootOf(model));
    }

    @Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V", at = @At("RETURN"))
    private void mcaddon$unpose(LivingEntity entity, float yaw, float partialTick,
        com.mojang.blaze3d.vertex.PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffers,
        int light, CallbackInfo info) {
        JavaModelPoses.restore(model);
    }
    *///?}
}
