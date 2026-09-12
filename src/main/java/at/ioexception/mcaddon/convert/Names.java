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
     * One Bedrock identifier split into the two halves a resource location is made of.
     *
     * An identifier with no colon belongs to whatever namespace the caller says, which differs by
     * what is being named: a pack's own content takes the pack's namespace, while the game's own
     * takes `minecraft`. Both halves come back sanitised, because that is the only form the game
     * accepts and every caller wanted it.
     */
    public static Identifier split(String identifier, String fallbackNamespace) {
        int colon = identifier.indexOf(':');
        return colon <= 0
            ? new Identifier(sanitise(fallbackNamespace), sanitise(identifier))
            : new Identifier(sanitise(identifier.substring(0, colon)),
                sanitise(identifier.substring(colon + 1)));
    }

    /**
     * The sound event a definition's key becomes.
     *
     * A key that carries its own namespace keeps it, and one that does not belongs to the pack that
     * wrote it. Registering an event and looking one up have to agree, so both ask here.
     */
    public static Identifier soundEvent(String key, String packNamespace) {
        return split(key, packNamespace);
    }

    /** A Bedrock identifier in the shape the game reads, without being a `ResourceLocation` yet. */
    public record Identifier(String namespace, String path) {
    }

    /**
     * The namespace a pack's own content uses, recovered from the identifiers it declares.
     *
     * A pack's assets have no namespace of their own, only paths, so one has to be chosen for them.
     * Taking the namespace the add-on already uses for its entities, items and blocks means a texture
     * ends up at `oreville_vn:textures/...` and not under some invented prefix, which keeps ids
     * readable and keeps two packs apart without a lookup table. The behaviour packs are read too,
     * because a resource pack often declares no identifiers of its own.
     */
    public static String namespaceOf(BedrockPack pack, List<BedrockPack> behaviours) {
        Map<String, Integer> counts = new HashMap<>();
        List<BedrockPack> everywhere = new java.util.ArrayList<>(behaviours);
        everywhere.add(pack);

        for (String directory : List.of("entity", "entities", "items", "attachables", "blocks")) {
            for (Path file : everywhere.stream().flatMap(one -> one.list(directory, ".json").stream())
                .toList()) {
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
