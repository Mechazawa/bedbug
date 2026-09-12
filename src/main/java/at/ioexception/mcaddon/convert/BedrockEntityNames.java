package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the two editions call the same vanilla mob.
 *
 * Most ids match, which is why only the ones that differ are listed, each with the spellings Java
 * has used: boats became one type per wood, so the name that works depends on the version and the
 * registry is what decides. A name no candidate matches has no Java entity behind it, and a command
 * using it has to be dropped rather than rewritten, because Java's parser rejects a whole function
 * over one unknown type.
 */
public final class BedrockEntityNames {
    private static final Map<String, List<String>> RENAMED = Map.ofEntries(
        Map.entry("villager_v2", List.of("villager")),
        Map.entry("zombie_villager_v2", List.of("zombie_villager")),
        Map.entry("zombie_pigman", List.of("zombified_piglin")),
        Map.entry("evocation_illager", List.of("evoker")),
        Map.entry("evocation_fang", List.of("evoker_fangs")),
        Map.entry("vindication_illager", List.of("vindicator")),
        Map.entry("tropicalfish", List.of("tropical_fish")),
        Map.entry("ender_crystal", List.of("end_crystal")),
        Map.entry("eye_of_ender_signal", List.of("eye_of_ender")),
        Map.entry("fishing_hook", List.of("fishing_bobber")),
        Map.entry("fireworks_rocket", List.of("firework_rocket")),
        Map.entry("thrown_trident", List.of("trident")),
        Map.entry("wither_skull_dangerous", List.of("wither_skull")),
        Map.entry("xp_bottle", List.of("experience_bottle")),
        Map.entry("xp_orb", List.of("experience_orb")),
        Map.entry("boat", List.of("boat", "oak_boat")),
        Map.entry("chest_boat", List.of("chest_boat", "oak_chest_boat")),
        Map.entry("lingering_potion", List.of("lingering_potion", "potion")),
        Map.entry("splash_potion", List.of("splash_potion", "potion")));

    private BedrockEntityNames() {
    }

    /**
     * The Java id for a Bedrock one, or null when no version of Java has that entity.
     *
     * An id outside the `minecraft` namespace belongs to an add-on and comes back untouched, because
     * whether it exists depends on which packs are loaded rather than on this list.
     */
    public static String java(String bedrock) {
        String id = bedrock.startsWith("minecraft:") ? bedrock.substring("minecraft:".length()) : bedrock;
        if (id.contains(":")) return bedrock;

        String name = id.toLowerCase(Locale.ROOT);
        for (String candidate : RENAMED.getOrDefault(name, List.of(name))) {
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(Platform.id("minecraft", candidate))) {
                return "minecraft:" + candidate;
            }
        }
        return null;
    }
}
