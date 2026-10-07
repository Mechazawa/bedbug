package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the two editions call the same vanilla mob, in both directions.
 *
 * Most ids match, which is why only the ones that differ are listed, each with the spellings Java
 * has used: boats became one type per wood, so the name that works depends on the version and the
 * registry is what decides. A name no candidate matches has no Java entity behind it, and a command
 * using it has to be dropped rather than rewritten, because Java's parser rejects a whole function
 * over one unknown type.
 *
 * The way back is a list of its own rather than the first one turned round, because Bedrock keeps
 * names it no longer writes: `villager` and `vindication_illager` both still parse there, and a
 * pack comparing ids compares against `villager_v2` and `vindicator`.
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
        Map.entry("wind_charge_projectile", List.of("wind_charge")),
        Map.entry("breeze_wind_charge_projectile", List.of("breeze_wind_charge")),
        Map.entry("boat", List.of("boat", "oak_boat")),
        Map.entry("chest_boat", List.of("chest_boat", "oak_chest_boat")),
        Map.entry("lingering_potion", List.of("lingering_potion", "potion")),
        Map.entry("splash_potion", List.of("splash_potion", "potion")));

    private static final Map<String, String> BEDROCK = Map.ofEntries(
        Map.entry("villager", "villager_v2"),
        Map.entry("zombie_villager", "zombie_villager_v2"),
        Map.entry("zombified_piglin", "zombie_pigman"),
        Map.entry("evoker", "evocation_illager"),
        Map.entry("evoker_fangs", "evocation_fang"),
        Map.entry("tropical_fish", "tropicalfish"),
        Map.entry("end_crystal", "ender_crystal"),
        Map.entry("eye_of_ender", "eye_of_ender_signal"),
        Map.entry("fishing_bobber", "fishing_hook"),
        Map.entry("firework_rocket", "fireworks_rocket"),
        Map.entry("trident", "thrown_trident"),
        Map.entry("experience_bottle", "xp_bottle"),
        Map.entry("experience_orb", "xp_orb"),
        Map.entry("wind_charge", "wind_charge_projectile"),
        Map.entry("breeze_wind_charge", "breeze_wind_charge_projectile"),
        Map.entry("bamboo_raft", "boat"),
        Map.entry("bamboo_chest_raft", "chest_boat"),
        // One thrown potion type stood for both kinds until Java split them, and splash is the kind
        // a pack throws.
        Map.entry("potion", "splash_potion"));

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

    /**
     * The Bedrock id for a Java one. Every wood's boat is Bedrock's one `boat`, which tells the woods
     * apart by a variant rather than by type. An id Bedrock spells the same, or one outside
     * `minecraft`, comes back untouched.
     */
    public static String bedrock(String java) {
        if (!java.startsWith("minecraft:")) return java;

        String name = java.substring("minecraft:".length());
        if (name.endsWith("_chest_boat")) return "minecraft:chest_boat";
        if (name.endsWith("_boat")) return "minecraft:boat";
        return "minecraft:" + BEDROCK.getOrDefault(name, name);
    }
}
