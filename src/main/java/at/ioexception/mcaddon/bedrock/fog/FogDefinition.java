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
 * The `air` block is the fog drawn in the open. `lava`, `lava_resistance`, `powder_snow` and `weather`
 * replace it while the camera is in that medium or while precipitation falls at it. The `water`
 * block stays with the water fog a biome carries, which Java already draws through its own
 * attributes.
 *
 * `render_distance_type` says whether the two distances are blocks or a multiple of the render
 * distance. The render distance belongs to the client, so the multiple travels and the client
 * resolves it.
 */
public record FogDefinition(String identifier, float red, float green, float blue, float start,
    float end, boolean scaledToRenderDistance, Medium lava, Medium lavaResistance,
    Medium powderSnow, Medium weather) {

    private static final String[] MEDIA = {"lava", "lava_resistance", "powder_snow", "weather"};

    /**
     * One fog block: a colour and the two distances it fades between.
     *
     * A distance is blocks, or a multiple of the render distance until {@link #inBlocks} resolves it.
     */
    public record Medium(float red, float green, float blue, float start, float end,
        boolean scaledToRenderDistance) {

        static Medium tryFrom(JsonObject block) {
            if (block == null) return null;

            float[] colour = colour(block.get("fog_color"));
            return new Medium(colour[0], colour[1], colour[2], number(block, "fog_start", 0f),
                number(block, "fog_end", 1f),
                !Json.text(block, "render_distance_type", "render").equals("fixed"));
        }

        public Medium inBlocks(float renderDistanceBlocks) {
            return scaledToRenderDistance
                ? new Medium(red, green, blue, start * renderDistanceBlocks, end * renderDistanceBlocks,
                    false)
                : this;
        }

        /** Both must already be in blocks. A weight of one is `other`. */
        public Medium mix(Medium other, float weight) {
            return new Medium(lerp(red, other.red, weight), lerp(green, other.green, weight),
                lerp(blue, other.blue, weight), lerp(start, other.start, weight),
                lerp(end, other.end, weight), false);
        }

        JsonObject encode() {
            JsonObject json = new JsonObject();
            json.addProperty("red", red);
            json.addProperty("green", green);
            json.addProperty("blue", blue);
            json.addProperty("start", start);
            json.addProperty("end", end);
            json.addProperty("scaled", scaledToRenderDistance);
            return json;
        }

        static Medium decode(JsonObject json) {
            if (json == null) return null;

            return new Medium(number(json, "red", 1f), number(json, "green", 1f),
                number(json, "blue", 1f), number(json, "start", 0f), number(json, "end", 1f),
                json.has("scaled") && json.get("scaled").getAsBoolean());
        }

        private static float lerp(float from, float to, float weight) {
            return from + (to - from) * weight;
        }
    }

    public FogDefinition(String identifier, float red, float green, float blue, float start,
        float end, boolean scaledToRenderDistance) {
        this(identifier, red, green, blue, start, end, scaledToRenderDistance, null, null, null, null);
    }

    public Medium air() {
        return new Medium(red, green, blue, start, end, scaledToRenderDistance);
    }

    /** The lava block, or the one for fire resistance while the camera entity has it. */
    public Medium lava(boolean fireResistant) {
        return fireResistant && lavaResistance != null ? lavaResistance : lava;
    }

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

            Medium open = Medium.tryFrom(air);
            return new FogDefinition(identifier, open.red, open.green, open.blue, open.start,
                open.end, open.scaledToRenderDistance,
                Medium.tryFrom(distance.getAsJsonObject(MEDIA[0])),
                Medium.tryFrom(distance.getAsJsonObject(MEDIA[1])),
                Medium.tryFrom(distance.getAsJsonObject(MEDIA[2])),
                Medium.tryFrom(distance.getAsJsonObject(MEDIA[3])));
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
        return air().inBlocks(renderDistanceBlocks).start;
    }

    public float endAt(float renderDistanceBlocks) {
        return air().inBlocks(renderDistanceBlocks).end;
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
        Medium[] media = {lava, lavaResistance, powderSnow, weather};
        for (int i = 0; i < media.length; i++) {
            if (media[i] != null) json.add(MEDIA[i], media[i].encode());
        }
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
            number(json, "end", 1f), json.has("scaled") && json.get("scaled").getAsBoolean(),
            Medium.decode(json.getAsJsonObject(MEDIA[0])), Medium.decode(json.getAsJsonObject(MEDIA[1])),
            Medium.decode(json.getAsJsonObject(MEDIA[2])), Medium.decode(json.getAsJsonObject(MEDIA[3])));
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
