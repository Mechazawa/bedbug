package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.screen.AddonsScreen;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Puts an Add-Ons button on Edit World, above the row of Save and Cancel, in the room of the gap
 * over that row. Java's screen fills a window 240 high with 6 to spare, so the button only fits
 * there.
 */
@Mixin(EditWorldScreen.class)
public abstract class EditWorldScreenMixin {
    private static final int BUTTON_WIDTH = 200;

    @Shadow
    @Final
    private LinearLayout layout;

    @Shadow
    @Final
    private LevelStorageSource.LevelStorageAccess levelAccess;

    @Inject(method = "<init>", at = @At(value = "NEW",
        target = "net/minecraft/client/gui/layouts/SpacerElement", ordinal = 1))
    private void mcaddon$addAddons(CallbackInfo info) {
        EditWorldScreen screen = (EditWorldScreen) (Object) this;
        Path world = levelAccess.getLevelPath(LevelResource.ROOT);
        layout.addChild(Button.builder(Component.translatable("mcaddon.addons.button"),
                button -> Platform.setScreen(AddonsScreen.forWorld(screen, world)))
            .width(BUTTON_WIDTH).build());
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/layouts/SpacerElement;<init>(II)V", ordinal = 1), index = 1)
    private int mcaddon$closeGap(int height) {
        return 0;
    }
}
