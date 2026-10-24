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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/** Puts an Add-Ons button on Edit World, above the row of Save and Cancel. */
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
}
