package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonObject;

/**
 * What `minecraft:despawn` says about how far a mob may be from a player before it is removed.
 *
 * Java asks the entity the same two questions Bedrock states here: whether it is far enough to go
 * at once, and whether it is far enough to roll for. The distances are blocks and the inactivity is
 * seconds, so only the timer changes units on the way across.
 */
public record Despawn(double minDistance, double maxDistance, int randomChance, int inactivityTicks,
    boolean fromDistance, boolean fromChance, boolean fromInactivity, boolean removeChildren,
    boolean filtered, Filter filters) {

    /** Read off the component the entity carries now, because a group may bring one in. */
    public static Despawn from(JsonObject body) {
        JsonObject distance = body.getAsJsonObject("despawn_from_distance");
        return new Despawn(
            ServerEntityDefinition.number(distance, "min_distance", 32),
            ServerEntityDefinition.number(distance, "max_distance", 128),
            (int) ServerEntityDefinition.number(body, "min_range_random_chance", 800),
            (int) Math.round(ServerEntityDefinition.number(body, "min_range_inactivity_timer", 30)
                * BedrockUnits.TICKS_PER_SECOND),
            ServerEntityDefinition.flag(body, "despawn_from_distance", true),
            ServerEntityDefinition.flag(body, "despawn_from_chance", true),
            ServerEntityDefinition.flag(body, "despawn_from_inactivity", true),
            ServerEntityDefinition.flag(body, "remove_child_entities", false),
            body.has("filters"),
            Filter.parse(body.get("filters")));
    }

    /**
     * Whether a mob this far from the nearest player goes.
     *
     * Bedrock removes it outright past the outer distance and rolls a one in `min_range_random_chance`
     * past the inner one, which is the same pair of questions Java asks of a vanilla mob. A pack
     * that wrote its own filters replaces those rules rather than adding to them.
     */
    public boolean despawnsAt(double distance, int idleTicks, double roll) {
        if (filtered || !fromDistance) return false;
        if (distance > maxDistance) return true;
        if (distance <= minDistance) return false;
        if (fromInactivity && idleTicks < inactivityTicks) return false;
        return fromChance && randomChance > 0 && roll < 1.0 / randomChance;
    }
}
