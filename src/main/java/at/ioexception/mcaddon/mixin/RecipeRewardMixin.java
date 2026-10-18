package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.BedrockRules;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.advancements.AdvancementRewards;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Holds back the recipes an advancement hands out while Bedrock's `recipesUnlock` is off.
 *
 * Java unlocks a recipe as a player collects what it takes through an advancement per recipe whose
 * reward is the recipe, which is what the rule stops; the advancement's other rewards still come.
 */
@Mixin(AdvancementRewards.class)
public abstract class RecipeRewardMixin {
    @WrapWithCondition(method = "grant",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;awardRecipesByKey"))
    //? if >=1.21 {
    private boolean mcaddon$whileRecipesUnlock(net.minecraft.server.level.ServerPlayer player,
        java.util.List<?> recipes) {
        return BedrockRules.on(BedrockRules.RECIPES_UNLOCK);
    }
    //?} else {
    /*private boolean mcaddon$whileRecipesUnlock(net.minecraft.server.level.ServerPlayer player,
        net.minecraft.resources.ResourceLocation[] recipes) {
        return BedrockRules.on(BedrockRules.RECIPES_UNLOCK);
    }
    *///?}
}
