package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Floats off to a spot up to `xz_dist` across and `y_dist` up or down, `y_offset` higher than the
 * one picked, the way Java's ghast picks one.
 *
 * `minecraft:behavior.float_wander` keeps a spot only when it passes every check the pack switched
 * on: inside the entity's home, clear of blocks by one more block all round, reached in a straight
 * line through no liquid, and reachable by the navigator. A spot near a surface is preferred under
 * `navigate_around_surface`, and the first spot passing the rest is taken when no near one turns up.
 * Java's ghast tries 64 spots for a surface; Bedrock publishes no count, so this tries the same 64.
 */
final class FloatWanderGoal extends Goal {
    private static final int ATTEMPTS = 64;

    /** The most paths one pick asks the navigator for under `must_reach`, which is the costly check. */
    private static final int PATHS = 8;

    /** How far apart the points are that a straight float is checked for liquid at. */
    private static final double LINE_STEP = 0.5;

    private final AddonEntity mob;
    private final int across;
    private final int upDown;
    private final double offset;
    private final int shortest;
    private final int longest;
    private final boolean reselects;
    private final boolean homeBound;
    private final boolean mustReach;
    private final boolean aroundSurface;
    private final int surfaceAcross;
    private final int surfaceUpDown;
    private final boolean throughLiquids;
    private final boolean buffered;
    private int wandered;
    private int wanderFor;
    private Spot next;

    private record Spot(Vec3 at, Path path) {
    }

    FloatWanderGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.across = Math.max(1, (int) Goals.number(goal, "xz_dist", 10));
        this.upDown = Math.max(1, (int) Goals.number(goal, "y_dist", 7));
        this.offset = Goals.number(goal, "y_offset", 0);
        this.shortest = Goals.ticksLow(goal, "float_duration", 0);
        this.longest = Goals.ticksHigh(goal, "float_duration", 0);
        this.reselects = Goals.flag(goal, "random_reselect", false);
        this.homeBound = Goals.flag(goal, "use_home_position_restriction", true);
        this.mustReach = Goals.flag(goal, "must_reach", false);
        this.aroundSurface = Goals.flag(goal, "navigate_around_surface", false);
        this.surfaceAcross = (int) Goals.number(goal, "surface_xz_dist", 8);
        this.surfaceUpDown = (int) Goals.number(goal, "surface_y_dist", 8);
        this.throughLiquids = Goals.flag(goal, "allow_navigating_through_liquids", false);
        this.buffered = Goals.flag(goal, "additional_collision_buffer", false);
        setFlags(Goals.flag(goal, "float_wander_has_move_control", true)
            ? EnumSet.of(Goal.Flag.MOVE) : EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        if (mob.isVehicle()) return false;

        next = pick();
        return next != null;
    }

    /** A float lasts as long as `float_duration` says, where it says anything. */
    @Override
    public void start() {
        go(next);
        wandered = 0;
        wanderFor = longest <= 0 ? Integer.MAX_VALUE : Goals.between(mob.getRandom(), shortest, longest);
    }

    @Override
    public boolean canContinueToUse() {
        return !mob.isVehicle() && !mob.getNavigation().isDone() && wandered < wanderFor;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** Under `random_reselect` a float picks a new spot on the way at every chance. */
    @Override
    public void tick() {
        wandered++;
        if (!reselects) return;

        Spot spot = pick();
        if (spot != null) go(spot);
    }

    private void go(Spot spot) {
        if (spot.path() != null) {
            mob.getNavigation().moveTo(spot.path(), 1);
        } else {
            mob.getNavigation().moveTo(spot.at().x, spot.at().y, spot.at().z, 1);
        }
    }

    private Spot pick() {
        Spot fallback = null;
        int paths = 0;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            Vec3 at = candidate();
            if (!allowed(at)) continue;

            boolean nearSurface = !aroundSurface || nearSurface(BlockPos.containing(at));
            if (!nearSurface && fallback != null) continue;

            Path path = null;
            if (mustReach) {
                if (paths++ >= PATHS) break;
                path = mob.getNavigation().createPath(BlockPos.containing(at), 0);
                if (path == null || !path.canReach()) continue;
            }
            if (nearSurface) return new Spot(at, path);
            fallback = new Spot(at, path);
        }
        return fallback;
    }

    private Vec3 candidate() {
        var random = mob.getRandom();
        return mob.position().add((random.nextDouble() * 2 - 1) * across,
            (random.nextDouble() * 2 - 1) * upDown + offset, (random.nextDouble() * 2 - 1) * across);
    }

    /** Room for the entity at the spot, inside its home and reached through no liquid, as the pack asks. */
    private boolean allowed(Vec3 at) {
        var box = mob.getBoundingBox().move(at.subtract(mob.position()));
        if (!mob.level().noCollision(mob, buffered ? box.inflate(1) : box)) return false;
        if (homeBound && Platform.hasHome(mob) && !Platform.withinHome(mob, BlockPos.containing(at))) return false;

        return throughLiquids || dryLine(at);
    }

    private boolean dryLine(Vec3 to) {
        Vec3 from = mob.position();
        int steps = (int) Math.ceil(from.distanceTo(to) / LINE_STEP);
        for (int step = 0; step <= steps; step++) {
            BlockPos at = BlockPos.containing(from.lerp(to, steps == 0 ? 1 : (double) step / steps));
            if (!mob.level().getFluidState(at).isEmpty()) return false;
        }
        return true;
    }

    /** A block that is neither air nor liquid within `surface_xz_dist` across or `surface_y_dist` up or down. */
    private boolean nearSurface(BlockPos at) {
        for (Direction direction : Direction.values()) {
            int reach = direction.getAxis() == Direction.Axis.Y ? surfaceUpDown : surfaceAcross;
            for (int distance = 1; distance <= reach; distance++) {
                BlockPos checked = at.relative(direction, distance);
                if (!mob.level().getBlockState(checked).isAir() && mob.level().getFluidState(checked).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
