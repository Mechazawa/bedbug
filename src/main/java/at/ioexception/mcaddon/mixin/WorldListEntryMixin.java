package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.AddonSession;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Re-Create starts from the source world's add-ons, made active and their resources reloaded first. */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry")
public abstract class WorldListEntryMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private LevelSummary summary;

    @Shadow
    public abstract void recreateWorld();

    @Inject(method = "recreateWorld", at = @At("HEAD"), cancellable = true)
    private void mcaddon$holdForAddons(CallbackInfo info) {
        if (AddonSession.holdOpen(minecraft.getLevelSource().getLevelPath(summary.getLevelId()), this::recreateWorld)) {
            info.cancel();
        }
    }
}
