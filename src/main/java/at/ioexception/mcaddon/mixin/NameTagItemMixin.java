package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.NameTagItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps a name tag off a pack's entity that `minecraft:nameable` does not let one rename. */
@Mixin(NameTagItem.class)
public abstract class NameTagItemMixin {
    @Inject(method = "interactLivingEntity", at = @At("HEAD"), cancellable = true)
    private void mcaddon$askTheEntity(ItemStack tag, Player player, LivingEntity target, InteractionHand hand,
        CallbackInfoReturnable<InteractionResult> result) {
        if (target instanceof AddonEntity addon && !addon.takesNameTags()) {
            result.setReturnValue(InteractionResult.PASS);
        }
    }
}
