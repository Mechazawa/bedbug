package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.behaviour.DamageCause;
import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.state.AddonState;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The components that watch for something and fire an event when it happens.
 *
 * Bedrock has no Java equivalent for these: they are evaluators rather than goals. A pack leans on
 * them heavily, so without them an entity has its models and none of its reactions.
 *
 * Every one is read off the live set rather than off the definition, because a sensor is how a pack
 * arms a reaction it wants for a while: eighteen of the nineteen `minecraft:timer` declarations in
 * Mojang's own behaviour pack sit inside a component group, and a sensor read from the definition
 * runs for an entity whose group is off and never runs for one whose group brought it in.
 */
public final class Sensors {
    /**
     * How often the sensors are evaluated. Bedrock's own rate is not exact either.
     *
     * It is also the half second a hurt entity is invulnerable for, so a sensor that hurts something
     * lands once per window and needs no pacing of its own. A sensor reading a rate Bedrock states
     * per tick converts it through `BedrockUnits`, because asking a per-tick number once per
     * interval is a tenth of what the pack wrote.
     */
    public static final int INTERVAL_TICKS = 10;

    /** The widest a `minecraft:block_sensor` may watch, which bounds the search a break costs. */
    private static final int BLOCK_SENSOR_REACH = 32;

    /** Whether any loaded pack watches blocks at all, so a break costs nothing when none does. */
    private static Boolean watchingBlocks;

    private Sensors() {
    }

    public static void tick(AddonEntity entity, AddonState state) {
        if (entity.definition() == null || state == null) return;

        environment(entity, state);
        nearby(entity, state);
        timer(entity, state);
        scheduler(entity, state);
        hurtOnCondition(entity, state);
        dryingOut(entity, state);
        spawnEntity(entity, state);
        calmDown(entity, state);
    }

    /**
     * `minecraft:angry` keeps the entity angry for the time it names and then calms it down again.
     *
     * The event a pack names is what removes the group, so the count has to end even for an entity
     * nothing provokes again: without it a bee stung once stays angry for as long as it lives.
     * `duration_delta` spreads the calm over a crowd so a swarm does not settle on the same tick.
     */
    private static void calmDown(AddonEntity entity, AddonState state) {
        JsonObject angry = entity.liveComponent("minecraft:angry");
        if (angry == null) {
            entity.setCalmAtTick(-1);
            return;
        }

        if (entity.calmAtTick() < 0) {
            double seconds = number(angry, "duration", 25);
            double spread = number(angry, "duration_delta", 0);
            entity.setCalmAtTick(entity.tickCount + (int) ((seconds
                + (spread > 0 ? ThreadLocalRandom.current().nextDouble(-spread, spread) : 0)) * 20));
            return;
        }
        if (entity.tickCount < entity.calmAtTick()) return;

        entity.setCalmAtTick(-1);
        raise(entity, eventName(angry, "calm_event"), null, state);
    }

    /**
     * `minecraft:spawn_entity` has an entity produce another entity, or an item, of its own accord.
     *
     * The wait is in seconds and randomised between its two bounds, which is what keeps a group of
     * the same entity from all producing something on the same tick.
     */
    private static void spawnEntity(AddonEntity entity, AddonState state) {
        JsonObject component = entity.liveComponent("minecraft:spawn_entity");
        if (component == null || !(entity.level() instanceof ServerLevel level)) return;

        int index = 0;
        for (JsonElement element : Json.list(component, "entities")) {
            String key = "spawn_entity:" + index++;
            if (!element.isJsonObject()) continue;
            JsonObject spawn = element.getAsJsonObject();

            if (!Filter.parse(spawn.get("filters")).matches(
                new Filter.Context(entity, entity, null, state))) {
                continue;
            }
            if (!due(spawn)) continue;
            if (flag(spawn, "single_use", false) && !entity.spend(key)) continue;

            produce(entity, level, spawn, state);
        }
    }

    /**
     * Whether this evaluation is the one the pack's wait lands on.
     *
     * A chance per evaluation rather than a countdown per entity: it needs no state of its own and
     * averages out to the wait the pack asked for.
     */
    private static boolean due(JsonObject spawn) {
        double minimum = number(spawn, "min_wait_time", 300);
        double maximum = number(spawn, "max_wait_time", 600);
        double expected = Math.max(1, (minimum + maximum) / 2) * 20 / INTERVAL_TICKS;
        return ThreadLocalRandom.current().nextDouble() <= 1 / expected;
    }

    public static void produce(AddonEntity entity, ServerLevel level, JsonObject spawn, AddonState state) {
        String sound = Json.text(spawn, "spawn_sound", null);
        if (sound != null) play(level, entity, sound);

        int count = Math.max(1, (int) number(spawn, "num_to_spawn", 1));
        String item = Json.text(spawn, "spawn_item", null);
        if (item != null) {
            drop(entity, level, item, count, Json.text(spawn, "spawn_item_event", null), state);
            return;
        }

        String identifier = Json.text(spawn, "spawn_entity", null);
        EntityType<?> type = identifier == null ? null : AddonEntities.typeOf(identifier);
        if (type == null) {
            if (identifier != null) AddonEntities.unsupported("spawn_entity " + identifier);
            return;
        }

        String event = Json.text(spawn, "spawn_event", null);
        for (int i = 0; i < count; i++) {
            Entity spawned = Platform.createMob(type, level);
            if (spawned == null) return;

            Platform.placeAt(spawned, entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), 0);
            level.addFreshEntity(spawned);
            if (flag(spawn, "should_leash", false)) Platform.leashTo(spawned, entity);
            if (event != null) EntityEvents.trigger(spawned, event, entity, state);
        }
    }

    /** `spawn_item` is the other half of the same component: an egg, a scute, a shed feather. */
    private static void drop(AddonEntity entity, ServerLevel level, String name, int count,
        String event, AddonState state) {
        Item item = AddonItems.named(name);
        if (item == null) {
            AddonEntities.unsupported("spawn_entity item " + name);
            return;
        }

        ItemEntity dropped = new ItemEntity(level, entity.getX(), entity.getY() + 0.5, entity.getZ(),
            new ItemStack(item, count));
        level.addFreshEntity(dropped);
        if (event != null) EntityEvents.trigger(entity, event, dropped, state);
    }

    private static void play(ServerLevel level, Entity at, String sound) {
        Identifier id = Identifier.tryParse(sound.contains(":") ? sound : "minecraft:" + sound);
        if (id == null) return;

        BuiltInRegistries.SOUND_EVENT.getOptional(id).ifPresent(event ->
            level.playSound(null, at.getX(), at.getY(), at.getZ(), event, SoundSource.NEUTRAL, 1, 1));
    }

    /**
     * `minecraft:drying_out_timer` counts how long an entity has been out of water.
     *
     * The count goes back to nothing the moment the entity is in water again, and which event that
     * raises depends on whether the drying had finished: one event for interrupting it, another for
     * recovering from having dried out.
     */
    private static void dryingOut(AddonEntity entity, AddonState state) {
        JsonObject component = entity.liveComponent("minecraft:drying_out_timer");
        if (component == null) return;

        int total = (int) (number(component, "total_time", 300) * 20);
        if (entity.isInWater()) {
            if (entity.dryTicks() <= 0) return;

            String event = entity.dryTicks() >= total
                ? "recover_after_dried_out_event" : "stopped_drying_out_event";
            entity.setDryTicks(0);
            raise(entity, eventName(component, event), null, state);
            return;
        }

        int dry = entity.dryTicks() + INTERVAL_TICKS;
        entity.setDryTicks(dry);
        if (dry >= total && dry - INTERVAL_TICKS < total) {
            raise(entity, eventName(component, "dried_out_event"), null, state);
        }
    }

    /**
     * `minecraft:scheduler` fires its events when their filters hold, which a pack uses for the
     * things that should happen at a time of day or in certain weather.
     *
     * The delay fields hold it back between runs, so a pack's villager schedule is not re-decided
     * every time the sensors come round.
     */
    private static void scheduler(AddonEntity entity, AddonState state) {
        JsonObject scheduler = entity.liveComponent("minecraft:scheduler");
        if (scheduler == null || entity.tickCount < entity.schedulerDueTick()) return;

        int low = (int) (number(scheduler, "min_delay_secs", 0) * 20);
        int high = (int) (number(scheduler, "max_delay_secs", 0) * 20);
        entity.setSchedulerDueTick(entity.tickCount + low
            + (high > low ? ThreadLocalRandom.current().nextInt(high - low) : 0));

        for (JsonElement element : Json.list(scheduler, "scheduled_events")) {
            if (!element.isJsonObject()) continue;
            JsonObject scheduled = element.getAsJsonObject();
            String event = eventName(scheduled, "event");
            if (event == null) continue;

            if (Filter.parse(scheduled.get("filters")).matches(
                new Filter.Context(entity, entity, null, state))) {
                raise(entity, event, null, state);
                return;
            }
        }
    }

    /**
     * `minecraft:hurt_on_condition` is how a pack makes its entity take damage from where it is.
     *
     * `damage_per_tick` is the damage of one hit rather than a rate: every vanilla entity writes
     * four against lava, and four is what lava takes off in both editions each time it lands. What
     * paces it is the half second a hurt entity is invulnerable for, which is also the interval this
     * runs on.
     */
    private static void hurtOnCondition(AddonEntity entity, AddonState state) {
        JsonObject component = entity.liveComponent("minecraft:hurt_on_condition");
        if (component == null || !(entity.level() instanceof ServerLevel level)) return;

        for (JsonElement element : Json.list(component, "damage_conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = element.getAsJsonObject();
            if (!Filter.parse(condition.get("filters")).matches(
                new Filter.Context(entity, entity, null, state))) {
                continue;
            }

            DamageCause cause = DamageCause.named(Json.text(condition, "cause", "none"));
            Platform.hurt(entity, level,
                (cause == null ? DamageCause.NONE : cause).source(level.registryAccess(), null),
                (float) number(condition, "damage_per_tick", 1));
        }
    }

    /**
     * `minecraft:timer` fires its event after a delay, and again if it loops.
     *
     * The delay is a number, a range to pick from, or a weighted list of choices, which is how a
     * pack staggers a crowd of the same entity rather than having all of them act on the same tick.
     */
    private static void timer(AddonEntity entity, AddonState state) {
        JsonObject timer = entity.liveComponent("minecraft:timer");
        // A group leaving takes the count with it, so the group arriving again starts a fresh wait
        // rather than firing at once on a deadline the entity passed while the timer was off.
        if (timer == null) {
            entity.setTimerDueTick(-1);
            return;
        }

        String event = eventName(timer, "time_down_event");
        if (event == null) return;

        if (entity.timerDueTick() < 0) {
            entity.setTimerDueTick(entity.tickCount + delay(timer));
            return;
        }
        if (entity.tickCount < entity.timerDueTick()) return;

        EntityEvents.trigger(entity, event, null, state);
        boolean looping = flag(timer, "looping", true);
        entity.setTimerDueTick(looping ? entity.tickCount + delay(timer) : Integer.MAX_VALUE);
    }

    /**
     * `time` is seconds, as a number or as the range to pick from.
     *
     * `randomInterval` is what decides whether a range is rolled or its low end is taken every time,
     * and `random_time_choices` replaces the range outright with a weighted list.
     */
    private static int delay(JsonObject timer) {
        List<JsonElement> choices = Json.list(timer, "random_time_choices");
        if (!choices.isEmpty()) return (int) (weighted(choices) * 20);

        JsonElement time = timer.get("time");
        if (time == null) return 20;
        if (time.isJsonPrimitive()) return (int) (time.getAsDouble() * 20);
        if (!time.isJsonArray() || time.getAsJsonArray().size() < 2) return 20;

        double low = time.getAsJsonArray().get(0).getAsDouble();
        double high = time.getAsJsonArray().get(1).getAsDouble();
        if (!flag(timer, "randomInterval", true)) return (int) (low * 20);
        return (int) ((low + ThreadLocalRandom.current().nextDouble() * (high - low)) * 20);
    }

    private static double weighted(List<JsonElement> choices) {
        double total = choices.stream()
            .filter(JsonElement::isJsonObject)
            .mapToDouble(choice -> number(choice.getAsJsonObject(), "weight", 1))
            .sum();
        if (total <= 0) return 1;

        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (JsonElement choice : choices) {
            if (!choice.isJsonObject()) continue;
            roll -= number(choice.getAsJsonObject(), "weight", 1);
            if (roll <= 0) return number(choice.getAsJsonObject(), "value", 1);
        }
        return 1;
    }

    /** `minecraft:environment_sensor` fires an event whenever its own filters hold. */
    private static void environment(AddonEntity entity, AddonState state) {
        JsonObject sensor = entity.liveComponent("minecraft:environment_sensor");
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
     * Fires the events a pack asked for when its target comes within reach, or leaves it.
     *
     * Bedrock measures both distances from the entity's own target rather than from anything nearby,
     * so an entity with nothing to hunt raises neither. `must_see` is what separates a creeper that
     * stops swelling because the player walked away from one that lost sight of them.
     */
    private static void target(AddonEntity entity, AddonState state) {
        JsonObject sensor = entity.liveComponent("minecraft:target_nearby_sensor");
        if (sensor == null || entity.getTarget() == null) return;

        LivingEntity target = entity.getTarget();
        double distance = entity.distanceTo(target);
        double inside = number(sensor, "inside_range", 1);
        double outside = number(sensor, "outside_range", 5);
        boolean seen = !flag(sensor, "must_see", false) || entity.hasLineOfSight(target);

        String lost = eventName(sensor, "on_vision_lost_inside_range");
        if (distance <= inside && !seen && lost != null) {
            EntityEvents.trigger(entity, lost, target, state);
            return;
        }

        String near = eventName(sensor, "on_inside_range");
        String far = eventName(sensor, "on_outside_range");
        if (near != null && distance <= inside && seen) {
            EntityEvents.trigger(entity, near, target, state);
        } else if (far != null && distance >= outside) {
            EntityEvents.trigger(entity, far, target, state);
        }
    }

    /**
     * `minecraft:entity_sensor` fires when enough of something matching its filters is close enough.
     *
     * The big entities in a real pack carry dozens of these, so the box query is one per subsensor
     * rather than one per candidate, and a subsensor naming a cooldown is skipped outright while it
     * is still running.
     */
    private static void nearby(AddonEntity entity, AddonState state) {
        // `target_nearby_sensor` is the same question asked about whatever the entity is hunting,
        // and Bedrock writes it as a component of its own rather than as one more subsensor.
        target(entity, state);

        JsonObject sensor = entity.liveComponent("minecraft:entity_sensor");
        if (sensor == null) return;

        boolean playersOnly = flag(sensor, "find_players_only", false);
        boolean relative = flag(sensor, "relative_range", false);

        int index = 0;
        for (JsonElement element : Json.list(sensor, "subsensors")) {
            String key = "entity_sensor:" + index++;
            if (!element.isJsonObject()) continue;
            JsonObject subsensor = element.getAsJsonObject();

            String event = Json.text(subsensor, "event", null);
            if (event == null) continue;
            if (!entity.sensorReady(key)) continue;

            found(entity, subsensor, playersOnly, relative, state).ifPresent(other -> {
                if (subsensor.has("cooldown")) entity.startSensorCooldown(key, number(subsensor, "cooldown", 0));
                EntityEvents.trigger(entity, event, other, state);
            });
        }
    }

    /**
     * The first entity a subsensor counts, when it counted as many as the pack asked for.
     *
     * `minimum_count` of zero with a `maximum_count` of zero is how a pack asks about the absence of
     * something, so the count has to be bounded from above as well as below, and the answer may be
     * present with nothing in it.
     */
    private static Optional<Entity> found(AddonEntity entity, JsonObject subsensor,
        boolean playersOnly, boolean relative, AddonState state) {
        double horizontal = reach(subsensor.get("range"), 0, 10);
        double vertical = reach(subsensor.get("range"), 1, horizontal);
        if (relative) {
            horizontal += entity.getBbWidth() / 2;
            vertical += entity.getBbHeight() / 2;
        }

        AABB box = entity.getBoundingBox().inflate(horizontal, vertical, horizontal)
            .move(0, number(subsensor, "y_offset", 0), 0);
        Filter filter = Filter.parse(subsensor.get("event_filters"));

        List<? extends Entity> candidates = playersOnly
            ? entity.level().getEntitiesOfClass(Player.class, box)
            : entity.level().getEntities(entity, box);

        int required = (int) number(subsensor, "minimum_count", 1);
        int allowed = (int) number(subsensor, "maximum_count", Integer.MAX_VALUE);
        int found = 0;
        Entity first = null;
        for (Entity candidate : candidates) {
            if (!filter.matches(new Filter.Context(entity, candidate, candidate, state))) continue;

            found++;
            if (first == null) first = candidate;
            if (found > allowed) return Optional.empty();
        }
        return found >= required
            ? Optional.of(first == null ? entity : first) : Optional.empty();
    }

    /** `range` is a number or a pair of horizontal and vertical reach. */
    private static double reach(JsonElement range, int part, double fallback) {
        if (range == null) return fallback;
        if (range.isJsonPrimitive()) return range.getAsDouble();
        if (!range.isJsonArray() || range.getAsJsonArray().size() <= part) return fallback;
        return range.getAsJsonArray().get(part).getAsDouble();
    }

    /**
     * `minecraft:block_sensor` tells an entity that a block it cares about was broken near it.
     *
     * A break is common and a box query is not free, so the first one asks whether any loaded pack
     * watches blocks at all, and every break after that costs nothing when none does.
     */
    public static void blockBroken(Level level, Player breaker, BlockPos pos, BlockState broken) {
        if (!(level instanceof ServerLevel)) return;
        if (watchingBlocks == null) watchingBlocks = AddonEntities.anyDeclares("minecraft:block_sensor");
        if (!watchingBlocks) return;

        AddonState state = ServerHooks.state();
        if (state == null) return;

        String name = AddonBlocks.identifierOf(broken);
        AABB box = new AABB(pos).inflate(BLOCK_SENSOR_REACH);
        for (AddonEntity entity : level.getEntitiesOfClass(AddonEntity.class, box)) {
            JsonObject sensor = entity.liveComponent("minecraft:block_sensor");
            if (sensor == null) continue;

            double radius = number(sensor, "sensor_radius", 16);
            if (entity.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > radius * radius) {
                continue;
            }
            if (!Filter.parse(sensor.get("sources")).matches(
                new Filter.Context(entity, breaker, null, state))) {
                continue;
            }
            broke(entity, sensor, name, breaker, state);
        }
    }

    private static void broke(AddonEntity entity, JsonObject sensor, String block, Player breaker,
        AddonState state) {
        for (JsonElement element : Json.list(sensor, "on_break")) {
            if (!element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();

            boolean named = Json.list(rule, "block_list").stream()
                .filter(JsonElement::isJsonPrimitive)
                .anyMatch(listed -> block.equals(AddonBlocks.identifierOf(listed.getAsString())));
            if (!named) continue;

            raise(entity, Json.text(rule, "on_block_broken", null), breaker, state);
        }
    }

    private static void raise(AddonEntity entity, String event, Entity other, AddonState state) {
        if (event != null) EntityEvents.trigger(entity, event, other, state);
    }

    /** A pack writes an event either as a bare name or as the object carrying one. */
    private static String eventName(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null) return null;
        if (value.isJsonPrimitive()) return value.getAsString();
        return value.isJsonObject() ? Json.text(value.getAsJsonObject(), "event", null) : null;
    }

    /**
     * A pack writes `true` where a number belongs and a number where a flag does, and Gson answers
     * either with a thrown exception rather than a value, which would cost the whole sensor pass.
     */
    private static boolean flag(JsonObject json, String key, boolean fallback) {
        return ServerEntityDefinition.flag(json, key, fallback);
    }

    private static double number(JsonObject json, String key, double fallback) {
        return ServerEntityDefinition.number(json, key, fallback);
    }
}
