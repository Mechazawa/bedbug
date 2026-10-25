package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Lying down at night, which is what `minecraft:behavior.sleep` describes.
 *
 * Java writes sleeping into the villager alone, tied to a bed it has claimed. A pack's entity has
 * no bed, so what carries across is the part a player sees: it stops where it is until the sun is
 * up again, unless a goal of a lower priority number wants the entity. A rider sleeps only when
 * `can_sleep_while_riding` says so, and `cooldown_time` holds the goal back after each night.
 */
public final class SleepGoal extends Goal {
    /** The span of the day Bedrock lets a bed be slept in, in ticks. */
    private static final long BED_OPENS = 12542L;
    private static final long BED_CLOSES = 23460L;

    private final AddonEntity mob;
    private final boolean sleepsRiding;
    private final int cooldown;
    private long readyAt;

    public SleepGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.sleepsRiding = Goals.flag(component, "can_sleep_while_riding", false);
        this.cooldown = Goals.ticks(component, "cooldown_time", 0);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.level().getGameTime() >= readyAt && mayRest();
    }

    @Override
    public boolean canContinueToUse() {
        return mayRest();
    }

    @Override
    public void start() {
        mob.getNavigation().stop();
        mob.setPose(Pose.SLEEPING);
    }

    @Override
    public void tick() {
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
        mob.restedOn(Platform.dayTime(mob.level()) / 24000L);
    }

    @Override
    public void stop() {
        if (mob.getPose() == Pose.SLEEPING) mob.setPose(Pose.STANDING);
        readyAt = mob.level().getGameTime() + cooldown;
    }

    private boolean mayRest() {
        long time = Platform.dayTime(mob.level()) % 24000L;
        return time >= BED_OPENS && time < BED_CLOSES && (sleepsRiding || !mob.isPassenger());
    }
}
