package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.client.PlayerInput;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves the player facing where they were while the server has taken the camera input away.
 *
 * The mouse movement is still taken up as it arrives, so none of it is saved up to turn the player
 * all at once when the camera input comes back.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @WrapWithCondition(method = "turnPlayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean mcaddon$turnsWithTheCamera(LocalPlayer player, double yaw, double pitch) {
        return PlayerInput.allows(InputPermission.CAMERA);
    }
}
