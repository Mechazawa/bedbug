package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.PlayerFov;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Works the view out from the field of view `/camera ... fov_set` gave in place of the player's own
 * setting, by scaling what the game worked out by the ratio of the two, so every change the game makes
 * on top of the setting still happens. 26 moved the calculation onto the camera.
 */
//? if >=26 {
@Mixin(net.minecraft.client.Camera.class)
//?} else {
/*@Mixin(net.minecraft.client.renderer.GameRenderer.class)
*///?}
public abstract class FovMixin {
    //? if >=26 {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void mcaddon$packFov(float partialTick, CallbackInfoReturnable<Float> info) {
        float own = Minecraft.getInstance().options.fov().get();
        info.setReturnValue(info.getReturnValueF() * PlayerFov.base(own) / own);
    }
    //?} else {
    /*@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void mcaddon$packFov(net.minecraft.client.Camera camera, float partialTick, boolean useSetting,
        CallbackInfoReturnable<Double> info) {
        if (!useSetting) return;
        double own = Minecraft.getInstance().options.fov().get();
        info.setReturnValue(info.getReturnValueD() * PlayerFov.base((float) own) / own);
    }
    *///?}
}
