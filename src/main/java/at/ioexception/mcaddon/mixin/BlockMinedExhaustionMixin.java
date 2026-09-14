package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition.Action;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * What breaking a block costs the food bar, at the price the pack put on mining.
 *
 * `Block.playerDestroy` awards the statistic and charges a literal 0.005 beside it, for every block
 * a player breaks, which is the one place Java spends the mining price.
 */
@Mixin(Block.class)
public abstract class BlockMinedExhaustionMixin {
    @ModifyArg(method = "playerDestroy",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float mcaddon$miningCost(float amount) {
        return PlayerDefinition.cost(Action.MINE, amount);
    }
}
