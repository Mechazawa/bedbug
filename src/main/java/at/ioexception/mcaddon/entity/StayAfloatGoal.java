package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * `minecraft:behavior.float`: Java's float goal with the chance of a stroke up each tick, a vehicle
 * that sinks while it carries anyone, and passengers put off once the vehicle's head has been under
 * water for `time_under_water_to_dismount_passengers`.
 */
final class StayAfloatGoal extends Goal {
    private final AddonEntity mob;
    private final float chance;
    private final boolean sinksLaden;
    private final int dismountAfter;
    private int headUnder;

    StayAfloatGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.chance = (float) Goals.number(goal, "chance_per_tick_to_float", 0.8);
        this.sinksLaden = Goals.flag(goal, "sink_with_passengers", false);
        this.dismountAfter = Goals.ticks(goal, "time_under_water_to_dismount_passengers", 0);
        setFlags(Goals.controls(goal));
        mob.getNavigation().setCanFloat(true);
    }

    @Override
    public boolean canUse() {
        return mob.isInWater() && mob.getFluidHeight(FluidTags.WATER) > mob.getFluidJumpThreshold() || mob.isInLava();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        headUnder = 0;
    }

    @Override
    public void tick() {
        headUnder = mob.isVehicle() && mob.isEyeInFluid(FluidTags.WATER) ? headUnder + 1 : 0;
        if (headUnder > dismountAfter) mob.ejectPassengers();

        if (sinksLaden && mob.isVehicle()) return;
        if (mob.getRandom().nextFloat() < chance) mob.getJumpControl().jump();
    }
}
