package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition.Action;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * What a swing and a wound cost the food bar, at the price the pack put on each.
 *
 * Java pushes a literal 0.1 before the exhaustion call in `attack`, and takes the damage price off
 * the damage type through `DamageSource.getFoodExhaustion`. Both are scaled rather than replaced, so
 * a damage type Java charges nothing for goes on charging nothing.
 */
@Mixin(Player.class)
public abstract class PlayerAttackExhaustionMixin {
    @ModifyArg(method = "attack",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float mcaddon$attackCost(float amount) {
        return PlayerDefinition.cost(Action.ATTACK, amount);
    }

    //? if >=26 {
    @ModifyArg(method = "stabAttack",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float mcaddon$stabCost(float amount) {
        return PlayerDefinition.cost(Action.ATTACK, amount);
    }
    //?}

    @ModifyArg(method = "actuallyHurt",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float mcaddon$damageCost(float amount) {
        return PlayerDefinition.cost(Action.DAMAGE, amount);
    }
}
