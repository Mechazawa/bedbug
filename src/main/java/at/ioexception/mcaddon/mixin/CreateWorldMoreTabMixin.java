package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.screen.AddonsScreen;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Puts an Add-Ons button under Data Packs on Create World's More tab. */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$MoreTab")
public abstract class CreateWorldMoreTabMixin {
    private static final int BUTTON_WIDTH = 210;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void mcaddon$addAddons(CallbackInfo info) {
        GridLayout layout = (GridLayout) ((GridLayoutTab) (Object) this).getLayout();
        // The tab lays its buttons out one to a row.
        int[] rows = {0};
        layout.visitChildren(child -> rows[0]++);
        layout.addChild(Button.builder(Component.translatable("mcaddon.addons.button"), button -> {
            if (Platform.currentScreen() instanceof CreateWorldScreen screen) {
                Platform.setScreen(AddonsScreen.forNewWorld(screen));
            }
        }).width(BUTTON_WIDTH).build(), rows[0], 0);
    }
}
