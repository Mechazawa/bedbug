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
 * entity reaches them through the pose alone and its own model animates the rest. Times run on the
 * game clock, because Java ticks a running goal only every second tick.
 */
public final class PoseHoldGoal extends Goal {
    private final AddonEntity mob;
    private final Pose pose;
    private final Goals.Span duration;
    private final Goals.Span wait;
    private final boolean waitsFirst;
    private final Filter conditions;
    private final double noticeRadius;
    private final boolean needsTarget;
    private final String onStart;
    private final String onDone;
    private long endsAt;
    private long readyAt = -1;
    private LivingEntity answered;

    private PoseHoldGoal(AddonEntity mob, JsonObject component, Pose pose, Goals.Span duration,
        Goals.Span wait, boolean waitsFirst, double noticeRadius, boolean needsTarget) {
        this.mob = mob;
        this.pose = pose;
        this.duration = duration;
        this.wait = wait;
        this.waitsFirst = waitsFirst;
        this.conditions = Goals.filter(component, "filters");
        this.noticeRadius = noticeRadius;
        this.needsTarget = needsTarget;
        this.onStart = Goals.event(component, "on_start");
        this.onDone = Goals.event(component, "on_done");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.roar`, which a Bedrock mob does once at whatever it has just noticed. */
    public static PoseHoldGoal roaring(AddonEntity mob, JsonObject component) {
        return new PoseHoldGoal(mob, component, Pose.ROARING, Goals.span(component, "duration", 1, 1),
            new Goals.Span(0, 0), false, 0, true);
    }

    /** `minecraft:behavior.croak`, which runs on its own clock while the pack's filters hold. */
    public static PoseHoldGoal croaking(AddonEntity mob, JsonObject component) {
        return new PoseHoldGoal(mob, component, Pose.CROAKING, Goals.span(component, "duration", 4.5, 4.5),
            Goals.span(component, "interval", 10, 20), false, 0, false);
    }

    /** `minecraft:behavior.sniff`, which ends looking at whatever it found within its radius. */
    public static PoseHoldGoal sniffing(AddonEntity mob, JsonObject component) {
        return new PoseHoldGoal(mob, component, Pose.SNIFFING, Goals.span(component, "duration", 1, 1),
            Goals.span(component, "cooldown_range", 3, 10), false,
            Goals.number(component, "sniffing_radius", 5), false);
    }

    /** `minecraft:behavior.emerge`, which a pack gives to something that arrives out of the ground. */
    public static PoseHoldGoal emerging(AddonEntity mob, JsonObject component) {
        return new PoseHoldGoal(mob, component, Pose.EMERGING, Goals.span(component, "duration", 5, 5),
            Goals.span(component, "cooldown_time", 0.5, 0.5), false, 0, false);
    }

    /**
     * `minecraft:behavior.dig`, which is the same thing the other way round.
     *
     * `idle_time` is how long the entity has to have had nothing to do before it goes under, so it
     * waits that long before its first dig as well as between digs.
     */
    public static PoseHoldGoal digging(AddonEntity mob, JsonObject component) {
        return new PoseHoldGoal(mob, component, Pose.DIGGING, Goals.span(component, "duration", 0, 0),
            Goals.span(component, "idle_time", 0, 0), true, 0, false);
    }

    @Override
    public boolean canUse() {
        long now = mob.level().getGameTime();
        if (readyAt < 0) readyAt = waitsFirst ? now + wait.roll(mob.getRandom()) : 0;
        if (now < readyAt) return false;
        if (needsTarget && (mob.getTarget() == null || mob.getTarget() == answered)) return false;
        if (mob.getPose() != Pose.STANDING) return false;

        return conditions.matches(new Filter.Context(mob, mob, mob.getTarget(), ServerHooks.state()));
    }

    @Override
    public boolean canContinueToUse() {
        return mob.level().getGameTime() < endsAt;
    }

    @Override
    public void start() {
        endsAt = mob.level().getGameTime() + duration.roll(mob.getRandom());
        answered = mob.getTarget();
        mob.setPose(pose);
        fire(onStart);
    }

    @Override
    public void tick() {
        mob.getNavigation().stop();
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
    }

    @Override
    public void stop() {
        mob.setPose(Pose.STANDING);
        readyAt = mob.level().getGameTime() + wait.roll(mob.getRandom());
        fire(onDone);
        if (noticeRadius <= 0) return;

        mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(noticeRadius))
            .stream()
            .filter(nearby -> nearby != mob)
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .ifPresent(nearby -> mob.getLookControl().setLookAt(nearby, 30f, 30f));
    }

    private void fire(String event) {
        if (event != null) {
            at.ioexception.mcaddon.server.EntityEvents.trigger(mob, event, null, ServerHooks.state());
        }
    }
}
