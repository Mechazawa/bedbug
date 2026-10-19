package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.behaviour.SwingSounds;
import at.ioexception.mcaddon.registry.AddonItems;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//? if <26 {
/*import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
*///?}

/**
 * Plays what a held item's `minecraft:swing_sounds` names in place of the sound Java's attack
 * makes for a hit, a critical hit and a blow that does no damage.
 *
 * The attack runs on the server and Java plays its sound from there to everyone near, so the
 * replacement is played the same way.
 */
@Mixin(Player.class)
public abstract class PlayerSwingSoundMixin {
    //? if >=26 {
    @WrapOperation(method = {"attack", "attackVisualEffects"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;"
                + "playServerSideSound(Lnet/minecraft/sounds/SoundEvent;)V"))
    private void mcaddon$swingSound(Player player, SoundEvent sound, Operation<Void> original) {
        SwingSounds swings = AddonItems.swingSoundsOf(player.getMainHandItem().getItem());
        if (swings == null || !swings.playInstead(player, sound)) original.call(player, sound);
    }
    //?} else {
    /*@WrapOperation(method = "attack",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;playSound(Lnet/minecraft/world/entity/player/Player;"
                + "DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V"))
    private void mcaddon$swingSound(Level level, Player except, double x, double y, double z, SoundEvent sound,
        SoundSource source, float volume, float pitch, Operation<Void> original) {
        Player player = (Player) (Object) this;
        SwingSounds swings = AddonItems.swingSoundsOf(player.getMainHandItem().getItem());
        if (swings == null || !swings.playInstead(player, sound)) {
            original.call(level, except, x, y, z, sound, source, volume, pitch);
        }
    }
    *///?}
}
