package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.block.AddonBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies the sides of `minecraft:connection_rule` `enabled_directions` to a pane or a bar.
 *
 * A pane asks `attachsTo` with the neighbour's own `isFaceSturdy` for the side turned towards it,
 * and that call is the only place the side is known. A side the rule leaves out is answered as not
 * sturdy, which refuses the connection for a block that is not itself a pane.
 */
@Mixin(IronBarsBlock.class)
public abstract class BarsConnectionMixin {
    @Redirect(method = {"getStateForPlacement", "updateShape"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/state/BlockState;isFaceSturdy("
            + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/core/Direction;)Z"))
    private boolean mcaddon$sides(BlockState neighbour, BlockGetter level, BlockPos position, Direction side) {
        return (!(neighbour.getBlock() instanceof AddonBlock block) || block.connectsOnSide(side))
            && neighbour.isFaceSturdy(level, position, side);
    }
}
