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
 * What a resource pack's `sounds.json` plays for each level sound event, out of its
 * `individual_event_sounds` section.
 *
 * A level sound event is one of the engine's fixed names, `item.spear.attack_hit` or
 * `attack.critical`, and the entry names the sound definition it plays, with its volume and pitch.
 *
 * @param sound   the sound definition the event plays
 * @param volume  how loud, 1 where the entry names no volume
 * @param pitch   the range a pitch is drawn from, a range of no width at 1 where the entry names none
 */
public record EventSounds(String sound, float volume, double[] pitch) {
    public static Map<String, EventSounds> readAll(BedrockPack pack) {
        Map<String, EventSounds> found = new LinkedHashMap<>();
        Path file = pack.file("sounds.json");
        if (!Files.isRegularFile(file)) return found;

        try {
            JsonElement section = Json.parseObject(file).get("individual_event_sounds");
            JsonElement events = section != null && section.isJsonObject()
                ? section.getAsJsonObject().get("events") : null;
            if (events == null || !events.isJsonObject()) return found;

            for (Map.Entry<String, JsonElement> entry : events.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject body = entry.getValue().getAsJsonObject();
                String sound = Json.text(body, "sound", null);
                if (sound == null || sound.isBlank()) continue;

                double[] pitch = EntitySounds.range(body.get("pitch"));
                found.put(entry.getKey(), new EventSounds(sound, (float) Json.number(body, "volume", 1),
                    pitch == null ? new double[] {1, 1} : pitch));
            }
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read the event sounds of sounds.json: {}", error.toString());
        }
        return found;
    }
}
