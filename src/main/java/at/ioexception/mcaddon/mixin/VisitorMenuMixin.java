package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.Visitors;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * Keeps a visitor out of the inventories an entity holds: a chest minecart, a chest boat and a
 * mount. A block's inventory is refused earlier, with the click on the block, and a merchant's
 * trades stay open to a visitor, as they do in Bedrock.
 */
@Mixin(ServerPlayer.class)
public abstract class VisitorMenuMixin {
    @Inject(method = "openMenu", at = @At("HEAD"), cancellable = true)
    private void mcaddon$visitorOpening(MenuProvider provider, CallbackInfoReturnable<OptionalInt> info) {
        if (provider instanceof ContainerEntity && Visitors.is((ServerPlayer) (Object) this)) {
            info.setReturnValue(OptionalInt.empty());
        }
    }

    //? if >=26 {
    @Inject(method = {"openHorseInventory", "openNautilusInventory"}, at = @At("HEAD"), cancellable = true)
    //?} else {
    /*@Inject(method = "openHorseInventory", at = @At("HEAD"), cancellable = true)
    *///?}
    private void mcaddon$visitorOpeningMount(CallbackInfo info) {
        if (Visitors.is((ServerPlayer) (Object) this)) info.cancel();
    }
}
