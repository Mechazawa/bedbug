package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.BedrockRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves a bed standing, used where it cannot hold a spawn, while Bedrock's `respawnBlocksExplode` is
 * off. Java removes the bed before it explodes, so the use is answered before any of that happens.
 */
@Mixin(net.minecraft.world.level.block.BedBlock.class)
public abstract class RespawnBlockMixin {
    //? if >=1.21 {
    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void mcaddon$withoutExploding(BlockState state, Level level, BlockPos pos,
        net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit,
        CallbackInfoReturnable<InteractionResult> info) {
        if (!level.isClientSide() && !BedrockRules.on(BedrockRules.RESPAWN_BLOCKS_EXPLODE)
            && Platform.bedExplodes(level, pos)) {
            info.setReturnValue(InteractionResult.SUCCESS);
        }
    }
    //?} else {
    /*@Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void mcaddon$withoutExploding(BlockState state, Level level, BlockPos pos,
        net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
        net.minecraft.world.phys.BlockHitResult hit, CallbackInfoReturnable<InteractionResult> info) {
        if (!level.isClientSide() && !BedrockRules.on(BedrockRules.RESPAWN_BLOCKS_EXPLODE)
            && Platform.bedExplodes(level, pos)) {
            info.setReturnValue(InteractionResult.SUCCESS);
        }
    }
    *///?}
}
