package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a pack's script that a lever went over, and who pulled it.
 *
 * 1.21 added the puller and dropped the state the pull answered with. Mixin matches a method by its
 * exact descriptor, so the injector is written twice, once per shape.
 */
@Mixin(LeverBlock.class)
public abstract class LeverBlockMixin {
    //? if >=1.21 {
    @Inject(method = "pull", at = @At("HEAD"))
    private void mcaddon$pulled(BlockState state, Level level, BlockPos position, Player player,
        CallbackInfo info) {
        if (!level.isClientSide()) WorldEvents.leverPulled(level, position, player);
    }
    //?} else {
    /*@Inject(method = "pull", at = @At("HEAD"))
    private void mcaddon$pulled(BlockState state, Level level, BlockPos position,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<BlockState> info) {
        if (!level.isClientSide()) WorldEvents.leverPulled(level, position, null);
    }
    *///?}
}
