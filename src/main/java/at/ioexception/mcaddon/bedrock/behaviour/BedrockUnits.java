package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.world.phys.Vec3;

/**
 * The quantities a behaviour pack states in a unit Java does not count in.
 *
 * A conversion that reads cleanly and lands on the wrong number is the failure this exists to stop:
 * both sides are plain decimals, so nothing about the value says which unit it arrived in. Each
 * method here names the unit it takes and the unit it answers with.
 */
public final class BedrockUnits {

    /** How many of Java's ticks fill the second Bedrock times a behaviour and a particle in. */
    public static final int TICKS_PER_SECOND = 20;

    private BedrockUnits() {
    }

    /**
     * A speed stated in blocks a second, as the blocks a tick Java moves something by.
     *
     * Every speed a particle effect states is per second, down to Mojang's own gravity of 9.8, and
     * Java advances a particle once a tick by whatever its velocity holds.
     */
    public static double blocksPerTick(double blocksPerSecond) {
        return blocksPerSecond / TICKS_PER_SECOND;
    }

    /**
     * An acceleration stated in blocks a second each second, as blocks a tick each tick.
     *
     * The conversion is applied twice because both the distance and the time are counted per
     * second, which is why an acceleration off by a factor of twenty reads as a plausible one.
     */
    public static double blocksPerTickSquared(double blocksPerSecondSquared) {
        return blocksPerSecondSquared / (TICKS_PER_SECOND * (double) TICKS_PER_SECOND);
    }

    /**
     * How often a chance Bedrock states per tick succeeds when it is asked once every `ticks` ticks.
     *
     * Bedrock asks such a chance on every tick of the entity, and the sensors here run on an
     * interval, so asking the pack's own number once per interval fires it a fraction as often. What
     * carries over is the chance of at least one success across the whole interval.
     */
    public static double chanceOverTicks(double perTick, int ticks) {
        if (perTick <= 0 || ticks <= 0) return 0;
        if (perTick >= 1) return 1;

        return 1 - Math.pow(1 - perTick, ticks);
    }

    /**
     * A three member vector a component states in blocks.
     *
     * Bedrock measures a model in sixteenths of a block and a component in whole ones, and a seat
     * sitting a fifth of a block down reads as a plausible model coordinate either way, so the two
     * are told apart by which file the number came out of rather than by the number.
     */
    public static Vec3 blocks(JsonElement vector, Vec3 fallback) {
        if (vector == null || !vector.isJsonArray()) return fallback;

        JsonArray members = vector.getAsJsonArray();
        if (members.size() < 3) return fallback;

        return new Vec3(members.get(0).getAsDouble(), members.get(1).getAsDouble(),
            members.get(2).getAsDouble());
    }
}
