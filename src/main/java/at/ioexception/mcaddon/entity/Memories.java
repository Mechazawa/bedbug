package at.ioexception.mcaddon.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * What a pack's entity remembers, for the goals that act on a memory rather than on a sight.
 *
 * Bedrock's `minecraft:memory_sensor.*` write into an entity's memory and its
 * `minecraft:memory_behavior.*` read one back, which is why the goals carry a priority and nothing
 * else: the target is in the memory. Mojang publishes the names and no fields for any of them, so
 * what a sensor stores and how long it keeps it are the defaults below.
 *
 * Java has a memory system of its own on `Mob.getBrain`, and it is a registry of memory module
 * types with a schedule and behaviour control attached. Nothing here needs that: a memory is a
 * value one component writes and another reads, and this holds those values.
 */
public final class Memories {
    /** How long a written memory stands before the entity has forgotten it, in ticks. */
    private static final int KEPT_TICKS = 200;

    private LivingEntity nearby;
    private int nearbyUntil;
    private BlockPos event;
    private int eventUntil;

    private static final java.util.List<String> COMPONENTS = java.util.List.of(
        "minecraft:memory_sensor.in_range_of_entity", "minecraft:memory_sensor.game_event",
        "minecraft:memory_behavior.follow_target", "minecraft:memory_behavior.flee_threat",
        "minecraft:memory_behavior.move_to_position");

    /** Null for an entity whose pack declared no memory component at all. */
    public static Memories tryFrom(AddonEntity mob) {
        return COMPONENTS.stream().anyMatch(mob::declares) ? new Memories() : null;
    }

    public void remember(LivingEntity seen, int tick) {
        nearby = seen;
        nearbyUntil = tick + KEPT_TICKS;
    }

    public void remember(BlockPos where, int tick) {
        event = where;
        eventUntil = tick + KEPT_TICKS;
    }

    /** The entity last written by a sensor, or null once it has gone or been forgotten. */
    public LivingEntity nearby(int tick) {
        if (nearby != null && (tick > nearbyUntil || !nearby.isAlive())) nearby = null;
        return nearby;
    }

    /** Where the last game event a sensor heard happened, or null once it has been forgotten. */
    public Vec3 event(int tick) {
        if (event != null && tick > eventUntil) event = null;
        return event == null ? null : Vec3.atCenterOf(event);
    }
}
