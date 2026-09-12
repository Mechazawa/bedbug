package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns Bedrock's names into names a Java `ResourceLocation` accepts. */
public final class Names {
    private Names() {
    }

    /**
     * Bedrock allows uppercase, spaces and characters a resource location rejects.
     *
     * Anything outside the allowed set becomes an underscore rather than being dropped, so two
     * different Bedrock names cannot collapse onto one Java name by losing characters.
     */
    public static String sanitise(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (char character : value.toLowerCase(Locale.ROOT).toCharArray()) {
            result.append(allowed(character) ? character : '_');
        }
        return result.toString();
    }

    private static boolean allowed(char character) {
        return character >= 'a' && character <= 'z'
            || character >= '0' && character <= '9'
            || character == '_' || character == '.' || character == '-' || character == '/';
    }

    /**
     * The namespace a pack's own content uses, recovered from the identifiers it declares.
     *
     * A pack's assets have no namespace of their own, only paths, so one has to be chosen for them.
     * Taking the namespace the pack already uses for its entities and items means a texture ends up
     * at `oreville_vn:textures/...` and not under some invented prefix, which keeps ids readable and
     * keeps two packs apart without a lookup table.
     */
    public static String namespaceOf(BedrockPack pack) {
        Map<String, Integer> counts = new HashMap<>();
        for (String directory : List.of("entity", "entities", "items", "attachables", "blocks")) {
            for (Path file : pack.list(directory, ".json")) {
                String identifier = identifierIn(file);
                if (identifier == null) continue;
                int colon = identifier.indexOf(':');
                if (colon > 0) counts.merge(identifier.substring(0, colon), 1, Integer::sum);
            }
        }

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(entry -> sanitise(entry.getKey()))
            .orElseGet(() -> "mcaddon_" + pack.manifest().uuid().toString().substring(0, 8));
    }

    /** The `identifier` under whichever single `minecraft:*` key the file declares. */
    private static String identifierIn(Path file) {
        try {
            JsonObject root = Json.parseObject(file);
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getKey().startsWith("minecraft:") || !entry.getValue().isJsonObject()) continue;
                JsonObject body = entry.getValue().getAsJsonObject();
                JsonObject description = body.has("description") && body.get("description").isJsonObject()
                    ? body.getAsJsonObject("description") : body;
                String identifier = Json.text(description, "identifier", null);
                if (identifier != null) return identifier;
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
        return null;
    }
}
