package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * What a client biome's `minecraft:biome_music` plays, resolved against the pack's own definitions.
 *
 * The component names a key in `sounds/music_definitions.json` rather than a sound, and that entry
 * is what carries the track and the silence between one playing and the next. Bedrock's underwater
 * music comes out of the same file under the key `water`, which is the entry a biome asking for
 * underwater music gets.
 *
 * @param track      what plays in the biome, or null where the pack defines no such entry
 * @param underwater what plays while the player is submerged in it, on the versions with a field
 * @param volume     the multiplier the component states, or -1 where it states none
 */
public record BiomeMusic(MusicDefinitions track, MusicDefinitions underwater, float volume) {
    /** Nothing stated, which leaves the game's own music where it is. */
    public static final BiomeMusic NONE = new BiomeMusic(null, null, -1);

    /** The key Bedrock keeps its underwater track under. */
    private static final String UNDERWATER = "water";

    /**
     * @param definitions every entry of `music_definitions.json`, by the key it is written under
     * @param sound       the Java sound event one of the pack's names becomes, or null for none
     */
    public static BiomeMusic read(JsonObject component, Map<String, MusicDefinitions> definitions,
        UnaryOperator<String> sound) {
        if (component == null) return NONE;

        return new BiomeMusic(
            playing(Json.text(component, "music_definition", null), definitions, sound),
            Json.bool(component, "underwater_music", false)
                ? playing(UNDERWATER, definitions, sound) : null,
            (float) Json.number(component, "volume_multiplier", -1));
    }

    /**
     * The entry under one key, with its event read as the Java sound to play.
     *
     * An empty key is Bedrock's way of asking for silence, which Java has no way to state: a biome
     * naming no music plays whatever its dimension plays.
     */
    private static MusicDefinitions playing(String key, Map<String, MusicDefinitions> definitions,
        UnaryOperator<String> sound) {
        if (key == null) return null;
        if (key.isBlank()) {
            AddonEntities.unsupported("client biome minecraft:biome_music asking for silence");
            return null;
        }

        MusicDefinitions found = definitions.get(key);
        if (found == null) {
            AddonEntities.unsupported("music_definitions " + key + ", which no installed pack defines");
            return null;
        }

        String event = sound.apply(found.event());
        if (event == null) {
            AddonEntities.unsupported("biome sound " + found.event());
            return null;
        }
        return new MusicDefinitions(event, found.minDelay(), found.maxDelay());
    }
}
