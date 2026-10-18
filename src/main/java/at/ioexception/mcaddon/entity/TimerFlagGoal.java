package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Raises a flag for a while and tells the pack when it goes up and when it comes down.
 *
 * Bedrock's three timer flags are the same goal three times over, so a pack can run three of them
 * at different priorities. Each waits out `cooldown_range` before its first run as well as between
 * runs, which is how a pack times an animation without a script. The goal holds only the controls
 * `control_flags` names, so one naming none runs beside every other goal.
 */
public final class TimerFlagGoal extends Goal {
    private final AddonEntity mob;
    private final int durationLow;
    private final int durationHigh;
    private final int cooldownLow;
    private final int cooldownHigh;
    private final String startEvent;
    private final String endEvent;

    /** Which of Bedrock's three flags this one raises, which a pack's controller reads back. */
    private final String flag;
    private long readyAt = -1;
    private long endsAt;

    public TimerFlagGoal(AddonEntity mob, JsonObject component, String flag) {
        this.mob = mob;
        this.flag = flag;
        this.durationLow = Goals.ticksLow(component, "duration_range", 0);
        this.durationHigh = Goals.ticksHigh(component, "duration_range", 0);
        this.cooldownLow = Goals.ticksLow(component, "cooldown_range", 0);
        this.cooldownHigh = Goals.ticksHigh(component, "cooldown_range", 0);
        this.startEvent = Goals.event(component, "on_start");
        this.endEvent = Goals.event(component, "on_end");
        setFlags(Goals.controls(component));
    }

    @Override
    public boolean canUse() {
        if (readyAt < 0) readyAt = coolDown();
        return mob.level().getGameTime() >= readyAt;
    }

    @Override
    public boolean canContinueToUse() {
        return mob.level().getGameTime() < endsAt;
    }

    @Override
    public void start() {
        endsAt = mob.level().getGameTime() + Goals.between(mob.getRandom(), durationLow, durationHigh);
        mob.setValue(flag, true);
        raise(startEvent);
    }

    @Override
    public void tick() {
        if (getFlags().contains(Flag.MOVE)) mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        mob.setValue(flag, false);
        readyAt = coolDown();
        raise(endEvent);
    }

    private long coolDown() {
        return mob.level().getGameTime() + Goals.between(mob.getRandom(), cooldownLow, cooldownHigh);
    }

    private void raise(String event) {
        if (event != null) EntityEvents.trigger(mob, event, null, ServerHooks.state());
    }
}
