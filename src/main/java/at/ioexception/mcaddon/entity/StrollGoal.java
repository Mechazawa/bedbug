package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.AirAndWaterRandomPos;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.HoverRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * Wanders to somewhere `xz_dist` blocks across and `y_dist` up or down, on land, through water or
 * through the air, `y_offset` higher than the spot picked.
 *
 * Java's stroll, swim and flying goals pick a spot ten across and seven up or down whatever they are
 * told, which is Bedrock's default as well; a pack's own distances are what this goal adds.
 * `interval` is the one in N chance of setting out, which Java counts the same way, and an interval
 * of nought, which Mojang's own fish write, sets out at every chance.
 */
final class StrollGoal extends RandomStrollGoal {
    private enum Medium { LAND, WATER, AIR }

    /** How far below a spot in the air the ground a hover keeps its height over is looked for. */
    private static final int GROUND_SEARCH = 64;

    private final int across;
    private final int upDown;
    private final double offset;
    private final Medium medium;
    /** The height over the ground a hover keeps to, or nothing for a goal that names none. */
    private final double lowest;
    private final double highest;
    /** The ticks one wander may last before the goal gives it up, picked from this range as it sets out. */
    private final int shortestWander;
    private final int longestWander;
    private final boolean reselects;
    /** Whether a spot in the top layer of the water is taken one block lower. */
    private final boolean belowTheSurface;
    private int wandered;
    private int wanderFor;

    private StrollGoal(PathfinderMob mob, JsonObject goal, int interval, Medium medium, int shortest, int longest,
        boolean belowTheSurface) {
        super(mob, Goals.number(goal, "speed_multiplier", 1), interval);
        this.across = Math.max(1, (int) Goals.number(goal, "xz_dist", 10));
        this.upDown = Math.max(1, (int) Goals.number(goal, "y_dist", 7));
        this.offset = Goals.number(goal, "y_offset", 0);
        this.medium = medium;
        this.lowest = Goals.low(goal, "hover_height", Double.NaN);
        this.highest = Goals.high(goal, "hover_height", Double.NaN);
        this.shortestWander = shortest;
        this.longestWander = longest;
        this.reselects = Goals.flag(goal, "random_reselect", false);
        this.belowTheSurface = belowTheSurface;
    }

    /** `minecraft:behavior.random_stroll`: somewhere on dry land where there is any. */
    static StrollGoal walking(PathfinderMob mob, JsonObject goal) {
        return new StrollGoal(mob, goal, oneIn(goal), Medium.LAND, Integer.MAX_VALUE, Integer.MAX_VALUE, false);
    }

    /** `minecraft:behavior.random_swim`: somewhere in the water, under its surface unless `avoid_surface` says no. */
    static StrollGoal swimming(PathfinderMob mob, JsonObject goal) {
        return new StrollGoal(mob, goal, oneIn(goal), Medium.WATER, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Goals.flag(goal, "avoid_surface", true));
    }

    /**
     * `minecraft:behavior.swim_wander`, whose `interval` is the chance of setting out rather than a one
     * in N, and whose wander lasts `wander_time` seconds at most.
     */
    static StrollGoal wandering(PathfinderMob mob, JsonObject goal) {
        double chance = Goals.number(goal, "interval", 0.00833);
        int interval = chance <= 0 ? Integer.MAX_VALUE : (int) Math.max(1, Math.round(1 / chance));
        int longest = Goals.ticks(goal, "wander_time", 5);
        return new StrollGoal(mob, goal, interval, Medium.WATER, longest, longest, false);
    }

    /**
     * `minecraft:behavior.random_fly`, `random_hover` and `hover`: somewhere in the air ahead, the way
     * Java's own flying stroll looks. A hover keeps the spot within `hover_height` of the ground under it.
     */
    static StrollGoal flying(PathfinderMob mob, JsonObject goal) {
        return new StrollGoal(mob, goal, oneIn(goal), Medium.AIR, Integer.MAX_VALUE, Integer.MAX_VALUE, false);
    }

    /**
     * `minecraft:behavior.float_wander`, which names no interval and floats off again at every chance,
     * as Java's ghast does. A float lasts as long as `float_duration` says, where it says anything.
     */
    static StrollGoal floating(PathfinderMob mob, JsonObject goal) {
        int longest = Goals.ticksHigh(goal, "float_duration", 0);
        return longest <= 0
            ? new StrollGoal(mob, goal, 1, Medium.AIR, Integer.MAX_VALUE, Integer.MAX_VALUE, false)
            : new StrollGoal(mob, goal, 1, Medium.AIR, Goals.ticksLow(goal, "float_duration", 0), longest, false);
    }

    private static int oneIn(JsonObject goal) {
        return Math.max(1, (int) Goals.number(goal, "interval", 120));
    }

    @Override
    protected Vec3 getPosition() {
        Vec3 picked = switch (medium) {
            case WATER -> {
                Vec3 swim = BehaviorUtils.getRandomSwimmablePos(mob, across, upDown);
                yield swim != null && belowTheSurface && atTheSurface(BlockPos.containing(swim))
                    ? swim.add(0, -1, 0) : swim;
            }
            case AIR -> {
                Vec3 ahead = mob.getViewVector(0);
                Vec3 hover = HoverRandomPos.getPos(mob, across, upDown, ahead.x, ahead.z, (float) (Math.PI / 2), 3, 1);
                yield hover != null ? hover
                    : AirAndWaterRandomPos.getPos(mob, across, upDown, -2, ahead.x, ahead.z, (float) (Math.PI / 2));
            }
            case LAND -> {
                Vec3 land = LandRandomPos.getPos(mob, across, upDown);
                yield land != null ? land : DefaultRandomPos.getPos(mob, across, upDown);
            }
        };
        return picked == null ? null : overTheGround(picked.add(0, offset, 0));
    }

    private boolean atTheSurface(BlockPos spot) {
        return water(spot) && !water(spot.above()) && water(spot.below());
    }

    private boolean water(BlockPos at) {
        return mob.level().getFluidState(at).is(net.minecraft.tags.FluidTags.WATER);
    }

    /** The spot moved up or down into `hover_height` over the first solid block beneath it. */
    private Vec3 overTheGround(Vec3 spot) {
        if (Double.isNaN(lowest)) return spot;

        BlockPos from = BlockPos.containing(spot);
        for (int depth = 0; depth < GROUND_SEARCH; depth++) {
            BlockPos below = from.below(depth);
            if (mob.level().getBlockState(below).getCollisionShape(mob.level(), below).isEmpty()) continue;

            double ground = below.getY() + 1;
            return new Vec3(spot.x, Mth.clamp(spot.y, ground + lowest, ground + Math.max(lowest, highest)), spot.z);
        }
        return spot;
    }

    @Override
    public void start() {
        super.start();
        wandered = 0;
        wanderFor = longestWander == Integer.MAX_VALUE ? longestWander
            : Goals.between(mob.getRandom(), shortestWander, longestWander);
    }

    @Override
    public boolean canContinueToUse() {
        return super.canContinueToUse() && wandered < wanderFor;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** Under `random_reselect` a wander picks a new spot on the way, as often as it sets out at all. */
    @Override
    public void tick() {
        wandered++;
        if (!reselects || mob.getRandom().nextInt(interval) != 0) return;

        Vec3 next = getPosition();
        if (next != null) mob.getNavigation().moveTo(next.x, next.y, next.z, speedModifier);
    }
}
