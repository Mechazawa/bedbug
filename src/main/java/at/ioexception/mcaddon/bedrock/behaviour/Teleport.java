package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What `minecraft:teleport` says about a mob that blinks about rather than walks.
 *
 * Bedrock states the reach as a cube in blocks and the rate as a time in seconds, and the two are
 * not interchangeable: a pack asking for a teleport every thirty seconds inside a sixteen block
 * cube is describing an enderman, while the same numbers read the other way round are a mob that
 * jumps thirty blocks several times a second.
 */
public record Teleport(double reachX, double reachY, double reachZ, double lightChance,
    double darkChance, int shortestIntervalTicks, int longestIntervalTicks) {

    /** Null when the entity does not teleport, which is what a pack switching it off asks for. */
    public static Teleport tryFrom(ServerEntityDefinition definition) {
        JsonObject body = definition.component("minecraft:teleport");
        if (body == null || !ServerEntityDefinition.flag(body, "random_teleports", true)) return null;

        // The cube is stated as a half reach per axis, the way Java's own enderman measures it.
        double[] cube = reach(body.get("random_teleport_cube"));
        int shortest = ticks(ServerEntityDefinition.number(body, "min_random_teleport_time", 0));
        int longest = ticks(ServerEntityDefinition.number(body, "max_random_teleport_time", 20));

        return new Teleport(cube[0], cube[1], cube[2],
            ServerEntityDefinition.number(body, "light_teleport_chance", 0.01),
            ServerEntityDefinition.number(body, "dark_teleport_chance", 0.01),
            Math.min(shortest, longest), Math.max(shortest, longest));
    }

    /** Seconds, which is what every interval in a Bedrock component is stated in. */
    private static int ticks(double seconds) {
        return (int) Math.round(Math.max(0, seconds) * 20);
    }

    private static double[] reach(JsonElement cube) {
        double[] axes = {32, 16, 32};
        if (cube == null || !cube.isJsonArray()) return axes;

        for (int i = 0; i < Math.min(3, cube.getAsJsonArray().size()); i++) {
            axes[i] = Math.abs(cube.getAsJsonArray().get(i).getAsDouble());
        }
        return axes;
    }

    /** How long until the next attempt, picked inside the window the pack gave. */
    public int nextDelayTicks(double roll) {
        return shortestIntervalTicks
            + (int) (roll * Math.max(0, longestIntervalTicks - shortestIntervalTicks));
    }

    /** Bedrock rolls a different chance by whether the mob stands in the light. */
    public double chanceAt(int lightLevel) {
        return lightLevel > 7 ? lightChance : darkChance;
    }
}
