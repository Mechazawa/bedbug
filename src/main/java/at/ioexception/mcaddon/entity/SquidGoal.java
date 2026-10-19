package at.ioexception.mcaddon.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * The five ways Bedrock describes a squid moving through, against and out of water.
 *
 * A squid has no navigator: it pushes itself along and coasts. Each of these sets the entity's
 * velocity for as long as it runs, so an entity carrying one needs nothing but a body. None of the
 * five carries a value in its component body, which is why they read nothing out of one.
 */
public final class SquidGoal extends Goal {
    /** How fast an unhurried squid drifts, in blocks per tick. */
    private static final double DRIFT_SPEED = 0.05;

    /** How fast a frightened one leaves, in blocks per tick. */
    private static final double DART_SPEED = 0.3;

    /** How far a player has to be before a squid stops minding them. */
    private static final double THREAT_RANGE = 5;

    private static final int IDLE_DURATION = 80;
    private static final int DIVE_DURATION = 60;
    private static final int FLEE_DURATION = 40;
    private static final int PUSH_OFF_DURATION = 20;

    /** How often an idling squid picks somewhere else to drift, as a low and a high bound. */
    private static final int DRIFT_LOW = 20;
    private static final int DRIFT_HIGH = 40;

    /** How often a stranded squid flops, as a low and a high bound. */
    private static final int FLOP_LOW = 10;
    private static final int FLOP_HIGH = 30;

    private static final double START_CHANCE = 0.08;

    private final AddonEntity mob;
    private final Kind kind;
    private Vec3 heading = Vec3.ZERO;
    private LivingEntity threat;
    private int ticksLeft;
    private int untilNewHeading;

    private enum Kind { IDLE, DIVE, FLEE, PUSH_OFF_GROUND, FLOP }

    private SquidGoal(AddonEntity mob, Kind kind) {
        this.mob = mob;
        this.kind = kind;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    /** `minecraft:behavior.squid_idle`, which drifts one way for a while and then another. */
    public static SquidGoal idling(AddonEntity mob) {
        return new SquidGoal(mob, Kind.IDLE);
    }

    /** `minecraft:behavior.squid_dive`, which sinks while there is water underneath to sink into. */
    public static SquidGoal diving(AddonEntity mob) {
        return new SquidGoal(mob, Kind.DIVE);
    }

    /** `minecraft:behavior.squid_flee`, which darts away from whatever hurt it or came too close. */
    public static SquidGoal fleeing(AddonEntity mob) {
        return new SquidGoal(mob, Kind.FLEE);
    }

    /** `minecraft:behavior.squid_move_away_from_ground`, which pushes off the solid it is against. */
    public static SquidGoal leavingGround(AddonEntity mob) {
        return new SquidGoal(mob, Kind.PUSH_OFF_GROUND);
    }

    /** `minecraft:behavior.squid_out_of_water`, which flops on the spot while stranded. */
    public static SquidGoal flopping(AddonEntity mob) {
        return new SquidGoal(mob, Kind.FLOP);
    }

    @Override
    public boolean canUse() {
        switch (kind) {
            case IDLE:
                return mob.isInWater() && mob.getRandom().nextDouble() < START_CHANCE;
            case DIVE:
                return mob.isInWater() && waterBelow()
                    && mob.getRandom().nextDouble() < START_CHANCE;
            case FLEE:
                threat = threat();
                return mob.isInWater() && threat != null;
            case PUSH_OFF_GROUND:
                heading = awayFromGround();
                return mob.isInWater() && heading != null;
            case FLOP:
            default:
                return !mob.isInWater() && mob.onGround();
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (kind == Kind.FLOP) return !mob.isInWater();

        return ticksLeft > 0 && mob.isInWater();
    }

    @Override
    public void start() {
        untilNewHeading = 0;
        switch (kind) {
            case IDLE:
                ticksLeft = IDLE_DURATION;
                break;
            case DIVE:
                ticksLeft = DIVE_DURATION;
                break;
            case FLEE:
                ticksLeft = FLEE_DURATION;
                break;
            case PUSH_OFF_GROUND:
                ticksLeft = PUSH_OFF_DURATION;
                break;
            case FLOP:
            default:
                ticksLeft = 0;
                break;
        }
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        threat = null;
        heading = Vec3.ZERO;
    }

    /** The selector ticks a running goal every second tick unless it asks for each one. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        ticksLeft--;
        switch (kind) {
            case IDLE:
                drift();
                break;
            case DIVE:
                sink();
                break;
            case FLEE:
                dart();
                break;
            case PUSH_OFF_GROUND:
                pushOff();
                break;
            case FLOP:
            default:
                flop();
                break;
        }
    }

    /** A gentle course held for a second or two at a time, then chosen again. */
    private void drift() {
        if (--untilNewHeading <= 0) {
            untilNewHeading = Goals.between(mob.getRandom(), DRIFT_LOW, DRIFT_HIGH);
            heading = randomDirection().scale(DRIFT_SPEED);
            mob.setYRot((float) (Math.atan2(heading.z, heading.x) * 180 / Math.PI) - 90f);
        }
        mob.setDeltaMovement(heading);
    }

    private void sink() {
        Vec3 moving = mob.getDeltaMovement();
        mob.setDeltaMovement(moving.x * 0.8, -DRIFT_SPEED * 2, moving.z * 0.8);
    }

    private void dart() {
        if (threat == null) return;

        Vec3 away = mob.position().subtract(threat.position());
        if (away.lengthSqr() < 1.0E-4) away = randomDirection();
        mob.setDeltaMovement(away.normalize().scale(DART_SPEED));
    }

    private void pushOff() {
        if (heading == null) return;

        mob.setDeltaMovement(heading.scale(DRIFT_SPEED * 2).add(0, DRIFT_SPEED, 0));
    }

    /** A hop on the spot with a turn in it, which is all a stranded squid manages. */
    private void flop() {
        if (--untilNewHeading > 0 || !mob.onGround()) return;

        untilNewHeading = Goals.between(mob.getRandom(), FLOP_LOW, FLOP_HIGH);
        mob.setYRot(mob.getYRot() + (mob.getRandom().nextFloat() - 0.5f) * 90f);
        mob.setDeltaMovement((mob.getRandom().nextDouble() - 0.5) * 0.1, 0.4,
            (mob.getRandom().nextDouble() - 0.5) * 0.1);
    }

    /** Whether there is still water under the entity to go down into. */
    private boolean waterBelow() {
        return !mob.level().getFluidState(mob.blockPosition().below(2)).isEmpty();
    }

    /** Whoever this entity has reason to leave: what last hurt it, or the nearest player too close. */
    private LivingEntity threat() {
        LivingEntity hurtBy = mob.getLastHurtByMob();
        if (hurtBy != null && hurtBy.isAlive()) return hurtBy;

        List<Player> watching = mob.level().getEntitiesOfClass(Player.class,
            mob.getBoundingBox().inflate(THREAT_RANGE));
        return watching.stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }

    /**
     * The way off the solids the entity is touching, or null when it is touching none.
     *
     * Every side that is neither air nor fluid contributes the direction away from it, so an entity
     * wedged into a corner leaves along the one direction that is open.
     */
    private Vec3 awayFromGround() {
        BlockPos at = mob.blockPosition();
        Vec3 away = Vec3.ZERO;

        for (BlockPos side : List.of(at.below(), at.north(), at.south(), at.east(), at.west())) {
            if (mob.level().getBlockState(side).isAir()) continue;
            if (!mob.level().getFluidState(side).isEmpty()) continue;

            away = away.add(Vec3.atCenterOf(at).subtract(Vec3.atCenterOf(side)));
        }
        return away.lengthSqr() < 1.0E-4 ? null : away.normalize();
    }

    private Vec3 randomDirection() {
        double around = mob.getRandom().nextDouble() * Math.PI * 2;
        double rise = (mob.getRandom().nextDouble() - 0.5) * 0.6;
        return new Vec3(Math.cos(around), rise, Math.sin(around)).normalize();
    }
}
