package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Circles the place it started from, widening and rising as it goes.
 *
 * `minecraft:behavior.circle_around_anchor` is a phantom's flight. Java writes that as three goals
 * inside its own phantom; here it is one, because the pack states the whole of it: how far out, how
 * high, how often each of those is allowed to change, and by how much. While the entity has a target
 * the anchor is over it, `height_above_target_range` higher, picked each time the target changes, the
 * way Java's phantom sets its anchor over the player it is after.
 */
public final class CircleAroundAnchorGoal extends Goal {
    private final AddonEntity mob;
    private final double speed;
    private final double angleChange;
    private final double goalRadius;
    private final double radiusLow;
    private final double radiusHigh;
    private final double radiusChange;
    private final double radiusChangeChance;
    private final double heightLow;
    private final double heightHigh;
    private final double heightChangeChance;
    private final double aboveTargetLow;
    private final double aboveTargetHigh;
    private Vec3 anchor;
    private LivingEntity anchoredOver;
    private double angle;
    private double radius;
    private double height;

    public CircleAroundAnchorGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.angleChange = Goals.number(component, "angle_change", 15);
        this.goalRadius = Goals.number(component, "goal_radius", 0.5);
        this.radiusLow = Goals.low(component, "radius_range", 5);
        this.radiusHigh = Goals.high(component, "radius_range", 15);
        this.radiusChange = Goals.number(component, "radius_change", 1);
        this.radiusChangeChance = Goals.number(component, "radius_adjustment_chance", 0.004);
        this.heightLow = Goals.low(component, "height_offset_range", 0);
        this.heightHigh = Goals.high(component, "height_offset_range", 0);
        this.heightChangeChance = Goals.number(component, "height_adjustment_chance", 0.00286);
        this.aboveTargetLow = Goals.low(component, "height_above_target_range", 0);
        this.aboveTargetHigh = Goals.high(component, "height_above_target_range", 0);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public void start() {
        anchor = mob.position();
        anchoredOver = null;
        angle = mob.getRandom().nextDouble() * Math.PI * 2;
        radius = radiusLow;
        height = heightLow;
    }

    @Override
    public void tick() {
        if (anchor == null) return;

        LivingEntity target = mob.getTarget();
        if (target != null && target != anchoredOver) {
            anchoredOver = target;
            anchor = target.position().add(0,
                aboveTargetLow + mob.getRandom().nextDouble() * Math.max(0, aboveTargetHigh - aboveTargetLow), 0);
        }

        if (mob.getRandom().nextDouble() < radiusChangeChance) {
            radius = Math.min(radiusHigh, radius + radiusChange);
        }
        if (mob.getRandom().nextDouble() < heightChangeChance) {
            height = heightLow + mob.getRandom().nextDouble() * Math.max(0, heightHigh - heightLow);
        }

        Vec3 next = anchor.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
        if (mob.position().distanceTo(next) <= goalRadius || mob.getNavigation().isDone()) {
            angle += Math.toRadians(angleChange);
            next = anchor.add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
        }
        mob.getNavigation().moveTo(next.x, next.y, next.z, speed);
    }
}
