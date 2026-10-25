package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
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

    /** How many spots a stroll avoiding damaging blocks looks at before it gives up for this chance. */
    private static final int SAFE_ATTEMPTS = 10;

    /** How far a flight looks for a tree to land on, which is Java's parrot's search. */
    private static final int TREE_ACROSS = 3;
    private static final int TREE_UP_DOWN = 6;

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
    /** Whether a spot over a block that hurts, or beside one, is passed over. */
    private final boolean avoidsDamage;
    private boolean landsOnTrees;
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
        this.avoidsDamage = Goals.flag(goal, "avoid_damage_blocks", false);
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
     * `minecraft:behavior.random_hover` and `hover`: somewhere in the air ahead, the way Java's own
     * flying stroll looks, within `hover_height` of the ground under it.
     */
    static StrollGoal flying(PathfinderMob mob, JsonObject goal) {
        return new StrollGoal(mob, goal, oneIn(goal), Medium.AIR, Integer.MAX_VALUE, Integer.MAX_VALUE, false);
    }

    /**
     * `minecraft:behavior.random_fly`, which flies the same way and, under `can_land_on_trees`, lands
     * on a tree where there is one to land on.
     */
    static StrollGoal flyingAbout(PathfinderMob mob, JsonObject goal) {
        StrollGoal flight = flying(mob, goal);
        flight.landsOnTrees = Goals.flag(goal, "can_land_on_trees", true);
        return flight;
    }

    private static int oneIn(JsonObject goal) {
        return Math.max(1, (int) Goals.number(goal, "interval", 120));
    }

    @Override
    protected Vec3 getPosition() {
        for (int attempt = 0; attempt < SAFE_ATTEMPTS; attempt++) {
            Vec3 picked = landsOnTrees ? treeTop() : null;
            if (picked == null) picked = pick();
            if (picked == null || !avoidsDamage || !Platform.hurtsAt(mob, BlockPos.containing(picked))) return picked;
        }
        return null;
    }

    private Vec3 pick() {
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

    /**
     * Room to stand on a log or on leaves nearby, which is where Java's parrot lands. Bedrock publishes
     * no box for the search, so it is the parrot's: three blocks across and six up or down.
     */
    private Vec3 treeTop() {
        BlockPos at = mob.blockPosition();
        for (BlockPos spot : BlockPos.betweenClosed(at.offset(-TREE_ACROSS, -TREE_UP_DOWN, -TREE_ACROSS),
            at.offset(TREE_ACROSS, TREE_UP_DOWN, TREE_ACROSS))) {
            if (spot.equals(at)) continue;

            var below = mob.level().getBlockState(spot.below());
            boolean perch = below.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
                || below.is(net.minecraft.tags.BlockTags.LOGS);
            if (perch && mob.level().isEmptyBlock(spot) && mob.level().isEmptyBlock(spot.above())) {
                return Vec3.atBottomCenterOf(spot);
            }
        }
        return null;
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
