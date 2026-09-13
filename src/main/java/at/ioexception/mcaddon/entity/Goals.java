package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads the values a behaviour component carries, in every shape Bedrock lets a pack write them.
 *
 * A range is a pair, an object with a min and a max, or a bare number meaning both ends; a trigger is
 * an object naming an event or the event name on its own. Each goal in this package would otherwise
 * carry its own copy of that.
 */
final class Goals {
    /** Bedrock counts a goal's waits and durations in seconds, and Java counts them in ticks. */
    static final int TICKS_PER_SECOND = 20;

    private Goals() {
    }

    /** How high one hop carries, which is the height a rabbit and a slime both clear. */
    static final double HOP_HEIGHT = 0.42;

    /** How far forward one hop pushes at a speed multiplier of one. */
    static final double HOP_REACH = 0.3;

    /**
     * Leaves the ground along whatever the entity is facing.
     *
     * Bedrock has two ways of describing something that hops rather than walks, and both come to
     * this: turn, then push off. Nothing happens while the entity is already in the air.
     */
    static void hop(net.minecraft.world.entity.Mob mob, double speed) {
        if (!mob.onGround()) return;

        double facing = Math.toRadians(mob.getYRot());
        mob.setDeltaMovement(-Math.sin(facing) * HOP_REACH * speed, HOP_HEIGHT,
            Math.cos(facing) * HOP_REACH * speed);
    }

    static double number(JsonObject component, String key, double fallback) {
        JsonElement value = component == null ? null : component.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsDouble();
    }

    static boolean flag(JsonObject component, String key, boolean fallback) {
        JsonElement value = component == null ? null : component.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsBoolean();
    }

    static String text(JsonObject component, String key) {
        return component == null ? null : Json.text(component, key, null);
    }

    static int ticks(JsonObject component, String key, double seconds) {
        return (int) (number(component, key, seconds) * TICKS_PER_SECOND);
    }

    static int ticksLow(JsonObject component, String key, double seconds) {
        return (int) (low(component, key, seconds) * TICKS_PER_SECOND);
    }

    static int ticksHigh(JsonObject component, String key, double seconds) {
        return (int) (high(component, key, seconds) * TICKS_PER_SECOND);
    }

    /** Somewhere inside a range a pack gave both ends of, which may be the same number twice. */
    static int between(net.minecraft.util.RandomSource random, int low, int high) {
        return low + random.nextInt(Math.max(1, high - low + 1));
    }

    /** The low end of a range, which is the value itself when the pack wrote one number. */
    static double low(JsonObject component, String key, double fallback) {
        return end(component, key, fallback, 0, "min");
    }

    static double high(JsonObject component, String key, double fallback) {
        return end(component, key, fallback, 1, "max");
    }

    private static double end(JsonObject component, String key, double fallback, int index, String named) {
        JsonElement value = component == null ? null : component.get(key);
        if (value == null) return fallback;
        if (value.isJsonPrimitive()) return value.getAsDouble();

        if (value.isJsonArray()) {
            JsonArray pair = value.getAsJsonArray();
            return pair.size() > index ? pair.get(index).getAsDouble() : fallback;
        }
        return value.isJsonObject() ? number(value.getAsJsonObject(), named, fallback) : fallback;
    }

    /** The event a trigger names, whether the pack wrote the object around it or only the name. */
    static String event(JsonObject component, String key) {
        JsonElement value = component == null ? null : component.get(key);
        if (value == null) return null;
        if (value.isJsonPrimitive()) return value.getAsString();

        return value.isJsonObject() ? Json.text(value.getAsJsonObject(), "event", null) : null;
    }

    static Filter filter(JsonObject component, String key) {
        return Filter.parse(component == null ? null : component.get(key));
    }

    /** The blocks a component lists under one key, by the names a pack writes them under. */
    static Set<Block> blocks(JsonObject component, String key) {
        Set<Block> named = new LinkedHashSet<>();
        if (component == null) return named;

        for (JsonElement element : Json.list(component, key)) {
            String id = element.isJsonPrimitive() ? element.getAsString()
                : element.isJsonObject() ? blockName(element.getAsJsonObject()) : null;
            Identifier parsed = id == null ? null : Identifier.tryParse(id);
            if (parsed != null) BuiltInRegistries.BLOCK.getOptional(parsed).ifPresent(named::add);
        }
        return named;
    }

    /**
     * A block descriptor's own name, which a pack writes either bare or with the states beside it.
     *
     * `randomly_placeable_blocks` wraps the descriptor in an entry carrying a weight and a filter, so
     * the name may be one object further in than a plain list of blocks puts it.
     */
    private static String blockName(JsonObject descriptor) {
        if (descriptor.has("name")) return Json.text(descriptor, "name", null);
        JsonElement inner = descriptor.get("block");
        if (inner == null) return null;
        if (inner.isJsonPrimitive()) return inner.getAsString();

        return inner.isJsonObject() ? Json.text(inner.getAsJsonObject(), "name", null) : null;
    }
}
