package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.client.render.PlayerCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Puts the ears back at the player's head while a camera preset that asks for it is up.
 *
 * The game hears from wherever its camera stands, which is Bedrock's default too, so the only
 * change is for a preset naming `"listener": "player"`. The engine reads the position and the
 * directions off the camera it is handed, so handing it one placed at the player's eyes is enough.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @ModifyVariable(method = "updateSource", at = @At("HEAD"), argsOnly = true)
    private Camera mcaddon$hearFromThePreset(Camera camera) {
        return PlayerCamera.listener(camera);
    }
}
