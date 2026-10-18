package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.PlayerMusic;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the game's own background music waiting while a pack's `/music` track plays. */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mcaddon$waitForThePacksTrack(CallbackInfo info) {
        if (PlayerMusic.holdsTheMusic()) info.cancel();
    }
}
