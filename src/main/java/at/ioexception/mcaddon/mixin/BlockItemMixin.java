package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WebSockets;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells a player's WebSocket server that the player placed a block, with the stack it came from as
 * it was before the block left it.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("HEAD"))
    private void mcaddon$placing(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> info,
        @Share("held") LocalRef<ItemStack> held) {
        held.set(context.getItemInHand().copy());
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void mcaddon$placed(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> info,
        @Share("held") LocalRef<ItemStack> held) {
        if (info.getReturnValue().consumesAction() && context.getPlayer() instanceof ServerPlayer player) {
            WebSockets.placed(player, context.getLevel().getBlockState(context.getClickedPos()), held.get());
        }
    }
}
