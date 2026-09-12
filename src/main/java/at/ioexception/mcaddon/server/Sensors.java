package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.state.AddonState;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The components that watch for something and fire an event when it happens.
 *
 * Bedrock has no Java equivalent for these: they are evaluators rather than goals. A pack leans on
 * them heavily, so without them an entity has its models and none of its reactions.
 */
public final class Sensors {
    /** How often the sensors are evaluated. Bedrock's own rate is not exact either. */
    public static final int INTERVAL_TICKS = 10;

    private Sensors() {
    }

    public static void tick(AddonEntity entity, AddonState state) {
        ServerEntityDefinition definition = entity.definition();
        if (definition == null || state == null) return;

        environment(entity, definition, state);
        nearby(entity, definition, state);
        timer(entity, definition, state);
    }

    /**
     * `minecraft:timer` fires its event after a delay, and again if it loops.
     *
     * The delay is a number or a range to pick from, which is how a pack staggers a crowd of the same
     * entity rather than having all of them act on the same tick.
     */
    private static void timer(AddonEntity entity, ServerEntityDefinition definition, AddonState state) {
        JsonObject timer = definition.components().get("minecraft:timer");
        if (timer == null) return;

        JsonObject trigger = timer.getAsJsonObject("time_down_event");
        String event = trigger == null ? null : Json.text(trigger, "event", null);
        if (event == null) return;

        if (entity.timerDueTick() < 0) {
            entity.setTimerDueTick(entity.tickCount + delay(timer));
            return;
        }
        if (entity.tickCount < entity.timerDueTick()) return;

        EntityEvents.trigger(entity, event, null, state);
        boolean looping = !timer.has("looping") || timer.get("looping").getAsBoolean();
        entity.setTimerDueTick(looping ? entity.tickCount + delay(timer) : Integer.MAX_VALUE);
    }

    /** `time` is seconds, as a number or as the range to pick from. */
    private static int delay(JsonObject timer) {
        JsonElement time = timer.get("time");
        if (time == null) return 20;
        if (time.isJsonPrimitive()) return (int) (time.getAsDouble() * 20);

        if (time.isJsonArray() && time.getAsJsonArray().size() >= 2) {
            double low = time.getAsJsonArray().get(0).getAsDouble();
            double high = time.getAsJsonArray().get(1).getAsDouble();
            return (int) ((low + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * (high - low)) * 20);
        }
        return 20;
    }

    /** `minecraft:environment_sensor` fires an event whenever its own filters start holding. */
    private static void environment(AddonEntity entity, ServerEntityDefinition definition, AddonState state) {
        JsonObject sensor = definition.components().get("minecraft:environment_sensor");
        if (sensor == null) return;

        for (JsonElement element : Json.list(sensor, "triggers")) {
            if (!element.isJsonObject()) continue;
            JsonObject trigger = element.getAsJsonObject();
            String event = Json.text(trigger, "event", null);
            if (event == null) continue;

            if (Filter.parse(trigger.get("filters")).matches(
                new Filter.Context(entity, entity, null, state))) {
                EntityEvents.trigger(entity, event, null, state);
            }
        }
    }

    /**
     * `minecraft:entity_sensor` fires when something matching its filters is close enough.
     *
     * The big entities in a real pack carry dozens of these, so the range test comes before the
     * filter tree and the search is one box query per subsensor rather than one per candidate.
     */
    private static void nearby(AddonEntity entity, ServerEntityDefinition definition, AddonState state) {
        JsonObject sensor = definition.components().get("minecraft:entity_sensor");
        if (sensor == null) return;

        for (JsonElement element : Json.list(sensor, "subsensors")) {
            if (!element.isJsonObject()) continue;
            JsonObject subsensor = element.getAsJsonObject();
            String event = Json.text(subsensor, "event", null);
            if (event == null) continue;

            double range = subsensor.has("range") ? radius(subsensor.get("range")) : 10;
            int required = subsensor.has("minimum_count") ? subsensor.get("minimum_count").getAsInt() : 1;
            Filter filter = Filter.parse(subsensor.get("event_filters"));

            AABB box = entity.getBoundingBox().inflate(range);
            List<Entity> candidates = entity.level().getEntities(entity, box);

            int found = 0;
            Entity first = null;
            for (Entity candidate : candidates) {
                if (!filter.matches(new Filter.Context(entity, candidate, candidate, state))) continue;
                found++;
                if (first == null) first = candidate;
            }
            if (found >= required) EntityEvents.trigger(entity, event, first, state);
        }
    }

    /** `range` is a number or a pair of horizontal and vertical reach. */
    private static double radius(JsonElement range) {
        if (range.isJsonArray() && !range.getAsJsonArray().isEmpty()) {
            return range.getAsJsonArray().get(0).getAsDouble();
        }
        return range.isJsonPrimitive() ? range.getAsDouble() : 10;
    }
}
