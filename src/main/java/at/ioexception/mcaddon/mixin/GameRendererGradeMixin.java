package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.ColourGrading;
import at.ioexception.mcaddon.client.render.GradePass;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Grades the world's frame once it is drawn, hand and screen effects included, and before the HUD. */
@Mixin(GameRenderer.class)
public abstract class GameRendererGradeMixin {
    //? if >=26.3 {
    /*@Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    *///?} elif >=1.21 {
    @Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    //?} else {
    /*@Inject(method = "render", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V"))
    *///?}
    private void mcaddon$grade(CallbackInfo info) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;

        GradePass.draw(Platform.mainRenderTarget(client), ColourGrading.at(client.level, Platform.cameraPosition()));
    }
}
