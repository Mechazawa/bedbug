package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells a pack's script that a piston started moving, and which way.
 *
 * The game decides a piston is going by sending it a block event, and this is where that event
 * arrives, for the head going out and for it coming back. The method that moves the blocks is not
 * the same moment: a piston with nothing to pull retracts without reaching it, and a sticky one
 * reaches it only on the retraction that takes a block with it.
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    /** The game's own word for the head going out, against the two it uses for it coming back. */
    @Unique
    private static final int MCADDON_EXTENDING = 0;

    @Inject(method = "triggerEvent", at = @At("HEAD"))
    private void mcaddon$moving(BlockState state, Level level, BlockPos position, int event,
        int facing, CallbackInfoReturnable<Boolean> info) {

        if (!level.isClientSide()) WorldEvents.pistonMoved(level, position, event == MCADDON_EXTENDING);
    }
}
