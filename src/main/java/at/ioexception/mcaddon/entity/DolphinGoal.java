package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * The three things Bedrock has a dolphin do that no other mob does.
 *
 * Leaping clear of the water, leading a swimmer to buried treasure, and keeping pace with one. Java
 * writes all three into `Dolphin` itself, so a pack's own sea creature can reach none of them, and
 * each is short enough that the three belong together rather than in three files.
 */
public final class DolphinGoal extends Goal {
    /** How hard a breach throws the entity upward, which clears the surface from just below it. */
    private static final double BREACH_LIFT = 0.6;

    /** How far above the water a breach still counts as being in it. */
    private static final int SURFACE_REACH = 3;

    /** Ticks between two searches for buried treasure. */
    private static final int SEARCH_INTERVAL = 200;

    private final AddonEntity mob;
    private final Kind kind;
    private final double speed;
    private final double searchRange;
    private final double stopDistance;
    private final int interval;
    private final double reach;
    private int readyAt;
    private int untilSearch;
    private Player swimmer;
    private BlockPos treasure;

    private enum Kind { BREACH, TREASURE, ESCORT }

    private DolphinGoal(AddonEntity mob, JsonObject component, Kind kind) {
        this.mob = mob;
        this.kind = kind;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.searchRange = Goals.number(component, "search_range", 30);
        this.stopDistance = Goals.number(component, "stop_distance", 50);
        this.interval = (int) Goals.number(component, "interval", 50);
        this.reach = Goals.number(component, "catch_up_threshold", 12);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** `minecraft:behavior.random_breach`, which leaps clear of the water every so often. */
    public static DolphinGoal breaching(AddonEntity mob, JsonObject component) {
        return new DolphinGoal(mob, component, Kind.BREACH);
    }

    /** `minecraft:behavior.find_underwater_treasure`, which swims towards what is buried nearby. */
    public static DolphinGoal seekingTreasure(AddonEntity mob, JsonObject component) {
        return new DolphinGoal(mob, component, Kind.TREASURE);
    }

    /** `minecraft:behavior.swim_with_entity`, which keeps pace with a player who is swimming. */
    public static DolphinGoal escorting(AddonEntity mob, JsonObject component) {
        return new DolphinGoal(mob, component, Kind.ESCORT);
    }

    @Override
    public boolean canUse() {
        if (!mob.isInWater()) return false;

        return switch (kind) {
            case BREACH -> mob.tickCount >= readyAt && nearTheSurface();
            case TREASURE -> {
                // Finding a structure searches generated chunks, which is far too heavy to ask on
                // every tick, so the goal keeps whatever it last found until it looks again.
                if (untilSearch-- <= 0) {
                    untilSearch = SEARCH_INTERVAL;
                    treasure = buriedNearby();
                }
                yield treasure != null;
            }
            default -> {
                swimmer = swimmerNearby();
                yield swimmer != null;
            }
        };
    }

    @Override
    public boolean canContinueToUse() {
        return switch (kind) {
            case BREACH -> false;
            case TREASURE -> treasure != null
                && mob.distanceToSqr(Vec3.atCenterOf(treasure)) < stopDistance * stopDistance;
            default -> swimmer != null && swimmer.isAlive() && swimmer.isSwimming();
        };
    }

    @Override
    public void start() {
        if (kind != Kind.BREACH) return;

        readyAt = mob.tickCount + Math.max(1, interval);
        Vec3 moving = mob.getDeltaMovement();
        mob.setDeltaMovement(moving.x, BREACH_LIFT, moving.z);
    }

    @Override
    public void stop() {
        swimmer = null;
        treasure = null;
    }

    @Override
    public void tick() {
        if (kind == Kind.TREASURE && treasure != null) {
            mob.getNavigation().moveTo(treasure.getX() + 0.5, treasure.getY() + 0.5,
                treasure.getZ() + 0.5, speed);
            return;
        }
        if (kind != Kind.ESCORT || swimmer == null) return;

        mob.getLookControl().setLookAt(swimmer, 30f, 30f);
        // Falling behind is what the catch-up threshold is for: past it the entity swims harder.
        double urgency = mob.distanceTo(swimmer) > reach ? speed * 2 : speed;
        mob.getNavigation().moveTo(swimmer, urgency);
    }

    /** Whether there is open air close enough above for a leap to break the surface. */
    private boolean nearTheSurface() {
        return mob.level().getBlockState(mob.blockPosition().above(SURFACE_REACH)).isAir();
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

    /** The nearest player who is actually swimming, which is who a dolphin keeps pace with. */
    private Player swimmerNearby() {
        List<Player> nearby = mob.level().getEntitiesOfClass(Player.class,
            mob.getBoundingBox().inflate(searchRange), Player::isSwimming);
        return nearby.stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }
}
