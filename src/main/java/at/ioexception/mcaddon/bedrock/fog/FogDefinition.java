package at.ioexception.mcaddon.bedrock.fog;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One `fogs/*.json` file, as far as Java's renderer can carry it.
 *
 * Bedrock describes fog for each medium the camera can be in and, in a second block, a volumetric
 * fog with its own scattering. Java has one fog: a colour and the two distances it fades between.
 * What is read here is the `air` block, which is the one a `/fog` in a function is written for; the
 * water, lava and powder snow blocks stay with the medium Java already fogs its own way.
 *
 * `render_distance_type` says whether the two distances are blocks or a multiple of the render
 * distance. The render distance belongs to the client, so the multiple travels and the client
 * resolves it.
 */
public record FogDefinition(String identifier, float red, float green, float blue, float start,
    float end, boolean scaledToRenderDistance) {

    /** Null when the file names no fog for air, which leaves nothing Java can be told to draw. */
    public static FogDefinition tryFrom(Path file) {
        try {
            JsonObject settings = Json.parseObject(file).getAsJsonObject("minecraft:fog_settings");
            if (settings == null) return null;

            JsonObject description = settings.getAsJsonObject("description");
            String identifier = description == null ? null : Json.text(description, "identifier", null);
            JsonObject distance = settings.getAsJsonObject("distance");
            JsonObject air = distance == null ? null : distance.getAsJsonObject("air");
            if (identifier == null || air == null) return null;

            float[] colour = colour(air.get("fog_color"));
            return new FogDefinition(identifier, colour[0], colour[1], colour[2],
                number(air, "fog_start", 0f), number(air, "fog_end", 1f),
                !Json.text(air, "render_distance_type", "render").equals("fixed"));
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            return null;
        }
    }

    /** Every fog a pack ships, by the identifier a `/fog` names. */
    public static Map<String, FogDefinition> readAll(BedrockPack pack) {
        Map<String, FogDefinition> fogs = new LinkedHashMap<>();
        for (Path file : pack.list("fogs", ".json")) {
            FogDefinition fog = tryFrom(file);
            if (fog != null) fogs.put(fog.identifier(), fog);
        }
        return fogs;
    }

    public float startAt(float renderDistanceBlocks) {
        return scaledToRenderDistance ? start * renderDistanceBlocks : start;
    }

    public float endAt(float renderDistanceBlocks) {
        return scaledToRenderDistance ? end * renderDistanceBlocks : end;
    }

    public String encode() {
        JsonObject json = new JsonObject();
        json.addProperty("id", identifier);
        json.addProperty("red", red);
        json.addProperty("green", green);
        json.addProperty("blue", blue);
        json.addProperty("start", start);
        json.addProperty("end", end);
        json.addProperty("scaled", scaledToRenderDistance);
        return json.toString();
    }

    /** Null for the message that says a player's fog stack has nothing left on it. */
    public static FogDefinition decode(String encoded) {
        if (encoded.isEmpty()) return null;

        JsonElement parsed = Json.parse(encoded);
        if (!parsed.isJsonObject()) return null;

        JsonObject json = parsed.getAsJsonObject();
        return new FogDefinition(Json.text(json, "id", ""), number(json, "red", 1f),
            number(json, "green", 1f), number(json, "blue", 1f), number(json, "start", 0f),
            number(json, "end", 1f), json.has("scaled") && json.get("scaled").getAsBoolean());
    }

    /**
     * Bedrock writes a colour as `#rrggbb` or as three numbers.
     *
     * The numbers are a fraction of full in the format's own examples, and a channel above one can
     * only have been written as a byte, so the two are told apart by what they hold.
     */
    private static float[] colour(JsonElement value) {
        if (value == null) return new float[] {1f, 1f, 1f};

        if (value.isJsonArray()) {
            JsonArray channels = value.getAsJsonArray();
            if (channels.size() < 3) return new float[] {1f, 1f, 1f};

            float[] read = new float[3];
            for (int i = 0; i < 3; i++) read[i] = channels.get(i).getAsFloat();
            boolean bytes = read[0] > 1f || read[1] > 1f || read[2] > 1f;
            for (int i = 0; i < 3; i++) read[i] = bytes ? read[i] / 255f : read[i];
            return read;
        }

        int packed = Integer.parseInt(value.getAsString().replace("#", ""), 16);
        return new float[] {(packed >> 16 & 0xFF) / 255f, (packed >> 8 & 0xFF) / 255f,
            (packed & 0xFF) / 255f};
    }

    private static float number(JsonObject json, String key, float fallback) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsFloat();
    }
}
