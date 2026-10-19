package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.PackHudText;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands every title, subtitle and action bar the client is sent to the packs' `hud_screen.json`, and
 * leaves Java's own drawing of one out where a pack hides Bedrock's.
 *
 * A hidden piece is taken off the display rather than left for the next frame to skip, so a title
 * already showing goes the moment the pack hides the one replacing it, which is what Bedrock's
 * control going invisible does. The class is `Gui` up to 26.1.2 and `Hud` from 26.2, and clearing the
 * titles is `clearTitles` from 26 and `clear` before.
 */
//? if >=26.2 {
@Mixin(net.minecraft.client.gui.Hud.class)
//?} else {
/*@Mixin(net.minecraft.client.gui.Gui.class)
*///?}
public abstract class HudTextMixin {
    @Shadow
    private Component title;

    @Shadow
    private Component subtitle;

    @Shadow
    private int overlayMessageTime;

    @Inject(method = "setTitle", at = @At("HEAD"), cancellable = true)
    private void mcaddon$readTitle(Component text, CallbackInfo info) {
        if (!PackHudText.title(text.getString())) return;
        title = null;
        info.cancel();
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), cancellable = true)
    private void mcaddon$readSubtitle(Component text, CallbackInfo info) {
        if (!PackHudText.subtitle(text.getString())) return;
        subtitle = null;
        info.cancel();
    }

    @Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void mcaddon$readActionbar(Component text, boolean animate, CallbackInfo info) {
        if (!PackHudText.actionbar(text.getString())) return;
        overlayMessageTime = 0;
        info.cancel();
    }

    //? if >=26 {
    @Inject(method = "clearTitles", at = @At("HEAD"))
    //?} else {
    /*@Inject(method = "clear", at = @At("HEAD"))
    *///?}
    private void mcaddon$clearTitles(CallbackInfo info) {
        PackHudText.clearTitles();
    }
}
