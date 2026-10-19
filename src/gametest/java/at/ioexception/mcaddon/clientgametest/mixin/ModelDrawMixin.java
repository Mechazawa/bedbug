package at.ioexception.mcaddon.clientgametest.mixin;

import at.ioexception.mcaddon.client.render.Submitted;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands every model the frame draws to `Submitted`, posed as it goes into the buffer. */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelDrawMixin {
    //? if >=26.2 {
    @Inject(method = "prepareModel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
    private void mcaddon$keep(ModelFeatureRenderer.Submit<?> submit, CallbackInfo info) {
        Submitted.drawn(submit.state(), submit.model(), submit.renderType(), submit.lightCoords(),
            submit.overlayCoords(), submit.tintedColor());
    }
    //?} else {
    /*@Inject(method = "renderModel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", ordinal = 0))
    private void mcaddon$keep(net.minecraft.client.renderer.SubmitNodeStorage.ModelSubmit<?> submit,
        net.minecraft.client.renderer.rendertype.RenderType renderType,
        com.mojang.blaze3d.vertex.VertexConsumer buffer, net.minecraft.client.renderer.OutlineBufferSource outlines,
        net.minecraft.client.renderer.MultiBufferSource.BufferSource crumbling, CallbackInfo info) {
        Submitted.drawn(submit.state(), submit.model(), renderType, submit.lightCoords(), submit.overlayCoords(),
            submit.tintedColor());
    }
    *///?}
}
