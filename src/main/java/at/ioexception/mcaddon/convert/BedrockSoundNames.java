package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

/**
 * What the two editions call the same built-in sound.
 *
 * A pack names a sound either by a key its own `sound_definitions.json` declares, which this mod
 * registers an event for, or by one of the engine's own. An engine name lives in Bedrock rather
 * than in any add-on, so there is no definition to convert and the only way to play something is to
 * name Java's equivalent. Most of the ambient and music names are spelled the same in both
 * editions, which is why only the ones that differ are listed.
 *
 * The registry decides whether the answer exists, because a track Java added later is absent on an
 * older version. A name it has nothing for comes back null, and the caller reports it rather than
 * writing an id the game would refuse a whole biome over.
 */
public final class BedrockSoundNames {
    private static final Map<String, String> RENAMED = Map.ofEntries(
        Map.entry("ambient.soulsand_valley.additions", "ambient.soul_sand_valley.additions"),
        Map.entry("ambient.soulsand_valley.loop", "ambient.soul_sand_valley.loop"),
        Map.entry("ambient.soulsand_valley.mood", "ambient.soul_sand_valley.mood"),
        Map.entry("ambient.underwater.additions", "ambient.underwater.loop.additions"),
        Map.entry("ambient.candle", "block.candle.ambient"),
        Map.entry("ambient.weather.lightning.impact", "entity.lightning_bolt.impact"),
        Map.entry("ambient.weather.rain", "weather.rain"),
        Map.entry("ambient.weather.the_end_light_flash", "weather.end_flash"),
        Map.entry("ambient.weather.thunder", "entity.lightning_bolt.thunder"),
        Map.entry("breeze_wind_charge.burst", "entity.breeze.wind_burst"),
        Map.entry("explode", "entity.generic.explode"),
        Map.entry("mob.horse.wood", "entity.horse.step_wood"),
        Map.entry("music.game.basalt_deltas", "music.nether.basalt_deltas"),
        Map.entry("music.game.creative", "music.creative"),
        Map.entry("music.game.credits", "music.credits"),
        Map.entry("music.game.crimson_forest", "music.nether.crimson_forest"),
        Map.entry("music.game.deep_dark", "music.overworld.deep_dark"),
        Map.entry("music.game.dripstone_caves", "music.overworld.dripstone_caves"),
        Map.entry("music.game.end", "music.end"),
        Map.entry("music.game.endboss", "music.dragon"),
        Map.entry("music.game.frozen_peaks", "music.overworld.frozen_peaks"),
        Map.entry("music.game.grove", "music.overworld.grove"),
        Map.entry("music.game.jagged_peaks", "music.overworld.jagged_peaks"),
        Map.entry("music.game.lush_caves", "music.overworld.lush_caves"),
        Map.entry("music.game.meadow", "music.overworld.meadow"),
        // Bedrock's generic nether track plays the four pieces Java's nether wastes music plays.
        Map.entry("music.game.nether", "music.nether.nether_wastes"),
        Map.entry("music.game.nether_wastes", "music.nether.nether_wastes"),
        Map.entry("music.game.snowy_slopes", "music.overworld.snowy_slopes"),
        Map.entry("music.game.soulsand_valley", "music.nether.soul_sand_valley"),
        Map.entry("music.game.stony_peaks", "music.overworld.stony_peaks"),
        Map.entry("music.game.swamp_music", "music.overworld.swamp"),
        Map.entry("music.game.warped_forest", "music.nether.warped_forest"),
        Map.entry("music.game.water", "music.under_water"),
        // Both pick from the calm, hal and piano pieces Java plays under one name.
        Map.entry("music.game_and_wild_equal_chance", "music.game"),
        Map.entry("music.game_and_wild_favor_game", "music.game"),
        Map.entry("music.overworld.jungle_edge", "music.overworld.sparse_jungle"),
        Map.entry("music.overworld.mesa", "music.overworld.badlands"),
        Map.entry("music.overworld.swamp_music", "music.overworld.swamp"),
        Map.entry("wind_charge.burst", "entity.wind_charge.wind_burst"));

    /**
     * The level sound events a swing names, against the Java sound Bedrock's own entry for each
     * plays. Bedrock's entry for each metal spear names the one spear sound, which is why only the
     * plain spear and the wooden one, which has sounds of its own, are listed.
     */
    private static final Map<String, String> EVENTS = Map.ofEntries(
        Map.entry("attack.critical", "entity.player.attack.crit"),
        Map.entry("attack.nodamage", "entity.player.attack.nodamage"),
        Map.entry("attack.strong", "entity.player.attack.strong"),
        Map.entry("shear", "entity.sheep.shear"),
        Map.entry("item.spear.attack_hit", "item.spear.hit"),
        Map.entry("item.spear.attack_miss", "item.spear.attack"),
        Map.entry("item.wooden_spear.attack_hit", "item.spear_wood.hit"),
        Map.entry("item.wooden_spear.attack_miss", "item.spear_wood.attack"),
        Map.entry("item.wooden_spear.use", "item.spear_wood.use"));

    private BedrockSoundNames() {
    }

    /**
     * The Java id for what one of Bedrock's level sound events plays where no pack gives it a sound,
     * or null where this version has nothing for it.
     */
    public static String javaForEvent(String event) {
        String plain = event.replaceFirst("^item\\.(copper|diamond|golden|iron|netherite|stone)_spear\\.",
            "item.spear.");
        return java(EVENTS.getOrDefault(plain, plain));
    }

    /**
     * The sound event a pack's own name becomes, or null where nothing plays under it.
     *
     * The pack's own definitions are searched first, because a pack naming one of the engine's
     * sounds and shipping a definition under that name means its own file rather than the
     * engine's.
     *
     * @param defined every sound event the pack defines, as `namespace:path`
     */
    public static String java(String bedrock, String namespace, List<String> defined) {
        Names.Identifier own = Names.soundEvent(bedrock, namespace);
        String id = own.namespace() + ":" + own.path();
        return defined.contains(id) ? id : java(bedrock);
    }

    /**
     * The sound event a script's name plays: the id as written when the game has it, else the
     * definition the add-on running the script ships under that name, else the Java sound Bedrock's
     * name stands for, else the name as written.
     *
     * @param namespace the add-on whose script runs, null where several add-ons ship a script
     */
    public static Identifier played(String sound, String namespace) {
        Identifier written = sound == null ? null : Identifier.tryParse(sound);
        if (written == null || BuiltInRegistries.SOUND_EVENT.containsKey(written)) return written;

        if (namespace != null) {
            Names.Identifier own = Names.soundEvent(sound, namespace);
            Identifier packs = Identifier.tryParse(own.namespace() + ":" + own.path());
            if (packs != null && BuiltInRegistries.SOUND_EVENT.containsKey(packs)) return packs;
        }

        String java = java(sound);
        return java == null ? written : Identifier.tryParse(java);
    }

    /** The Java id for one of Bedrock's own sounds, or null where this version has no such event. */
    public static String java(String bedrock) {
        String name = bedrock.startsWith("minecraft:")
            ? bedrock.substring("minecraft:".length()) : bedrock;
        if (name.contains(":")) return null;

        String candidate = RENAMED.getOrDefault(name, name);
        if (!exists(candidate) && candidate.startsWith("mob.")) candidate = "entity." + candidate.substring(4);
        return exists(candidate) ? "minecraft:" + candidate : null;
    }

    private static boolean exists(String name) {
        return BuiltInRegistries.SOUND_EVENT.containsKey(Platform.id("minecraft", name));
    }
}
