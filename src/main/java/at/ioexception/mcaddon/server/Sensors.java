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

            int minimum = (int) ServerEntityDefinition.number(spawn, "min_wait_time", 300);
            int maximum = (int) ServerEntityDefinition.number(spawn, "max_wait_time", 600);
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
     *
     * The delays are stated in seconds and hold the whole component back rather than one event, so
     * an entity that has just fired one waits before any of them can fire again.
     */
    private static void scheduler(AddonEntity entity, ServerEntityDefinition definition, AddonState state) {
        JsonObject scheduler = definition.components().get("minecraft:scheduler");
        if (scheduler == null || entity.tickCount < entity.scheduledFromTick()) return;

        for (JsonElement element : Json.list(scheduler, "scheduled_events")) {
            if (!element.isJsonObject()) continue;
            JsonObject scheduled = element.getAsJsonObject();
            String event = ServerEntityDefinition.event(scheduled, "event");
            if (event == null) continue;

            if (Filter.parse(scheduled.get("filters")).matches(
                new Filter.Context(entity, entity, null, state))) {
                entity.setScheduledFromTick(entity.tickCount + scheduleDelay(scheduler));
                EntityEvents.trigger(entity, event, null, state);
                return;
            }
        }
    }

    /** `min_delay_secs` and `max_delay_secs` are seconds, as every wait in a component is. */
    private static int scheduleDelay(JsonObject scheduler) {
        double shortest = ServerEntityDefinition.number(scheduler, "min_delay_secs", 0);
        double longest = Math.max(shortest,
            ServerEntityDefinition.number(scheduler, "max_delay_secs", shortest));
        return (int) ((shortest + java.util.concurrent.ThreadLocalRandom.current().nextDouble()
            * (longest - shortest)) * 20);
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

            double perTick = ServerEntityDefinition.number(condition, "damage_per_tick", 1);
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

        String event = ServerEntityDefinition.event(timer, "time_down_event");
        if (event == null) return;

        if (entity.timerDueTick() < 0) {
            entity.setTimerDueTick(entity.tickCount + delay(timer));
            return;
        }
        if (entity.tickCount < entity.timerDueTick()) return;

        EntityEvents.trigger(entity, event, null, state);
        entity.setTimerDueTick(ServerEntityDefinition.flag(timer, "looping", true)
            ? entity.tickCount + delay(timer) : Integer.MAX_VALUE);
    }

    /**
     * `time` is seconds, as a number or as the range to pick from.
     *
     * `randomInterval` decides what a range means: with it off the timer takes the lower bound every
     * time, which is how a pack asks a crowd of the same entity to act together rather than apart.
     * `random_time_choices` replaces the range with a weighted list of waits to draw from.
     */
    private static int delay(JsonObject timer) {
        List<JsonElement> choices = Json.list(timer, "random_time_choices");
        if (!choices.isEmpty()) return (int) (choose(choices) * 20);

        JsonElement time = timer.get("time");
        if (time == null) return 20;
        if (time.isJsonPrimitive()) return (int) (time.getAsDouble() * 20);
        if (!time.isJsonArray() || time.getAsJsonArray().size() < 2) return 20;

        double low = time.getAsJsonArray().get(0).getAsDouble();
        double high = time.getAsJsonArray().get(1).getAsDouble();
        if (!ServerEntityDefinition.flag(timer, "randomInterval", true)) return (int) (low * 20);
        return (int) ((low + java.util.concurrent.ThreadLocalRandom.current().nextDouble()
            * (high - low)) * 20);
    }

    /** One wait out of a weighted list, in seconds. */
    private static double choose(List<JsonElement> choices) {
        double total = choices.stream()
            .filter(JsonElement::isJsonObject)
            .mapToDouble(choice -> ServerEntityDefinition.number(choice.getAsJsonObject(), "weight", 1))
            .sum();

        double roll = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * total;
        double value = 1;
        for (JsonElement choice : choices) {
            if (!choice.isJsonObject()) continue;
            value = ServerEntityDefinition.number(choice.getAsJsonObject(), "value", 1);
            roll -= ServerEntityDefinition.number(choice.getAsJsonObject(), "weight", 1);
            if (roll <= 0) return value;
        }
        return value;
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

        // A pack asking for sight is asking for the loss of it to raise its own event instead.
        if (ServerEntityDefinition.flag(sensor, "must_see", false)
            && !entity.hasLineOfSight(entity.getTarget())) {
            String lost = ServerEntityDefinition.event(sensor, "on_vision_lost_inside_range");
            if (lost != null) EntityEvents.trigger(entity, lost, entity.getTarget(), state);
            return;
        }

        double distance = entity.distanceTo(entity.getTarget());
        double inside = ServerEntityDefinition.number(sensor, "inside_range", 1);
        double outside = ServerEntityDefinition.number(sensor, "outside_range", 5);

        String near = ServerEntityDefinition.event(sensor, "on_inside_range");
        String far = ServerEntityDefinition.event(sensor, "on_outside_range");
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

            // `relative_range` measures from the entity's own edge rather than from its middle,
            // which is what keeps a wide entity's reach the same as a narrow one's.
            double range = subsensor.has("range") ? radius(subsensor.get("range")) : 10;
            if (ServerEntityDefinition.flag(sensor, "relative_range", true)) {
                range += entity.getBbWidth() / 2;
            }

            int required = (int) ServerEntityDefinition.number(subsensor, "minimum_count", 1);
            int allowed = (int) ServerEntityDefinition.number(subsensor, "maximum_count", Integer.MAX_VALUE);
            boolean playersOnly = ServerEntityDefinition.flag(sensor, "find_players_only", false);
            Filter filter = Filter.parse(subsensor.get("event_filters"));

            AABB box = entity.getBoundingBox().inflate(range);
            List<Entity> candidates = entity.level().getEntities(entity, box);

            int found = 0;
            Entity first = null;
            for (Entity candidate : candidates) {
                if (playersOnly && !(candidate instanceof net.minecraft.world.entity.player.Player)) {
                    continue;
                }
                if (!filter.matches(new Filter.Context(entity, candidate, candidate, state))) continue;
                found++;
                if (first == null) first = candidate;
            }
            if (found >= required && found <= allowed) EntityEvents.trigger(entity, event, first, state);
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
