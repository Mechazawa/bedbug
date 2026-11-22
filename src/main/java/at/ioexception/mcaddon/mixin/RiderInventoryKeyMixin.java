package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sends the inventory key of a player riding an add-on mount to the server while the mount's slots
 * open to its rider, so the server opens them in place of the player's own inventory.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class RiderInventoryKeyMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "isServerControlledInventory", at = @At("HEAD"), cancellable = true)
    private void mcaddon$mountStock(CallbackInfoReturnable<Boolean> info) {
        if (minecraft.player != null && minecraft.player.getVehicle() instanceof AddonEntity mount
            && mount.opensToRider()) {
            info.setReturnValue(true);
        }
    }
}
