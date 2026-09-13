package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells a pack's script who picked something up, which Fabric has no callback for. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void mcaddon$pickedUp(Player player, CallbackInfo info) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (self.level().isClientSide()) return;

        if (WorldEvents.pickupRefused(player, self)) info.cancel();
    }
}
