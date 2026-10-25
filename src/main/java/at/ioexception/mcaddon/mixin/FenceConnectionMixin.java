package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.block.AddonBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the sides of `minecraft:connection_rule` `enabled_directions` to a fence or a wall.
 *
 * `connectsTo` is given the direction from the fence or wall to the neighbour, so the side of the
 * neighbour that is reached is the opposite one.
 */
@Mixin({FenceBlock.class, WallBlock.class})
public abstract class FenceConnectionMixin {
    @Inject(method = "connectsTo", at = @At("HEAD"), cancellable = true)
    private void mcaddon$sides(BlockState state, boolean sideSolid, Direction direction,
                               CallbackInfoReturnable<Boolean> connects) {
        if (state.getBlock() instanceof AddonBlock block && !block.connectsOnSide(direction.getOpposite())) {
            connects.setReturnValue(false);
        }
    }
}
