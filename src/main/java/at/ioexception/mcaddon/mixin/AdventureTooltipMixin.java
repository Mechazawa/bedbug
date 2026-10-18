package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.PlayerRules;
import at.ioexception.mcaddon.server.BedrockRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.21 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Leaves out of an item's tooltip the blocks it may break and be placed on while Bedrock's `showTags`
 * is off. From 1.20.5 each list is a component that writes its own lines; before that the item asks
 * whether each part of its tooltip is hidden, and those two parts answer yes.
 */
//? if >=1.21 {
@Mixin(net.minecraft.world.item.AdventureModePredicate.class)
//?} else {
/*@Mixin(net.minecraft.world.item.ItemStack.class)
*///?}
public abstract class AdventureTooltipMixin {
    //? if >=1.21 {
    @Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
    private void mcaddon$whileShown(CallbackInfo info) {
        if (!PlayerRules.on(BedrockRules.SHOW_TAGS, true)) info.cancel();
    }
    //?} else {
    /*@Inject(method = "shouldShowInTooltip", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$whileShown(int hidden, net.minecraft.world.item.ItemStack.TooltipPart part,
        CallbackInfoReturnable<Boolean> info) {
        if ((part == net.minecraft.world.item.ItemStack.TooltipPart.CAN_DESTROY
            || part == net.minecraft.world.item.ItemStack.TooltipPart.CAN_PLACE)
            && !PlayerRules.on(BedrockRules.SHOW_TAGS, true)) {
            info.setReturnValue(false);
        }
    }
    *///?}
}
