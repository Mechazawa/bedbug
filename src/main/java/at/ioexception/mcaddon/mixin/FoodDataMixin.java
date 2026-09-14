package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition.Action;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * What healing off a full food bar costs, at the price the pack put on it.
 *
 * `FoodData.tick` is the only place Java charges for regeneration, and it charges twice: the
 * saturated healing spends whatever saturation it burned, up to 6, and the slower healing spends a
 * literal 6. Scaling keeps that shape, so the saturated charge stays proportional to what it healed.
 */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @ModifyArg(method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;addExhaustion(F)V"))
    private float mcaddon$healingCost(float amount) {
        return PlayerDefinition.cost(Action.HEAL, amount);
    }
}
