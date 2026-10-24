package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plays what an active pack's `sounds.json` says a mob of the game's own makes, in place of the
 * sound Java gives it. Every mob voice, its idle sound, its hurt and death and a villager's trade
 * sounds, goes through this one call on both sides.
 */
@Mixin(Entity.class)
public abstract class EntityVoiceMixin {
    @Inject(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V", at = @At("HEAD"), cancellable = true)
    private void mcaddon$packVoice(SoundEvent sound, float volume, float pitch, CallbackInfo info) {
        Entity entity = (Entity) (Object) this;
        AddonSounds.Played voice = AddonSounds.voiceOf(entity, sound, volume, pitch);
        if (voice != null && voice.sound() == sound && voice.volume() == volume && voice.pitch() == pitch) return;

        info.cancel();
        if (voice != null && !entity.isSilent()) {
            entity.level().playSound(null, entity.getX(), entity.getY(), entity.getZ(), voice.sound(),
                entity.getSoundSource(), voice.volume(), voice.pitch());
        }
    }
}
