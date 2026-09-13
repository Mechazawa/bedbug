package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonItems;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps out of the off hand what `minecraft:allow_off_hand` does not let in.
 *
 * Java's own off-hand slot takes anything and states no rule of its own, so the slot is recognised
 * by the inventory it belongs to and the place it holds in it. Every way a screen puts a stack
 * somewhere asks this first: a click, a drag, a shift click and a hotbar swap alike.
 */
@Mixin(Slot.class)
public abstract class SlotMixin {
    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void mcaddon$offHand(ItemStack stack, CallbackInfoReturnable<Boolean> allowed) {
        Slot self = (Slot) (Object) this;
        if (!(self.container instanceof Inventory)) return;
        if (self.getContainerSlot() != Inventory.SLOT_OFFHAND) return;

        if (AddonItems.refusedOffHand(stack)) allowed.setReturnValue(false);
    }
}
