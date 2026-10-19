package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.registry.AddonSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

import java.util.stream.Stream;

/**
 * The level sound events `minecraft:swing_sounds` names for the swing of the item carrying it.
 *
 * Bedrock plays them in place of the player's own: `attack_miss` for a swing that lands on nothing
 * or deals no damage, `attack_hit` for one that hurts and `attack_critical_hit` for a critical hit.
 * Each name is a level sound event, which a resource pack's `individual_event_sounds` gives a sound;
 * a moment the item leaves out keeps the sound Java plays.
 */
public record SwingSounds(String miss, String hit, String critical) {
    private static final String COMPONENT = "minecraft:swing_sounds";

    /** The moments of a swing, in Java's sounds for each. */
    public enum Moment {
        MISS, HIT, CRITICAL;

        /** The moment Java's sound for an attack stands for, or null for one no swing sound replaces. */
        public static Moment of(SoundEvent java) {
            if (java == SoundEvents.PLAYER_ATTACK_CRIT) return CRITICAL;
            if (java == SoundEvents.PLAYER_ATTACK_NODAMAGE) return MISS;
            return java == SoundEvents.PLAYER_ATTACK_STRONG || java == SoundEvents.PLAYER_ATTACK_WEAK
                || java == SoundEvents.PLAYER_ATTACK_KNOCKBACK ? HIT : null;
        }
    }

    public static SwingSounds tryFrom(ItemDefinition definition) {
        if (!definition.components().containsKey(COMPONENT)) return null;
        return new SwingSounds(definition.text(COMPONENT, "attack_miss"),
            definition.text(COMPONENT, "attack_hit"), definition.text(COMPONENT, "attack_critical_hit"));
    }

    public Stream<String> named() {
        return Stream.of(miss, hit, critical).filter(java.util.Objects::nonNull);
    }

    /**
     * Plays the sound this item names for the moment Java's own sound stands for, heard by everyone
     * near the player, and answers whether it did. Java's own sound plays where this answers false.
     */
    public boolean playInstead(Player player, SoundEvent java) {
        Moment moment = Moment.of(java);
        return moment != null && play(player, moment);
    }

    public boolean play(Player player, Moment moment) {
        String event = switch (moment) {
            case MISS -> miss;
            case HIT -> hit;
            case CRITICAL -> critical;
        };
        AddonSounds.EventSound sound = event == null ? null : AddonSounds.eventSound(event);
        if (sound == null) return false;

        sound.playAt(player);
        return true;
    }
}
