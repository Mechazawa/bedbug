package at.ioexception.mcaddon.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Poses a model with the Bedrock clip playing on its entity where the model is finally drawn.
 *
 * From 1.21.9 an entity's body is submitted with its render state and posed again by
 * `setupAnim` only once the frame's models are drawn, which undoes anything done to its parts
 * before then. This is that moment, so the clip's pose goes on after it and comes off once the
 * model is in the buffer. Before 1.21.9 there is no such class, and `LivingEntityRendererMixin`
 * poses the model in `render` instead.
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.renderer.feature.ModelFeatureRenderer")
public abstract class ModelFeatureRendererMixin {
    //? if >=26.2 {
    @org.spongepowered.asm.mixin.injection.Inject(method = "prepareModel",
        at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V",
            shift = org.spongepowered.asm.mixin.injection.At.Shift.AFTER))
    private void mcaddon$pose(net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> submit,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        at.ioexception.mcaddon.client.render.JavaModelPoses.pose(
            at.ioexception.mcaddon.client.render.RenderStates.of(submit.state()), submit.model().root());
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "prepareModel",
        at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void mcaddon$unpose(net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> submit,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        at.ioexception.mcaddon.client.render.JavaModelPoses.restore(submit.model().root());
    }
    //?} else if >=26 {
    /*@org.spongepowered.asm.mixin.injection.Inject(method = "renderModel",
        at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V",
            shift = org.spongepowered.asm.mixin.injection.At.Shift.AFTER))
    private void mcaddon$pose(net.minecraft.client.renderer.SubmitNodeStorage.ModelSubmit<?> submit,
        net.minecraft.client.renderer.rendertype.RenderType renderType,
        com.mojang.blaze3d.vertex.VertexConsumer buffer, net.minecraft.client.renderer.OutlineBufferSource outlines,
        net.minecraft.client.renderer.MultiBufferSource.BufferSource crumbling,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        at.ioexception.mcaddon.client.render.JavaModelPoses.pose(
            at.ioexception.mcaddon.client.render.RenderStates.of(submit.state()), submit.model().root());
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "renderModel",
        at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private void mcaddon$unpose(net.minecraft.client.renderer.SubmitNodeStorage.ModelSubmit<?> submit,
        net.minecraft.client.renderer.rendertype.RenderType renderType,
        com.mojang.blaze3d.vertex.VertexConsumer buffer, net.minecraft.client.renderer.OutlineBufferSource outlines,
        net.minecraft.client.renderer.MultiBufferSource.BufferSource crumbling,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
        at.ioexception.mcaddon.client.render.JavaModelPoses.restore(submit.model().root());
    }
    *///?}
}
