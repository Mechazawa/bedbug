package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.hud.HudElement;
import at.ioexception.mcaddon.client.render.PlayerHud;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves undrawn the pieces of the heads-up display `/hud` hid for this player.
 *
 * Java draws each piece from a private method of its own, which is skipped, except where a version
 * draws one inline: 1.20.1 draws the armour, the hunger and the air bubbles inside the method that
 * draws the health, and 1.21.1 the air bubbles, each under a profiler section of that name, so the
 * drawing calls between one section and the next are what is skipped there. From 26 the bar above
 * the hotbar is whichever of the experience, the jump and the locator bar the frame picks, and all
 * of it is Bedrock's progress bar. The class holding the methods is `Gui` up to 26.1.2 and `Hud`
 * from 26.2, with the same method names on both.
 */
//? if >=26.2 {
@Mixin(net.minecraft.client.gui.Hud.class)
//?} else {
/*@Mixin(net.minecraft.client.gui.Gui.class)
*///?}
public abstract class HudMixin {
    //? if >=26 {
    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideHotbar(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.HOTBAR)) info.cancel();
    }

    @Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideCrosshair(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.CROSSHAIR)) info.cancel();
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideStatusEffects(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.STATUS_EFFECTS)) info.cancel();
    }

    @Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideItemText(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.ITEM_TEXT)) info.cancel();
    }

    @Inject(method = "extractVehicleHealth", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideHorseHealth(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.HORSE_HEALTH)) info.cancel();
    }

    @Inject(method = "extractHearts", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideHealth(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.HEALTH)) info.cancel();
    }

    @Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$hideArmor(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.ARMOR)) info.cancel();
    }

    @Inject(method = "extractFood", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideHunger(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.HUNGER)) info.cancel();
    }

    @Inject(method = "extractAirBubbles", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideAirBubbles(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.AIR_BUBBLES)) info.cancel();
    }
    //?} else {
    /*@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideCrosshair(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.CROSSHAIR)) info.cancel();
    }

    @Inject(method = "renderEffects", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideStatusEffects(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.STATUS_EFFECTS)) info.cancel();
    }

    @Inject(method = "renderSelectedItemName", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideItemText(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.ITEM_TEXT)) info.cancel();
    }

    @Inject(method = "renderVehicleHealth", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideHorseHealth(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.HORSE_HEALTH)) info.cancel();
    }

    @Inject(method = "renderHearts", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideHealth(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.HEALTH)) info.cancel();
    }

    @Inject(method = {"renderExperienceBar", "renderJumpMeter"}, at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideProgressBar(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.PROGRESS_BAR)) info.cancel();
    }
    *///?}

    //? if >=26.2 {
    @WrapWithCondition(method = "extractHotbarAndDecorations", at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractBackground("
            + "Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractRenderState("
            + "Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")})
    private boolean mcaddon$drawsProgressBar(net.minecraft.client.gui.contextualbar.ContextualBar bar,
        net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta) {
        return !PlayerHud.hides(HudElement.PROGRESS_BAR);
    }

    @WrapWithCondition(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractExperienceLevel("
            + "Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"))
    private boolean mcaddon$drawsExperienceLevel(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
        net.minecraft.client.gui.Font font, int level) {
        return !PlayerHud.hides(HudElement.PROGRESS_BAR);
    }
    //?} else if >=26 {
    /*@WrapWithCondition(method = "extractHotbarAndDecorations", at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;"
            + "extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"),
        @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;"
            + "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
            + "Lnet/minecraft/client/DeltaTracker;)V")})
    private boolean mcaddon$drawsProgressBar(net.minecraft.client.gui.contextualbar.ContextualBarRenderer bar,
        net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta) {
        return !PlayerHud.hides(HudElement.PROGRESS_BAR);
    }

    @WrapWithCondition(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;extractExperienceLevel("
            + "Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V"))
    private boolean mcaddon$drawsExperienceLevel(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
        net.minecraft.client.gui.Font font, int level) {
        return !PlayerHud.hides(HudElement.PROGRESS_BAR);
    }
    *///?} else if >=1.21 {
    /*@Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideHotbar(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.HOTBAR)) info.cancel();
    }

    @Inject(method = "renderArmor", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$hideArmor(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.ARMOR)) info.cancel();
    }

    @Inject(method = "renderFood", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideHunger(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.HUNGER)) info.cancel();
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideExperienceLevel(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.PROGRESS_BAR)) info.cancel();
    }

    @WrapWithCondition(method = "renderPlayerHealth",
        slice = @org.spongepowered.asm.mixin.injection.Slice(from = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=air")),
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite("
            + "Lnet/minecraft/resources/Identifier;IIII)V"))
    private boolean mcaddon$drawsAirBubble(net.minecraft.client.gui.GuiGraphics graphics,
        net.minecraft.resources.Identifier sprite, int x, int y, int width, int height) {
        return !PlayerHud.hides(HudElement.AIR_BUBBLES);
    }
    *///?} else {
    /*@Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hideHotbar(CallbackInfo info) {
        if (PlayerHud.hides(HudElement.HOTBAR)) info.cancel();
    }

    @WrapWithCondition(method = "renderPlayerHealth",
        slice = @org.spongepowered.asm.mixin.injection.Slice(
            from = @At(value = "INVOKE_STRING",
                target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V",
                args = "ldc=armor"),
            to = @At(value = "INVOKE_STRING",
                target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
                args = "ldc=health")),
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blit("
            + "Lnet/minecraft/resources/Identifier;IIIIII)V"))
    private boolean mcaddon$drawsArmor(net.minecraft.client.gui.GuiGraphics graphics,
        net.minecraft.resources.Identifier texture, int x, int y, int u, int v, int width, int height) {
        return !PlayerHud.hides(HudElement.ARMOR);
    }

    @WrapWithCondition(method = "renderPlayerHealth",
        slice = @org.spongepowered.asm.mixin.injection.Slice(
            from = @At(value = "INVOKE_STRING",
                target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
                args = "ldc=food"),
            to = @At(value = "INVOKE_STRING",
                target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
                args = "ldc=air")),
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blit("
            + "Lnet/minecraft/resources/Identifier;IIIIII)V"))
    private boolean mcaddon$drawsHunger(net.minecraft.client.gui.GuiGraphics graphics,
        net.minecraft.resources.Identifier texture, int x, int y, int u, int v, int width, int height) {
        return !PlayerHud.hides(HudElement.HUNGER);
    }

    @WrapWithCondition(method = "renderPlayerHealth",
        slice = @org.spongepowered.asm.mixin.injection.Slice(from = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=air")),
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blit("
            + "Lnet/minecraft/resources/Identifier;IIIIII)V"))
    private boolean mcaddon$drawsAirBubble(net.minecraft.client.gui.GuiGraphics graphics,
        net.minecraft.resources.Identifier texture, int x, int y, int u, int v, int width, int height) {
        return !PlayerHud.hides(HudElement.AIR_BUBBLES);
    }
    *///?}
}
