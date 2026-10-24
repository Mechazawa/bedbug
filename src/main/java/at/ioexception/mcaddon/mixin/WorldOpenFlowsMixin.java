package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.AddonSession;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens a saved world only once the add-ons its list turns on are active on the client. */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
    @Shadow
    @Final
    private LevelStorageSource levelSource;

    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void mcaddon$holdForAddons(String levelId, Runnable onCancel, CallbackInfo info) {
        WorldOpenFlows flows = (WorldOpenFlows) (Object) this;
        if (AddonSession.holdOpen(levelSource.getLevelPath(levelId), () -> flows.openWorld(levelId, onCancel))) {
            info.cancel();
        }
    }
}
