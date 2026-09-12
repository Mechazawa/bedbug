package at.ioexception.mcaddon.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads the JSON dialect Bedrock packs are written in.
 *
 * Pack files carry byte order marks, line comments and trailing commas, none of which strict JSON
 * allows, so every read in this mod goes through here rather than through Gson's defaults.
 */
public final class Json {
    private Json() {
    }

    @SuppressWarnings("deprecation")
    public static JsonElement parse(InputStream stream) throws IOException {
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonReader lenient = new JsonReader(reader);
            lenient.setLenient(true);
            return JsonParser.parseReader(lenient);
        }
    }

    public static JsonObject parseObject(Path file) throws IOException {
        try (InputStream stream = Files.newInputStream(file)) {
            JsonElement parsed = parse(stream);
            if (!parsed.isJsonObject()) throw new IOException(file + " is not a JSON object");
            return parsed.getAsJsonObject();
        }
    }

    public static String text(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsString();
    }

    /** Bedrock writes a single value where a list is allowed, so both shapes reach this. */
    public static List<JsonElement> list(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) return List.of(value);
        return value.getAsJsonArray().asList();
    }
}
