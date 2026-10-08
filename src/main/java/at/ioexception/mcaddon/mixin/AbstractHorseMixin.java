package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Asks a pack's script before a horse, a donkey, a mule or a llama is tamed, and tells it afterwards.
 *
 * 26 moved the horses into a package of their own.
 */
//? if >=26 {
@Mixin(net.minecraft.world.entity.animal.equine.AbstractHorse.class)
//?} else {
/*@Mixin(net.minecraft.world.entity.animal.horse.AbstractHorse.class)
*///?}
public abstract class AbstractHorseMixin {
    @Inject(method = "tameWithName", at = @At("HEAD"), cancellable = true)
    private void mcaddon$askFirst(Player player, CallbackInfoReturnable<Boolean> info) {
        if (WorldEvents.tamingRefused((Entity) (Object) this, player)) info.setReturnValue(false);
    }

    @Inject(method = "tameWithName", at = @At("RETURN"))
    private void mcaddon$tamed(Player player, CallbackInfoReturnable<Boolean> info) {
        if (info.getReturnValueZ()) WorldEvents.tamed((Entity) (Object) this, player);
    }
}
