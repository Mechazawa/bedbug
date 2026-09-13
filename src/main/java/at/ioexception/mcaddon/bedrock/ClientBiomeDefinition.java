package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The colours a resource pack paints one biome with.
 *
 * Bedrock puts the drawing half of a biome in the resource pack and the generating half in the
 * behaviour pack, under the same identifier. Java keeps both on one entry, so the two halves are
 * read together and the colours land in the converted biome's `effects`.
 *
 * A pack may write them in either of two shapes. `biomes/<name>.client_biome.json` is one file per
 * biome with named components; `biomes_client.json` is one file naming every biome at once, which
 * is what a pack written before the split says. Both are read, and the per-biome file wins where a
 * pack ships both.
 */
public record ClientBiomeDefinition(int water, int sky, int grass, int foliage, int dryFoliage) {

    /** No colour at all, which is what a biome the resource pack never mentions gets. */
    public static final ClientBiomeDefinition NONE = new ClientBiomeDefinition(-1, -1, -1, -1, -1);

    /** The components a converted biome carries something of. */
    private static final Set<String> READ = Set.of("minecraft:water_appearance",
        "minecraft:sky_color", "minecraft:grass_appearance", "minecraft:foliage_appearance",
        "minecraft:dry_foliage_color");

    /** What the one file naming every biome states beyond a colour. */
    private static final List<String> LEGACY_DROPPED =
        List.of("fog_identifier", "water_surface_transparency");

    /**
     * Every biome the resource pack paints, by identifier.
     *
     * The whole-pack file is read first so a per-biome file overrides it, which is the order
     * Bedrock reads them in.
     */
    public static Map<String, ClientBiomeDefinition> readAll(BedrockPack pack) {
        Map<String, ClientBiomeDefinition> found = new LinkedHashMap<>();
        readLegacy(pack, found);

        for (Path file : pack.list("biomes", ".json")) {
            try {
                read(Json.parseObject(file), found);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return found;
    }

    private static void read(JsonObject root, Map<String, ClientBiomeDefinition> into) {
        JsonObject body = root.has("minecraft:client_biome")
            && root.get("minecraft:client_biome").isJsonObject()
            ? root.getAsJsonObject("minecraft:client_biome") : null;
        if (body == null) return;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        JsonObject components = body.getAsJsonObject("components");
        if (identifier == null || components == null) return;

        components.keySet().stream().filter(name -> !READ.contains(name))
            .forEach(name -> AddonEntities.unsupported("client biome " + name));

        into.put(identifier, new ClientBiomeDefinition(
            colour(components, "minecraft:water_appearance", "surface_color"),
            colour(components, "minecraft:sky_color", "sky_color"),
            colour(components, "minecraft:grass_appearance", "color"),
            colour(components, "minecraft:foliage_appearance", "color"),
            colour(components, "minecraft:dry_foliage_color", "color")));
    }

    private static void readLegacy(BedrockPack pack, Map<String, ClientBiomeDefinition> into) {
        if (!pack.has("biomes_client.json")) return;

        try {
            JsonObject biomes = Json.parseObject(pack.file("biomes_client.json"))
                .getAsJsonObject("biomes");
            if (biomes == null) return;

            for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;

                JsonObject body = entry.getValue().getAsJsonObject();
                AddonEntities.unsupportedFields("biomes_client.json", body, LEGACY_DROPPED);
                into.put(entry.getKey(), new ClientBiomeDefinition(
                    Json.colour(Json.text(body, "water_surface_color", null)), -1, -1, -1, -1));
            }
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read biomes_client.json: {}", error.toString());
        }
    }

    private static int colour(JsonObject components, String component, String field) {
        JsonObject body = components.getAsJsonObject(component);
        return body == null ? -1 : Json.colour(Json.text(body, field, null));
    }
}
