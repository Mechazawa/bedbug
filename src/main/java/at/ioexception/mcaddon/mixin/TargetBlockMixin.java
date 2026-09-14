package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells a pack's script that a target block was struck, and how much it is worth.
 *
 * The state handed in is the one the block carried before the hit and the answer is the strength the
 * hit earned, so both halves of the change are here.
 */
@Mixin(TargetBlock.class)
public abstract class TargetBlockMixin {
    @Shadow
    @Final
    private static IntegerProperty OUTPUT_POWER;

    @Inject(method = "updateRedstoneOutput", at = @At("RETURN"))
    private static void mcaddon$hit(LevelAccessor level, BlockState state, BlockHitResult hit,
        Entity source, CallbackInfoReturnable<Integer> info) {

        if (!(level instanceof Level world) || world.isClientSide()) return;

        WorldEvents.targetBlockHit(world, hit.getBlockPos(), source, state.getValue(OUTPUT_POWER),
            info.getReturnValue());
    }
}
