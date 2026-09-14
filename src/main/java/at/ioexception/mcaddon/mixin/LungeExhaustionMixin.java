package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition.Action;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * What the lunge enchantment costs the food bar, at the price the pack put on it.
 *
 * `ApplyExhaustion` is the enchantment effect Java charges through, and `minecraft:lunge` is the one
 * enchantment that uses it, charging 4 per level. Bedrock states its price the same way, so scaling
 * keeps the per-level shape. Before 26 there is neither the effect nor the enchantment, which is why
 * the older body of this mixin has nothing to inject into.
 */
//? if >=26 {
@Mixin(net.minecraft.world.item.enchantment.effects.ApplyExhaustion.class)
public abstract class LungeExhaustionMixin {
    @ModifyArg(method = "apply",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float mcaddon$lungeCost(float amount) {
        return PlayerDefinition.cost(Action.LUNGE, amount);
    }
}
//?} else {
/*@Mixin(net.minecraft.world.entity.player.Player.class)
public abstract class LungeExhaustionMixin {
}
*///?}
