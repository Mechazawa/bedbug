package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.alchemy.Potion;

import java.util.List;
import java.util.Optional;

/**
 * Bedrock's potions by the number a pack names them with.
 *
 * Bedrock numbers its potions where Java names them, so `"id": 19` in a witch's drinking list is
 * water breathing. The list runs in Bedrock's own order, which Mojang's witch and wandering trader
 * confirm in the comments beside their numbers; a place no Java potion stands for is null, and a
 * name the running version has no potion of resolves to nothing.
 */
public final class BedrockPotions {
    private static final List<String> BY_NUMBER = java.util.Arrays.asList(
        "water", "mundane", null, "thick", "awkward",
        "night_vision", "long_night_vision", "invisibility", "long_invisibility",
        "leaping", "long_leaping", "strong_leaping",
        "fire_resistance", "long_fire_resistance",
        "swiftness", "long_swiftness", "strong_swiftness",
        "slowness", "long_slowness",
        "water_breathing", "long_water_breathing",
        "healing", "strong_healing", "harming", "strong_harming",
        "poison", "long_poison", "strong_poison",
        "regeneration", "long_regeneration", "strong_regeneration",
        "strength", "long_strength", "strong_strength",
        "weakness", "long_weakness",
        null,
        "turtle_master", "long_turtle_master", "strong_turtle_master",
        "slow_falling", "long_slow_falling", "strong_slowness",
        "wind_charged", "weaving", "oozing", "infested");

    private BedrockPotions() {
    }

    public static Optional<Potion> numbered(int number) {
        String named = number < 0 || number >= BY_NUMBER.size() ? null : BY_NUMBER.get(number);
        return named == null ? Optional.empty() : BuiltInRegistries.POTION.getOptional(Platform.id("minecraft", named));
    }
}
