package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/**
 * The wait `minecraft:transformation` states before the entity becomes another, and what shortens it.
 *
 * Bedrock writes the wait in seconds as a value with a random range on top, and lets the blocks the
 * pack names nearby speed it up. The numbers Mojang gives its zombie villager are the ones Java's own
 * zombie villager cures with: a one in a hundred chance each tick to look, up to fourteen beds or
 * iron bars within four blocks, each taking a tick off three times in ten.
 */
public final class Transformation {
    private final JsonObject body;
    private final double assistChance;
    private final double blockChance;
    private final int blockMax;
    private final int blockRadius;
    private final Set<Block> helpers;
    private int remaining;

    private Transformation(JsonObject body, JsonObject delay, AddonEntity entity) {
        this.body = body;
        this.assistChance = Goals.number(delay, "block_assist_chance", 0);
        this.blockChance = Goals.number(delay, "block_chance", 0);
        this.blockRadius = (int) Goals.number(delay, "block_radius", 0);
        int stated = (int) Goals.number(delay, "block_max", 0);
        this.blockMax = stated > 0 ? stated : blockRadius;
        this.helpers = Goals.blocks(delay, "block_types");

        double low = Goals.number(delay, "range_min", 0);
        double high = Goals.number(delay, "range_max", 0);
        double extra = high > 0 ? low + entity.getRandom().nextDouble() * Math.max(0, high - low) : 0;
        this.remaining = (int) ((Goals.number(delay, "value", 0) + extra) * Goals.TICKS_PER_SECOND);
    }

    /** Null for a transformation with no wait, which happens the moment it is asked for. */
    public static Transformation tryFrom(JsonObject body, AddonEntity entity) {
        JsonObject delay = body != null && body.has("delay") && body.get("delay").isJsonObject()
            ? body.getAsJsonObject("delay") : null;
        if (delay == null) return null;

        Transformation waiting = new Transformation(body, delay, entity);
        return waiting.remaining > 0 ? waiting : null;
    }

    public JsonObject body() {
        return body;
    }

    /** Counts one tick of the wait down, and the ticks the blocks nearby take off it; true once it is over. */
    boolean tick(AddonEntity entity) {
        remaining -= 1 + assistance(entity);
        return remaining <= 0;
    }

    private int assistance(AddonEntity entity) {
        if (helpers.isEmpty() || blockRadius <= 0 || entity.getRandom().nextDouble() >= assistChance) return 0;

        int found = 0;
        int taken = 0;
        BlockPos centre = entity.blockPosition();
        for (BlockPos position : BlockPos.betweenClosed(centre.offset(-blockRadius, -blockRadius, -blockRadius),
            centre.offset(blockRadius, blockRadius, blockRadius))) {
            if (found >= blockMax) break;
            if (!helpers.contains(entity.level().getBlockState(position).getBlock())) continue;

            found++;
            if (entity.getRandom().nextDouble() < blockChance) taken++;
        }
        return taken;
    }
}
