package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.PlayerRules;
import at.ioexception.mcaddon.server.BedrockRules;
import net.minecraft.client.gui.components.toasts.RecipeToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows no toast for a recipe unlocked while Bedrock's `showRecipeMessages` is off. */
@Mixin(RecipeToast.class)
public abstract class RecipeToastMixin {
    @Inject(method = "addOrUpdate", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$whileShown(CallbackInfo info) {
        if (!PlayerRules.on(BedrockRules.SHOW_RECIPE_MESSAGES, true)) info.cancel();
    }
}
