package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fog one `fogs/` file paints, as much of it as a Java biome has a field for.
 *
 * Bedrock states a fog per camera location: the `air` a player stands in, the `water` they swim in,
 * and four more for weather, lava and powder snow. Java carries one fog colour and one water fog
 * colour on a biome, so those two locations land here. The other four are not biome data in Java:
 * the client reads them from the pack's fog file while the camera is in that medium
 * (`bedrock.fog.FogDefinition`), so they are read and not reported here.
 *
 * A distance is either a count of blocks or a fraction of the render distance, which is what
 * `render_distance_type` says. Java states its own in blocks, so a fraction has no value to write
 * and is named instead.
 */
public record FogDefinition(Location air, Location water) {

    /** No fog at all, which leaves the game's own where it is. */
    public static final FogDefinition NONE = new FogDefinition(Location.NONE, Location.NONE);

    /** The camera locations a Java biome has a field for. */
    private static final Set<String> READ = Set.of("air", "water");

    /** The locations the client draws from the fog file itself. */
    private static final List<String> CLIENT_DRAWN =
        List.of("lava", "lava_resistance", "powder_snow", "weather");

    /** What a fog states beside its distances, which is the deferred renderer's half. */
    private static final List<String> DROPPED = List.of("volumetric");

    /** The fade one location states for the moment the camera enters it. */
    private static final List<String> LOCATION_DROPPED = List.of("transition_fog");

    /**
     * One camera location: the colour it paints, and where what it paints begins and ends.
     *
     * A colour or a distance of -1 is one the file does not state, or one stated as a fraction of
     * the render distance, which Java has no field to hold.
     */
    public record Location(int colour, float start, float end) {
        /** Nothing stated for this location. */
        public static final Location NONE = new Location(-1, -1, -1);

        /** Whether anything here is worth writing. */
        public boolean painted() {
            return colour >= 0 || start >= 0 || end >= 0;
        }
    }

    /** Every fog the resource pack defines, by identifier. */
    public static Map<String, FogDefinition> readAll(BedrockPack pack) {
        Map<String, FogDefinition> found = new LinkedHashMap<>();

        for (Path file : pack.list("fogs", ".json")) {
            try {
                read(Json.parseObject(file), found);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return found;
    }

    private static void read(JsonObject root, Map<String, FogDefinition> into) {
        JsonObject body = root.has("minecraft:fog_settings")
            && root.get("minecraft:fog_settings").isJsonObject()
            ? root.getAsJsonObject("minecraft:fog_settings") : null;
        if (body == null) return;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        JsonObject distance = body.getAsJsonObject("distance");
        if (identifier == null || distance == null) return;

        AddonEntities.unsupportedFields("fog", body, DROPPED);
        distance.keySet().stream().filter(name -> !READ.contains(name) && !CLIENT_DRAWN.contains(name))
            .forEach(name -> AddonEntities.unsupported("fog distance " + name));
        CLIENT_DRAWN.stream().filter(distance::has).forEach(name ->
            AddonEntities.unsupportedFields("fog " + name, distance.getAsJsonObject(name),
                LOCATION_DROPPED));

        into.put(identifier,
            new FogDefinition(location(distance, "air"), location(distance, "water")));
    }

    private static Location location(JsonObject distance, String name) {
        JsonObject body = distance.getAsJsonObject(name);
        if (body == null) return Location.NONE;

        AddonEntities.unsupportedFields("fog " + name, body, LOCATION_DROPPED);

        boolean blocks = "fixed".equals(Json.text(body, "render_distance_type", "render"));
        if (!blocks && (body.has("fog_start") || body.has("fog_end"))) {
            AddonEntities.unsupported("fog " + name + " distance as a fraction of the render "
                + "distance, which Java states in blocks");
        }

        return new Location(Json.colour(Json.text(body, "fog_color", null)),
            blocks ? (float) Json.number(body, "fog_start", -1) : -1,
            blocks ? (float) Json.number(body, "fog_end", -1) : -1);
    }
}
