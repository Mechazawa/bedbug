package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.client.PlayerControl;
import at.ioexception.mcaddon.client.PlayerInput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves the player facing where they were while the server has taken the camera input away, and
 * hands the mouse to the player's control scheme, which may turn the camera or move a pointer with it
 * instead of turning the player.
 *
 * The mouse movement is still taken up as it arrives, so none of it is saved up to turn the player
 * all at once when the camera input comes back.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @WrapOperation(method = "turnPlayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void mcaddon$turnsWithTheCamera(LocalPlayer player, double yaw, double pitch, Operation<Void> turn) {
        if (PlayerInput.allows(InputPermission.CAMERA) && !PlayerControl.takesMouse(yaw, pitch)) {
            turn.call(player, yaw, pitch);
        }
    }
}
