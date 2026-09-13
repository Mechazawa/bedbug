package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.block.AddonBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers `minecraft:chest_obstruction` for a pack's own block standing over a chest.
 *
 * Java asks the block above a chest whether it conducts redstone, and this mod sets that from
 * `minecraft:redstone_conductivity`, so the two components would otherwise share one answer. The
 * position handed in is the chest's, and the block being asked about is the one over it.
 */
@Mixin(ChestBlock.class)
public abstract class ChestBlockMixin {
    @Inject(method = "isBlockedChestByBlock", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$obstruction(BlockGetter level, BlockPos chest,
        CallbackInfoReturnable<Boolean> blocked) {
        BlockPos above = chest.above();
        BlockState state = level.getBlockState(above);
        if (state.getBlock() instanceof AddonBlock block) {
            blocked.setReturnValue(block.obstructsChest(state, level, above));
        }
    }
}
