package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * Stands still in one of the poses the game already draws, for as long as the pack asked.
 *
 * Roaring, croaking and sniffing are the same shape in Bedrock: stop, hold a pose, wait, do it
 * again. Java owns each pose but hands it out only to the one mob it wrote the pose for, so a pack's
 * entity reaches them through the pose alone and its own model animates the rest.
 */
public final class PoseHoldGoal extends Goal {
    private final AddonEntity mob;
    private final Pose pose;
    private final int durationLow;
    private final int durationHigh;
    private final int intervalLow;
    private final int intervalHigh;
    private final Filter conditions;
    private final double noticeRadius;
    private final boolean needsTarget;
    private int held;
    private int holdFor;
    private int untilNext;
    private LivingEntity answered;

    private PoseHoldGoal(AddonEntity mob, JsonObject component, Pose pose, String durationKey,
        double durationFallback, String intervalKey, double noticeRadius, boolean needsTarget) {
        this.mob = mob;
        this.pose = pose;
        this.durationLow = Goals.ticksLow(component, durationKey, durationFallback);
        this.durationHigh = Goals.ticksHigh(component, durationKey, durationFallback);
        this.intervalLow = intervalKey == null ? 0 : Goals.ticksLow(component, intervalKey, 10);
        this.intervalHigh = intervalKey == null ? 0 : Goals.ticksHigh(component, intervalKey, 10);
        this.conditions = Goals.filter(component, "filters");
        this.noticeRadius = noticeRadius;
        this.needsTarget = needsTarget;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.roar`, which a Bedrock mob does once at whatever it has just noticed. */
    public static PoseHoldGoal roaring(AddonEntity mob, JsonObject component) {
        return new PoseHoldGoal(mob, component, Pose.ROARING, "duration", 1, null, 0, true);
    }

    /** `minecraft:behavior.croak`, which runs on its own clock while the pack's filters hold. */
    public static PoseHoldGoal croaking(AddonEntity mob, JsonObject component) {
        return new PoseHoldGoal(mob, component, Pose.CROAKING, "duration", 4.5, "interval", 0, false);
    }

    /** `minecraft:behavior.sniff`, which ends looking at whatever it found within its radius. */
    public static PoseHoldGoal sniffing(AddonEntity mob, JsonObject component) {
        return new PoseHoldGoal(mob, component, Pose.SNIFFING, "duration", 1, "cooldown_range",
            Goals.number(component, "sniffing_radius", 5), false);
    }

    @Override
    public boolean canUse() {
        if (untilNext-- > 0) return false;
        if (needsTarget && (mob.getTarget() == null || mob.getTarget() == answered)) return false;
        if (mob.getPose() != Pose.STANDING) return false;

        return conditions.matches(new Filter.Context(mob, mob, mob.getTarget(), ServerHooks.state()));
    }

    @Override
    public boolean canContinueToUse() {
        return held < holdFor;
    }

    @Override
    public void start() {
        held = 0;
        holdFor = Goals.between(mob.getRandom(), durationLow, durationHigh);
        answered = mob.getTarget();
        mob.setPose(pose);
    }

    @Override
    public void tick() {
        held++;
        mob.getNavigation().stop();
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
    }

    @Override
    public void stop() {
        mob.setPose(Pose.STANDING);
        untilNext = Goals.between(mob.getRandom(), intervalLow, intervalHigh);
        if (noticeRadius <= 0) return;

        mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(noticeRadius))
            .stream()
            .filter(nearby -> nearby != mob)
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .ifPresent(nearby -> mob.getLookControl().setLookAt(nearby, 30f, 30f));
    }
}
