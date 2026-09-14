package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonObject;

/**
 * What `minecraft:angry` says about a mob that stays angry for a while and then stops.
 *
 * The anger is a countdown in seconds with an event at the end of it, so an entity whose component
 * is read as a flag alone is angry for the rest of its life and never fires the event the pack
 * wrote for the moment it calms down.
 */
public record Anger(int durationTicks, int spreadTicks, String calmEvent, double broadcastRange,
    boolean broadcastAnger) {

    /** Null when the entity has no anger of its own. */
    public static Anger tryFrom(ServerEntityDefinition definition) {
        JsonObject body = definition.component("minecraft:angry");
        if (body == null) return null;

        return new Anger(
            ticks(ServerEntityDefinition.number(body, "duration", 25)),
            ticks(ServerEntityDefinition.number(body, "duration_delta", 0)),
            ServerEntityDefinition.event(body, "calm_event"),
            ServerEntityDefinition.number(body, "broadcast_range",
                ServerEntityDefinition.number(body, "broadcastRange", 20)),
            ServerEntityDefinition.flag(body, "broadcast_anger",
                ServerEntityDefinition.flag(body, "broadcastAnger", false)));
    }

    /** Seconds, as every duration in a Bedrock component is. A negative one means it never ends. */
    private static int ticks(double seconds) {
        return seconds < 0 ? -1 : (int) Math.round(seconds * 20);
    }

    /** How long this bout of anger lasts, spread over the window the pack allowed. */
    public int lifetimeTicks(double roll) {
        if (durationTicks < 0) return -1;
        return Math.max(0, durationTicks + (int) ((roll * 2 - 1) * spreadTicks));
    }
}
