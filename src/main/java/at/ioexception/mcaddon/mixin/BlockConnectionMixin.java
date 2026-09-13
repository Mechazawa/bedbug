package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.block.AddonBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers `minecraft:connection_rule` for a pack's own block a fence, wall or pane stands beside.
 *
 * Java keeps one list of blocks nothing reaches across to, and a fence, a wall and a pane all
 * consult it before they look at the face turned towards them. A block the pack said accepts no
 * connections joins that list for as long as the pack is loaded.
 */
@Mixin(Block.class)
public abstract class BlockConnectionMixin {
    @Inject(method = "isExceptionForConnection", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$refused(BlockState state, CallbackInfoReturnable<Boolean> exception) {
        if (state.getBlock() instanceof AddonBlock block && block.refusesConnections()) {
            exception.setReturnValue(true);
        }
    }
}
