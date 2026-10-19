package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.PlayerAimAssist;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lays the aim assist target over what the player points at, after every pick the game makes. 26
 * moved the pick from the renderer onto the client.
 */
//? if >=26 {
@Mixin(Minecraft.class)
//?} else {
/*@Mixin(net.minecraft.client.renderer.GameRenderer.class)
*///?}
public abstract class AimAssistPickMixin {
    @Inject(method = "pick(F)V", at = @At("TAIL"))
    private void mcaddon$aimAssist(float partialTicks, CallbackInfo info) {
        PlayerAimAssist.pick(Minecraft.getInstance());
    }
}
