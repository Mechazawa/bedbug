package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Two things Bedrock has a dolphin do that no other mob does: leaping clear of the water and leading
 * a swimmer to buried treasure.
 *
 * Java writes both into `Dolphin` itself, so a pack's own sea creature can reach neither of them,
 * and each is short enough that the two belong together.
 */
public final class DolphinGoal extends Goal {
    /** How hard a breach throws the entity upward, which clears the surface from just below it. */
    private static final double BREACH_LIFT = 0.6;

    /** How far above the water a breach still counts as being in it. */
    private static final int SURFACE_REACH = 3;

    /** How many spots a breach looks at for water to come down in. */
    private static final int LANDING_ATTEMPTS = 10;

    /** What is left of an entity's speed across after a tick in the air. */
    private static final double AIR_DRAG = 0.91;

    /** About how long a breach is out of the water, rising at its lift and falling back. */
    private static final int BREACH_TICKS = 15;

    /** Ticks between two searches for buried treasure. */
    private static final int SEARCH_INTERVAL = 200;

    private final AddonEntity mob;
    private final Kind kind;
    private final double speed;
    private final double searchRange;
    private final double stopDistance;
    private final int interval;
    private final int across;
    private final int cooldown;
    private int readyAt;
    private int untilSearch;
    private BlockPos treasure;
    private Vec3 landing;

    private enum Kind { BREACH, TREASURE }

    private DolphinGoal(AddonEntity mob, JsonObject component, Kind kind) {
        this.mob = mob;
        this.kind = kind;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.searchRange = Goals.number(component, "search_range", 30);
        this.stopDistance = Goals.number(component, "stop_distance", 50);
        this.interval = Math.max(1, (int) Goals.number(component, "interval", 120));
        this.across = Math.max(1, (int) Goals.number(component, "xz_dist", 10));
        this.cooldown = Goals.ticks(component, "cooldown_time", 0);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * `minecraft:behavior.random_breach`, which leaps clear of the water on a one in `interval` chance,
     * towards water up to `xz_dist` across, and then waits `cooldown_time` before it may again.
     */
    public static DolphinGoal breaching(AddonEntity mob, JsonObject component) {
        return new DolphinGoal(mob, component, Kind.BREACH);
    }

    /** `minecraft:behavior.find_underwater_treasure`, which swims towards what is buried nearby. */
    public static DolphinGoal seekingTreasure(AddonEntity mob, JsonObject component) {
        return new DolphinGoal(mob, component, Kind.TREASURE);
    }

    @Override
    public boolean canUse() {
        if (!mob.isInWater()) return false;

        return switch (kind) {
            case BREACH -> readyToBreach();
            case TREASURE -> {
                // Finding a structure searches generated chunks, which is far too heavy to ask on
                // every tick, so the goal keeps whatever it last found until it looks again.
                if (untilSearch-- <= 0) {
                    untilSearch = SEARCH_INTERVAL;
                    treasure = buriedNearby();
                }
                yield treasure != null;
            }
        };
    }

    @Override
    public boolean canContinueToUse() {
        return switch (kind) {
            case BREACH -> false;
            case TREASURE -> treasure != null
                && mob.distanceToSqr(Vec3.atCenterOf(treasure)) < stopDistance * stopDistance;
        };
    }

    @Override
    public void start() {
        if (kind != Kind.BREACH) return;

        readyAt = mob.tickCount + cooldown;
        // Fast enough across that the leap comes down at the spot, slowed by the air as it goes.
        double carried = (1 - Math.pow(AIR_DRAG, BREACH_TICKS)) / (1 - AIR_DRAG);
        Vec3 drift = landing.subtract(mob.position()).multiply(1, 0, 1).scale(1 / carried);
        mob.setDeltaMovement(drift.x, BREACH_LIFT, drift.z);
    }

    @Override
    public void stop() {
        treasure = null;
    }

    @Override
    public void tick() {
        if (kind == Kind.TREASURE && treasure != null) {
            mob.getNavigation().moveTo(treasure.getX() + 0.5, treasure.getY() + 0.5,
                treasure.getZ() + 0.5, speed);
        }
    }

    private boolean readyToBreach() {
        if (mob.tickCount < readyAt || mob.getRandom().nextInt(reducedTickDelay(interval)) != 0) return false;
        if (!nearTheSurface()) return false;

        landing = landing();
        return landing != null;
    }

    /** Whether there is open air close enough above for a leap to break the surface. */
    private boolean nearTheSurface() {
        return mob.level().getBlockState(mob.blockPosition().above(SURFACE_REACH)).isAir();
    }

    /**
     * Somewhere up to `xz_dist` across where the surface of the water lies at the height it lies at
     * over the entity, which is where the leap comes down.
     */
    private Vec3 landing() {
        BlockPos surface = mob.blockPosition();
        while (surface.getY() < mob.blockPosition().getY() + SURFACE_REACH && water(surface)) {
            surface = surface.above();
        }

        for (int attempt = 0; attempt < LANDING_ATTEMPTS; attempt++) {
            BlockPos spot = surface.offset(mob.getRandom().nextInt(across * 2 + 1) - across, 0,
                mob.getRandom().nextInt(across * 2 + 1) - across);
            if (water(spot.below()) && mob.level().getBlockState(spot).isAir()) return Vec3.atBottomCenterOf(spot);
        }
        return null;
    }

    private boolean water(BlockPos at) {
        return mob.level().getFluidState(at).is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * The nearest buried treasure, or null where there is none in range.
     *
     * `minecraft:dolphin_located` is the tag Java keeps for exactly this question, so what a pack's
     * entity leads a player to is what a vanilla dolphin would have led them to.
     */
    private BlockPos buriedNearby() {
        if (!(mob.level() instanceof ServerLevel level)) return null;

        return level.findNearestMapStructure(net.minecraft.tags.StructureTags.DOLPHIN_LOCATED,
            mob.blockPosition(), (int) searchRange, false);
    }
}
