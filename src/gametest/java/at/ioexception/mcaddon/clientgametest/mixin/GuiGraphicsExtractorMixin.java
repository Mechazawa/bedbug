package at.ioexception.mcaddon.clientgametest.mixin;

import at.ioexception.mcaddon.clientgametest.Drawn;
//? if >=26.3 {
/*import com.mojang.renderpearl.api.pipeline.RenderPipeline;
*///?} else {
import com.mojang.blaze3d.pipeline.RenderPipeline;
//?}
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Writes down every sprite the heads-up display asks to draw at its own size, which is the call the
 * crosshair, the hotbar, each heart, each piece of food and the experience bar are drawn with, every
 * rectangle filled across the whole screen, every line of text and every mob drawn into the display.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
    //? if >=26.3 {
    /*@Inject(method = "blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;"
        + "Lnet/minecraft/resources/Identifier;IIII)V", at = @At("HEAD"))
    *///?} else {
    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
        + "Lnet/minecraft/resources/Identifier;IIII)V", at = @At("HEAD"))
    //?}
    private void mcaddon$recordSprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int width, int height,
        CallbackInfo info) {
        Drawn.record(sprite);
    }

    @Inject(method = "fill(IIIII)V", at = @At("HEAD"))
    private void mcaddon$recordFill(int left, int top, int right, int bottom, int argb, CallbackInfo info) {
        GuiGraphicsExtractor graphics = (GuiGraphicsExtractor) (Object) this;
        if (left <= 0 && top <= 0 && right >= graphics.guiWidth() && bottom >= graphics.guiHeight()) {
            Drawn.recordScreenFill(argb);
        }
    }

    @Inject(method = "entity", at = @At("HEAD"))
    private void mcaddon$recordEntity(CallbackInfo info) {
        Drawn.recordEntity();
    }

    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"))
    private void mcaddon$recordText(net.minecraft.client.gui.Font font, String line, int x, int y, int argb,
        boolean shadow, CallbackInfo info) {
        Drawn.recordText(line);
    }
}
