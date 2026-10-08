package at.ioexception.mcaddon.clientgametest.mixin;

import at.ioexception.mcaddon.clientgametest.Drawn;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Writes down every sprite the heads-up display asks to draw at its own size, which is the call the
 * crosshair, the hotbar, each heart, each piece of food and the experience bar are drawn with.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
        + "Lnet/minecraft/resources/Identifier;IIII)V", at = @At("HEAD"))
    private void mcaddon$recordSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height,
        CallbackInfo info) {
        Drawn.record(sprite);
    }
}
