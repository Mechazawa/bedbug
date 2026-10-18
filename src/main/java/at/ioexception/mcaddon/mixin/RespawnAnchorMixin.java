package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.BedrockRules;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Leaves a charged respawn anchor standing, used where it cannot hold a spawn, while Bedrock's
 * `respawnBlocksExplode` is off: the use is answered at the moment Java would blow it up.
 */
@Mixin(net.minecraft.world.level.block.RespawnAnchorBlock.class)
public abstract class RespawnAnchorMixin {
    //? if >=1.21 {
    @Inject(method = "useWithoutItem", cancellable = true,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/RespawnAnchorBlock;explode"))
    //?} else {
    /*@Inject(method = "use", cancellable = true,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/RespawnAnchorBlock;explode"))
    *///?}
    private void mcaddon$withoutExploding(CallbackInfoReturnable<InteractionResult> info) {
        if (!BedrockRules.on(BedrockRules.RESPAWN_BLOCKS_EXPLODE)) info.setReturnValue(InteractionResult.SUCCESS);
    }
}
