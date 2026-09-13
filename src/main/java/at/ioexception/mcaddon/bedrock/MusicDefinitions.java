package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which track plays where, out of the resource pack's `sounds/music_definitions.json`.
 *
 * Both editions choose music by the biome the player is standing in and state a gap in seconds
 * between one track ending and the next starting, so a Bedrock entry carries everything Java asks
 * for. The key is a biome name, which the two editions spell the same way for almost every biome
 * they share; one they do not agree on matches nothing and the game keeps its own music there.
 *
 * @param event    the sound event to play, as the pack names it
 * @param minDelay the shortest silence before it starts again, in ticks
 * @param maxDelay the longest
 */
public record MusicDefinitions(String event, int minDelay, int maxDelay) {
    /** Bedrock states a delay in seconds and Java counts it in ticks. */
    private static final int TICKS_PER_SECOND = 20;

    /** Every biome the file names, by the biome name it is keyed under. */
    public static Map<String, MusicDefinitions> readAll(BedrockPack pack) {
        Map<String, MusicDefinitions> found = new LinkedHashMap<>();
        Path file = pack.file("sounds/music_definitions.json");
        if (!Files.isRegularFile(file)) return found;

        try {
            for (Map.Entry<String, JsonElement> entry : Json.parseObject(file).entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;

                JsonObject body = entry.getValue().getAsJsonObject();
                String event = Json.text(body, "event_name", null);
                if (event == null || event.isBlank()) continue;

                found.put(entry.getKey(), new MusicDefinitions(event,
                    seconds(body, "min_delay"), seconds(body, "max_delay")));
            }
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read music_definitions.json: {}", error.toString());
        }
        return found;
    }

    private static int seconds(JsonObject body, String field) {
        return (int) (Json.number(body, field, 0) * TICKS_PER_SECOND);
    }
}
