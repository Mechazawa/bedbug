package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Holds the entity at the surface of whatever it is floating in.
 *
 * `minecraft:behavior.rise_to_liquid_level` is how a Bedrock strider stays on top of the lava rather
 * than wading through it. The pack gives the two speeds and how far under the surface to sit; the
 * surface itself is found by looking up through the liquid the entity is standing in.
 */
public final class RiseToLiquidLevelGoal extends Goal {
    /** How far up the surface is looked for, which is deeper than anything wades. */
    private static final int SEARCH_HEIGHT = 16;

    private final AddonEntity mob;
    private final double offset;
    private final double rise;
    private final double sink;

    public RiseToLiquidLevelGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.offset = Goals.number(component, "liquid_y_offset", 0);
        this.rise = Goals.number(component, "rise_delta", 0.01);
        this.sink = Goals.number(component, "sink_delta", 0.01);
        setFlags(EnumSet.of(Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return mob.isInWater() || mob.isInLava();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        double surface = surfaceAbove();
        if (Double.isNaN(surface)) return;

        double wanted = surface - offset;
        if (mob.getY() < wanted - 0.1) {
            mob.setDeltaMovement(mob.getDeltaMovement().add(0, rise, 0));
        } else if (mob.getY() > wanted + 0.1) {
            mob.setDeltaMovement(mob.getDeltaMovement().subtract(0, sink, 0));
        }
    }

    /** Where the liquid the entity stands in stops, or not a number when it is standing in none. */
    private double surfaceAbove() {
        BlockPos at = mob.blockPosition();
        for (int up = 0; up <= SEARCH_HEIGHT; up++) {
            BlockPos above = at.above(up);
            if (mob.level().getFluidState(above).isEmpty()) return above.getY();
        }
        return Double.NaN;
    }
}
