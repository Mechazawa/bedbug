package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A world of a pack's own: an identifier, a generator and the band of height it occupies.
 *
 * Bedrock generates a dimension a pack declares as empty space and leaves the pack to build in it,
 * which is why `void` is the only generator type it accepts. The band is stated in two spellings.
 * `minecraft:dimension_bounds` gives the floor and the ceiling, `minecraft:dimension_height` gives
 * the floor and how tall the band is, and Java writes the second pair.
 */
public record DimensionDefinition(String identifier, String generatorType, String declaredBiome,
    int minimumY, int height) {

    /** The band Bedrock gives a dimension that states none, which is the overworld's own. */
    public static final int DEFAULT_MINIMUM_Y = -64;
    public static final int DEFAULT_HEIGHT = 384;

    /** Java counts a dimension's height in whole chunk sections. */
    public static final int SECTION = 16;

    /** What a Java dimension type accepts, out of `DimensionType`'s own bounds on the two fields. */
    private static final int LOWEST_Y = -2032;
    private static final int TALLEST = 4064;

    /** The only generator Bedrock builds a pack's own dimension with. */
    private static final String VOID = "void";

    public static List<DimensionDefinition> readAll(BedrockPack pack) {
        List<DimensionDefinition> dimensions = new ArrayList<>();
        for (Path file : pack.list("dimensions", ".json")) {
            try {
                DimensionDefinition dimension = read(Json.parseObject(file));
                if (dimension != null) dimensions.add(dimension);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return dimensions;
    }

    private static DimensionDefinition read(JsonObject root) {
        if (!root.has("minecraft:dimension") || !root.get("minecraft:dimension").isJsonObject()) {
            return null;
        }

        JsonObject body = root.getAsJsonObject("minecraft:dimension");
        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        if (identifier == null) return null;

        JsonObject components = body.getAsJsonObject("components");
        if (components == null) components = new JsonObject();

        JsonObject generation = components.getAsJsonObject("minecraft:generation");
        String generator = generation == null ? VOID : Json.text(generation, "generator_type", VOID);

        JsonObject named = components.getAsJsonObject("minecraft:default_biome");
        JsonObject bounds = components.getAsJsonObject("minecraft:dimension_bounds");
        JsonObject band = components.getAsJsonObject("minecraft:dimension_height");

        return new DimensionDefinition(identifier, generator,
            named == null ? null : Json.text(named, "biome", null),
            floor(bounds, band), height(bounds, band));
    }

    /**
     * The floor of the band, out of whichever of the two spellings the pack used.
     *
     * `dimension_bounds` states it as `min` and `dimension_height` as `min_y`, and Java takes it as
     * `min_y` either way.
     */
    private static int floor(JsonObject bounds, JsonObject band) {
        if (bounds != null) return sections(Json.number(bounds, "min", DEFAULT_MINIMUM_Y));
        if (band != null) return sections(Json.number(band, "min_y", DEFAULT_MINIMUM_Y));
        return DEFAULT_MINIMUM_Y;
    }

    /**
     * How tall the band is, out of whichever of the two spellings the pack used.
     *
     * `dimension_bounds` states the ceiling rather than the height, so the height is the distance
     * between the two. A pack writing a ceiling at or under its floor states no band at all and
     * takes the shortest one Java accepts.
     */
    private static int height(JsonObject bounds, JsonObject band) {
        int floor = floor(bounds, band);
        if (bounds != null) {
            int ceiling = sections(Json.number(bounds, "max", DEFAULT_MINIMUM_Y + DEFAULT_HEIGHT));
            return fitted(floor, ceiling - floor);
        }
        if (band != null) {
            return fitted(floor, sections(Json.number(band, "height_range", DEFAULT_HEIGHT)));
        }
        return DEFAULT_HEIGHT;
    }

    /** The whole chunk section a number falls in, because Java counts a dimension in sections. */
    private static int sections(double blocks) {
        return Math.floorDiv((int) Math.floor(blocks), SECTION) * SECTION;
    }

    /** A height Java accepts beside that floor: at least one section, and inside the world. */
    private static int fitted(int minimumY, int height) {
        int highest = Math.min(TALLEST, -LOWEST_Y - minimumY);
        return Math.max(SECTION, Math.min(highest, height));
    }

    /**
     * Whether this is a dimension Java can be given, naming what stops it when it is not.
     *
     * Bedrock builds a pack's own dimension as empty space, so any other generator name is one
     * neither edition has published a shape for and there is nothing to guess a Java generator out
     * of.
     */
    public boolean convertible() {
        if (VOID.equalsIgnoreCase(generatorType)) return true;

        AddonEntities.unsupported("dimension generator_type " + generatorType);
        return false;
    }

    /**
     * The biome the empty space is made of, which is the void where the pack names none.
     *
     * A `dimension` entry naming a biome out of the same pack loads on a dedicated server and stops
     * a client on the screen asking the player to fix their packs, so only a biome the game itself
     * ships is written and a pack's own is named instead.
     */
    public String biome() {
        if (declaredBiome == null) return "minecraft:the_void";

        String qualified = declaredBiome.indexOf(':') > 0
            ? declaredBiome : "minecraft:" + declaredBiome;
        if (qualified.startsWith("minecraft:")) return qualified;

        AddonEntities.unsupported("dimension default_biome from the pack's own biomes");
        return "minecraft:the_void";
    }
}
