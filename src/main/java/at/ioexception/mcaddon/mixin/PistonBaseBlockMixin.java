package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells a pack's script that a piston started moving, and which way.
 *
 * This is the method the game reaches once it has decided the piston is going, which is the moment
 * Bedrock reports rather than the block event that carries it.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    @Inject(method = "moveBlocks", at = @At("HEAD"))
    private void mcaddon$moving(Level level, BlockPos position, Direction facing, boolean extending,
        CallbackInfoReturnable<Boolean> info) {

        if (!level.isClientSide()) WorldEvents.pistonMoved(level, position, extending);
    }
}
