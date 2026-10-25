package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * The four ways Bedrock describes a slime moving: turning, hopping, rising and closing on a target.
 *
 * A slime has no navigator. It faces a direction and hops, and everything about where it ends up is
 * decided by which way it was facing when it left the ground. Each of these sets the facing or the
 * velocity directly, so an entity carrying one needs nothing but a body.
 */
public final class SlimeGoal extends Goal {
    /** How fast a slime rises through the liquid it is standing in. */
    private static final double FLOAT_RISE = 0.06;

    /** Ticks between two hits, which is the second a slime leaves between them. */
    private static final int HIT_INTERVAL = 20;

    private final AddonEntity mob;
    private final Kind kind;
    private final double speed;
    private final int turnAfter;
    private final int turnSpread;
    private final float turnRange;
    private final double jumpChance;
    private int untilTurn;
    private int untilHit;

    private enum Kind { RANDOM_DIRECTION, KEEP_ON_JUMPING, FLOAT, ATTACK }

    private SlimeGoal(AddonEntity mob, Kind kind, JsonObject component) {
        this.mob = mob;
        this.kind = kind;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.turnAfter = Goals.ticks(component, "min_change_direction_time", 2);
        this.turnSpread = (int) Goals.number(component, "add_random_time_range", 3);
        this.turnRange = (float) Goals.number(component, "turn_range", 360);
        // Bedrock states the chance out of a hundred; a slime in a liquid hops most ticks.
        this.jumpChance = Goals.number(component, "jump_chance_percentage", 80) / 100d;
        // Only the kinds that decide where the entity faces take the look flag, so turning and
        // attacking do not silently stop every other goal from looking at anything.
        setFlags(switch (kind) {
            case RANDOM_DIRECTION -> EnumSet.of(Goal.Flag.LOOK);
            case FLOAT -> EnumSet.of(Goal.Flag.JUMP);
            case KEEP_ON_JUMPING -> EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP);
            default -> EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP, Goal.Flag.LOOK);
        });
    }

    /**
     * `minecraft:behavior.slime_random_direction`, which turns up to `turn_range` degrees after
     * `min_change_direction_time` and a random whole number of seconds up to `add_random_time_range`.
     */
    public static SlimeGoal turning(AddonEntity mob, JsonObject component) {
        return new SlimeGoal(mob, Kind.RANDOM_DIRECTION, component);
    }

    /** `minecraft:behavior.slime_keep_on_jumping`, which hops forward for as long as it can. */
    public static SlimeGoal hopping(AddonEntity mob, JsonObject component) {
        return new SlimeGoal(mob, Kind.KEEP_ON_JUMPING, component);
    }

    /** `minecraft:behavior.slime_float`, which rises while standing in water or lava. */
    public static SlimeGoal floating(AddonEntity mob, JsonObject component) {
        return new SlimeGoal(mob, Kind.FLOAT, component);
    }

    /** `minecraft:behavior.slime_attack`, which hops at whatever the entity is after. */
    public static SlimeGoal attacking(AddonEntity mob, JsonObject component) {
        return new SlimeGoal(mob, Kind.ATTACK, component);
    }

    @Override
    public boolean canUse() {
        return switch (kind) {
            case FLOAT -> mob.isInWater() || mob.isInLava();
            case ATTACK -> alive(mob.getTarget());
            default -> !mob.isPassenger();
        };
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        untilTurn = 0;
        mob.getNavigation().stop();
    }

    /** The selector ticks a running goal every second tick unless it asks for each one. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        switch (kind) {
            case RANDOM_DIRECTION -> turn();
            case KEEP_ON_JUMPING -> Goals.hop(mob, speed);
            case FLOAT -> rise();
            case ATTACK -> close();
            default -> { }
        }
    }

    /** A new facing every few seconds, turned by at most what the pack allows. */
    private void turn() {
        if (--untilTurn > 0) return;

        untilTurn = turnAfter + mob.getRandom().nextInt(Math.max(0, turnSpread) + 1) * Goals.TICKS_PER_SECOND;
        mob.setYRot(mob.getYRot() + (mob.getRandom().nextFloat() - 0.5f) * turnRange);
        mob.yBodyRot = mob.getYRot();
        mob.yHeadRot = mob.getYRot();
    }

    /** A push upward while there is liquid to push against, with a hop out of it now and then. */
    private void rise() {
        Vec3 moving = mob.getDeltaMovement();
        mob.setDeltaMovement(moving.x, moving.y + FLOAT_RISE, moving.z);
        if (mob.getRandom().nextDouble() < jumpChance) mob.getJumpControl().jump();
    }

    /** A hop towards the target, and a hit once the two are touching. */
    private void close() {
        LivingEntity target = mob.getTarget();
        if (!alive(target)) return;

        double towards = Math.atan2(target.getZ() - mob.getZ(), target.getX() - mob.getX());
        mob.setYRot((float) Math.toDegrees(towards) - 90f);
        mob.yBodyRot = mob.getYRot();
        mob.yHeadRot = mob.getYRot();

        Goals.hop(mob, speed);
        if (--untilHit <= 0 && mob.distanceTo(target) <= mob.getBbWidth() + target.getBbWidth()) {
            hit(target);
        }
    }

    private void hit(LivingEntity target) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        untilHit = HIT_INTERVAL;
        Platform.hurt(target, level, mob.damageSources().mobAttack(mob),
            (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE));
    }

    private static boolean alive(LivingEntity target) {
        return target != null && target.isAlive();
    }
}
