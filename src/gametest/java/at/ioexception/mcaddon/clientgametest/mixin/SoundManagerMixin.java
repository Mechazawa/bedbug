package at.ioexception.mcaddon.clientgametest.mixin;

import at.ioexception.mcaddon.clientgametest.Heard;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Writes down every sound the client is asked to play, whether or not an audio device plays it.
 *
 * A test client may run with no sound device at all, and the engine then drops a sound before any
 * listener hears it, so what is recorded is the request rather than the playback.
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"))
    private void mcaddon$recordPlay(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> info) {
        Heard.record(sound);
    }

    @Inject(method = "playDelayed", at = @At("HEAD"))
    private void mcaddon$recordDelayed(SoundInstance sound, int delay, CallbackInfo info) {
        Heard.record(sound);
    }
}
