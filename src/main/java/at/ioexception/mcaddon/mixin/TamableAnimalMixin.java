package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Asks a pack's script before a wolf, a cat or a parrot is tamed, and tells it afterwards. */
@Mixin(TamableAnimal.class)
public abstract class TamableAnimalMixin {
    @Inject(method = "tame", at = @At("HEAD"), cancellable = true)
    private void mcaddon$askFirst(Player player, CallbackInfo info) {
        if (WorldEvents.tamingRefused((TamableAnimal) (Object) this, player)) info.cancel();
    }

    @Inject(method = "tame", at = @At("RETURN"))
    private void mcaddon$tamed(Player player, CallbackInfo info) {
        WorldEvents.tamed((TamableAnimal) (Object) this, player);
    }
}
