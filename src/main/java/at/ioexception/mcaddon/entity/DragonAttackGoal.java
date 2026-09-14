package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * The three ways a Bedrock dragon attacks: it dives at you, it circles and shoots, or it breathes.
 *
 * Java keeps all three on `EnderDragon` as phases, so no other entity can be given them. Each one is
 * stated fully in the pack, which is what makes it carry across: how close the target has to be, how
 * fast the entity moves while the attack runs and while it does not, how long it holds a shot before
 * taking it, and how long the roar before the flame lasts. What it shoots is the entity's own
 * `minecraft:shooter`, and the flame damages and lights whatever is in front of it.
 */
public final class DragonAttackGoal extends Goal {
    /** How wide the flame spreads, in degrees either side of where the entity is facing. */
    private static final double FLAME_SPREAD = 45;

    /** How far the flame reaches. */
    private static final double FLAME_REACH = 8;

    /** How long the flame leaves whatever it touches burning, in seconds. */
    private static final int FLAME_BURN = 8;

    /** Ticks between two of the flame's hits, so a breath is not damage on every tick. */
    private static final int FLAME_INTERVAL = 10;

    private final AddonEntity mob;
    private final Kind kind;
    private final double activeSpeed;
    private final double flightSpeed;
    private final double turnSpeed;
    private final double zoneLow;
    private final double zoneHigh;
    private final int chargeThreshold;
    private final double fireballRange;
    private final double viewAngle;
    private final double switchChance;
    private final int aimTicks;
    private final int roarTicks;
    private final int flameTicks;
    private final int cooldownTicks;
    private int held;
    private int aimed;
    private int untilNext;
    private double angle;
    private boolean clockwise;

    private enum Kind { CHARGE, STRAFE, FLAME }

    private DragonAttackGoal(AddonEntity mob, JsonObject component, Kind kind) {
        this.mob = mob;
        this.kind = kind;
        this.activeSpeed = Goals.number(component, "active_speed", 3);
        this.flightSpeed = Goals.number(component, "flight_speed", 1);
        this.turnSpeed = Goals.number(component, "turn_speed", 0.7);
        this.zoneLow = Goals.low(component, "target_zone", 10);
        this.zoneHigh = Goals.high(component, "target_zone", 150);
        this.chargeThreshold = Goals.ticks(component, "continue_charge_threshold_time", 0.5);
        this.fireballRange = Goals.number(component, "fireball_range", 64);
        this.viewAngle = Goals.number(component, "view_angle", 10);
        this.switchChance = Goals.number(component, "switch_direction_probability", 0.5);
        this.aimTicks = Goals.ticks(component, "target_in_range_and_in_view_time", 0.25);
        this.roarTicks = Goals.ticks(component, "roar_time", 2);
        this.flameTicks = Goals.ticks(component, "flame_time", 5);
        this.cooldownTicks = Goals.ticks(component, "cooldown_time", 10);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.dragonchargeplayer`, which crosses the gap at whatever it is after. */
    public static DragonAttackGoal charging(AddonEntity mob, JsonObject component) {
        return new DragonAttackGoal(mob, component, Kind.CHARGE);
    }

    /** `minecraft:behavior.dragonstrafeplayer`, which holds a ring around it and shoots inward. */
    public static DragonAttackGoal strafing(AddonEntity mob, JsonObject component) {
        return new DragonAttackGoal(mob, component, Kind.STRAFE);
    }

    /** `minecraft:behavior.dragonflaming`, which roars and then breathes over what is in front. */
    public static DragonAttackGoal flaming(AddonEntity mob, JsonObject component) {
        return new DragonAttackGoal(mob, component, Kind.FLAME);
    }

    @Override
    public boolean canUse() {
        if (untilNext-- > 0) return false;

        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;

        double away = mob.distanceTo(target);
        return kind == Kind.FLAME ? away <= FLAME_REACH : away >= zoneLow && away <= zoneHigh;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;

        return switch (kind) {
            case CHARGE -> held < chargeThreshold + zoneHigh / Math.max(0.01, activeSpeed);
            case STRAFE -> mob.distanceTo(target) <= zoneHigh;
            default -> held < roarTicks + flameTicks;
        };
    }

    @Override
    public void start() {
        held = 0;
        aimed = 0;
        angle = mob.getRandom().nextDouble() * Math.PI * 2;
        clockwise = mob.getRandom().nextBoolean();
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        untilNext = kind == Kind.FLAME ? cooldownTicks : 0;
        mob.setPose(Pose.STANDING);
        mob.setValue("is_charging", false);
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;

        held++;
        mob.getLookControl().setLookAt(target, (float) (turnSpeed * 100), 30f);
        switch (kind) {
            case CHARGE -> dive(target);
            case STRAFE -> circle(target);
            default -> breathe(target);
        }
    }

    /** Straight at the target at the speed the attack runs on, rather than the cruising one. */
    private void dive(LivingEntity target) {
        mob.setValue("is_charging", true);
        double pace = (mob.distanceTo(target) > zoneLow ? activeSpeed : flightSpeed) * 0.05;
        Vec3 towards = target.position().subtract(mob.position()).normalize().scale(pace);
        mob.setDeltaMovement(towards);
    }

    /**
     * A ring held around the target, with a shot taken once it has stayed in view long enough.
     *
     * The direction is rolled again each pass, which is what the switch chance states, and the view
     * angle is how far off the entity's own facing the target may be before a shot is allowed.
     */
    private void circle(LivingEntity target) {
        angle += Math.toRadians(clockwise ? 4 : -4);
        if (mob.getRandom().nextDouble() < switchChance / Goals.TICKS_PER_SECOND) {
            clockwise = !clockwise;
        }

        Vec3 next = target.position().add(Math.cos(angle) * zoneLow, zoneLow / 2,
            Math.sin(angle) * zoneLow);
        mob.getMoveControl().setWantedPosition(next.x, next.y, next.z, activeSpeed);
        if (mob.distanceTo(target) > fireballRange || !inView(target)) {
            aimed = 0;
            return;
        }

        if (++aimed < aimTicks) return;

        aimed = 0;
        mob.performRangedAttack(target, 1f);
    }

    /** A roar held first, then a cone of fire in front of the entity for as long as the pack asked. */
    private void breathe(LivingEntity target) {
        if (held <= roarTicks) {
            mob.setPose(Pose.ROARING);
            mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
            return;
        }

        mob.setPose(Pose.STANDING);
        if (!(mob.level() instanceof ServerLevel level) || held % FLAME_INTERVAL != 0) return;

        Vec3 facing = target.position().subtract(mob.position()).normalize();
        List<LivingEntity> caught = level.getEntitiesOfClass(LivingEntity.class,
            mob.getBoundingBox().inflate(FLAME_REACH), other -> other != mob && inCone(other, facing));
        for (LivingEntity burning : caught) {
            burning.setRemainingFireTicks(FLAME_BURN * Goals.TICKS_PER_SECOND);
            Platform.hurt(burning, level, mob.damageSources().mobAttack(mob),
                (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE));
        }
    }

    /** Whether the target sits close enough to straight ahead for a shot to be taken. */
    private boolean inView(LivingEntity target) {
        Vec3 facing = Vec3.directionFromRotation(0, mob.getYRot());
        Vec3 towards = target.position().subtract(mob.position()).multiply(1, 0, 1).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, facing.dot(towards))))) <= viewAngle;
    }

    /** Whether something stands inside the wedge the flame covers. */
    private boolean inCone(LivingEntity other, Vec3 facing) {
        Vec3 towards = other.position().subtract(mob.position()).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, facing.dot(towards))))) <= FLAME_SPREAD;
    }
}
