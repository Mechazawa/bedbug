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
        scheduler(entity, definition, state);
        hurtOnCondition(entity, definition, state);
        spawnEntity(entity, definition, state);
    }

    /**
     * `minecraft:spawn_entity` has an entity spawn others of its own accord.
     *
     * The wait is in seconds and randomised between its two bounds, which is what keeps a group of
     * the same entity from all spawning something on the same tick.
     */
    private static void spawnEntity(AddonEntity entity, ServerEntityDefinition definition,
        AddonState state) {
        JsonObject component = definition.components().get("minecraft:spawn_entity");
        if (component == null || !(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        for (JsonElement element : Json.list(component, "entities")) {
            if (!element.isJsonObject()) continue;
            JsonObject spawn = element.getAsJsonObject();

            String identifier = Json.text(spawn, "spawn_entity", null);
            if (identifier == null) continue;

            int minimum = spawn.has("min_wait_time") ? spawn.get("min_wait_time").getAsInt() : 300;
            int maximum = spawn.has("max_wait_time") ? spawn.get("max_wait_time").getAsInt() : 600;
            // Chance per evaluation rather than a countdown per entity, which needs no state of its
            // own and averages out to the wait the pack asked for.
            double expected = Math.max(1, (minimum + maximum) / 2.0) * 20 / INTERVAL_TICKS;
            if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > 1 / expected) continue;

            net.minecraft.world.entity.EntityType<?> type =
                at.ioexception.mcaddon.registry.AddonEntities.typeOf(identifier);
            if (type == null) continue;

            net.minecraft.world.entity.Entity spawned =
                at.ioexception.mcaddon.platform.Platform.createMob(type, level);
            if (spawned == null) continue;

            at.ioexception.mcaddon.platform.Platform.placeAt(spawned,
                entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), 0);
            level.addFreshEntity(spawned);

            String event = Json.text(spawn, "spawn_event", null);
            if (event != null) EntityEvents.trigger(spawned, event, entity, state);
        }
    }

    /**
     * `minecraft:scheduler` fires its events when their filters hold, which a pack uses for the
     * things that should happen at a time of day or in certain weather.
     */
    private static void scheduler(AddonEntity entity, ServerEntityDefinition definition, AddonState state) {
        JsonObject scheduler = definition.components().get("minecraft:scheduler");
        if (scheduler == null) return;

        for (JsonElement element : Json.list(scheduler, "scheduled_events")) {
            if (!element.isJsonObject()) continue;
            JsonObject scheduled = element.getAsJsonObject();
            String event = Json.text(scheduled, "event", null);
            if (event == null) continue;

            if (Filter.parse(scheduled.get("filters")).matches(
                new Filter.Context(entity, entity, null, state))) {
                EntityEvents.trigger(entity, event, null, state);
            }
        }
    }

    /** `minecraft:hurt_on_condition` is how a pack makes its entity take damage from where it is. */
    private static void hurtOnCondition(AddonEntity entity, ServerEntityDefinition definition,
        AddonState state) {
        JsonObject component = definition.components().get("minecraft:hurt_on_condition");
        if (component == null || !(entity.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        for (JsonElement element : Json.list(component, "damage_conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = element.getAsJsonObject();
            if (!Filter.parse(condition.get("filters")).matches(
                new Filter.Context(entity, entity, null, state))) {
                continue;
            }

            double perTick = condition.has("damage_per_tick")
                ? condition.get("damage_per_tick").getAsDouble() : 1;
            at.ioexception.mcaddon.platform.Platform.hurt(entity, level,
                level.damageSources().generic(), (float) (perTick * INTERVAL_TICKS));
        }
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
    /**
     * Fires the events a pack asked for when its target comes within reach, or leaves it.
     *
     * Bedrock measures both distances from the entity's own target rather than from anything nearby,
     * so an entity with nothing to hunt raises neither.
     */
    private static void target(AddonEntity entity, ServerEntityDefinition definition, AddonState state) {
        JsonObject sensor = definition.components().get("minecraft:target_nearby_sensor");
        if (sensor == null || entity.getTarget() == null) return;

        double distance = entity.distanceTo(entity.getTarget());
        double inside = sensor.has("inside_range") ? sensor.get("inside_range").getAsDouble() : 1;
        double outside = sensor.has("outside_range") ? sensor.get("outside_range").getAsDouble() : 5;

        String near = Json.text(sensor, "on_inside_range", null);
        String far = Json.text(sensor, "on_outside_range", null);
        if (near != null && distance <= inside) {
            EntityEvents.trigger(entity, near, entity.getTarget(), state);
        } else if (far != null && distance >= outside) {
            EntityEvents.trigger(entity, far, entity.getTarget(), state);
        }
    }

    private static void nearby(AddonEntity entity, ServerEntityDefinition definition, AddonState state) {
        // `target_nearby_sensor` is the same question asked about whatever the entity is hunting,
        // and Bedrock writes it as a component of its own rather than as one more subsensor.
        target(entity, definition, state);

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
