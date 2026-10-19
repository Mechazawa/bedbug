package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Leaps over and around the target, `minecraft:behavior.jump_around_target`, the way a breeze does.
 *
 * Within `valid_distance_to_target` of a target it can see over anything lower than
 * `line_of_sight_obstruction_height_ignore`, with `required_vertical_space` blocks of air over its
 * head, it crouches for `prepare_jump_duration` seconds and jumps to a point behind the target:
 * `landing_distance_from_target` beyond it, within `landing_position_spread_degrees` of straight
 * behind, on the first surface within `snap_to_surface_block_range` blocks up or down. It tries
 * `jump_angles` in a random order for one whose speed stays within `max_jump_velocity` and, under
 * `check_collision`, whose arc clears the blocks for a box scaled by `entity_bounding_box_scale`.
 * Each jump is followed by `jump_cooldown_duration` seconds of rest, or by
 * `jump_cooldown_when_hurt_duration` when it was hurt within the last `last_hurt_duration`. The
 * arc is flown the way Java moves an entity through the air, so it comes down where it aimed.
 */
public final class JumpAroundGoal extends Goal {
    /** What a living entity falls by each tick, in blocks a tick each tick. */
    private static final double GRAVITY = 0.08;

    private final AddonEntity mob;
    private final Filter filters;
    private final double nearest;
    private final double furthest;
    private final double landNearest;
    private final double landFurthest;
    private final double spread;
    private final List<Integer> angles = new ArrayList<>();
    private final double maxVelocity;
    private final int prepare;
    private final int cooldown;
    private final int hurtCooldown;
    private final int hurtWindow;
    private final int headroom;
    private final int snapRange;
    private final double sightOver;
    private final boolean collides;
    private final float boxScale;
    private LivingEntity target;
    private int crouched;
    private int restUntil;
    private boolean failed;

    /** Ticks since the jump, or -1 while the entity is still crouching for it. */
    private int flown;

    public JumpAroundGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.filters = Goals.filter(goal, "filters");
        this.nearest = Goals.low(goal, "valid_distance_to_target", 4);
        this.furthest = Goals.high(goal, "valid_distance_to_target", 20);
        this.landNearest = Goals.low(goal, "landing_distance_from_target", 4);
        this.landFurthest = Goals.high(goal, "landing_distance_from_target", 8);
        this.spread = Goals.number(goal, "landing_position_spread_degrees", 90);
        for (JsonElement angle : at.ioexception.mcaddon.util.Json.list(goal, "jump_angles")) {
            angles.add(angle.getAsInt());
        }
        if (angles.isEmpty()) angles.addAll(List.of(40, 55, 60, 75, 80));
        this.maxVelocity = Goals.number(goal, "max_jump_velocity", 1.4);
        this.prepare = Goals.ticks(goal, "prepare_jump_duration", 0.5);
        this.cooldown = Goals.ticks(goal, "jump_cooldown_duration", 0.5);
        this.hurtCooldown = Goals.ticks(goal, "jump_cooldown_when_hurt_duration", 0.1);
        this.hurtWindow = Goals.ticks(goal, "last_hurt_duration", 2);
        this.headroom = (int) Goals.number(goal, "required_vertical_space", 4);
        this.snapRange = (int) Goals.number(goal, "snap_to_surface_block_range", 10);
        this.sightOver = Goals.number(goal, "line_of_sight_obstruction_height_ignore", 4);
        this.collides = Goals.flag(goal, "check_collision", false);
        this.boxScale = (float) Goals.number(goal, "entity_bounding_box_scale", 0.7);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity hunted = mob.getTarget();
        if (hunted == null || !hunted.isAlive() || mob.tickCount < restUntil || !mob.onGround()) return false;

        double distance = mob.distanceTo(hunted);
        if (distance < nearest || distance > furthest || !roomOverhead() || !seesOver(hunted)) return false;
        if (!filters.matches(new Filter.Context(mob, hunted, hunted, ServerHooks.state()))) return false;

        target = hunted;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (failed) return false;

        // A jump leaves the ground on the tick after it is made.
        return flown < 0 ? target.isAlive() : flown < 2 || !mob.onGround();
    }

    @Override
    public void start() {
        crouched = 0;
        flown = -1;
        failed = false;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        boolean hurtLately = mob.getLastHurtByMob() != null
            && mob.tickCount - mob.getLastHurtByMobTimestamp() <= hurtWindow;
        restUntil = mob.tickCount + (hurtLately ? hurtCooldown : cooldown);
        target = null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (flown >= 0) {
            flown++;
            return;
        }

        mob.getLookControl().setLookAt(target, 30, 30);
        if (++crouched < prepare) return;

        Optional<Vec3> jump = aim();
        failed = jump.isEmpty();
        jump.ifPresent(launch -> {
            mob.setDeltaMovement(launch);
            flown = 0;
        });
    }

    private boolean roomOverhead() {
        BlockPos head = BlockPos.containing(mob.getX(), mob.getBoundingBox().maxY, mob.getZ());
        for (int above = 0; above < headroom; above++) {
            if (!mob.level().getBlockState(head.above(above)).getCollisionShape(mob.level(), head.above(above))
                .isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Whether the target can be seen from the eyes, or from as high over them as the goal ignores. */
    private boolean seesOver(LivingEntity hunted) {
        Vec3 eyes = mob.getEyePosition();
        Vec3 over = eyes.add(0, sightOver, 0);
        return mob.getSensing().hasLineOfSight(hunted) || mob.level().clip(new ClipContext(over,
            hunted.getEyePosition(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob)).getType()
            == HitResult.Type.MISS;
    }

    private Optional<Vec3> aim() {
        Vec3 behind = new Vec3(target.getX() - mob.getX(), 0, target.getZ() - mob.getZ()).normalize();
        double turn = Math.toRadians((mob.getRandom().nextDouble() - 0.5) * spread);
        Vec3 heading = behind.yRot((float) turn);
        double reach = landNearest + mob.getRandom().nextDouble() * (landFurthest - landNearest);
        Vec3 landing = surface(target.position().add(heading.scale(reach)));
        if (landing == null) return Optional.empty();

        List<Integer> tried = new ArrayList<>(angles);
        Collections.shuffle(tried, new java.util.Random(mob.getRandom().nextLong()));
        return tried.stream().map(angle -> arc(landing, angle)).flatMap(Optional::stream).findFirst();
    }

    /** The first standing place within the snap range above or below a point, nearest first. */
    private Vec3 surface(Vec3 point) {
        BlockPos at = BlockPos.containing(point);
        for (int step = 0; step <= snapRange * 2; step++) {
            int offset = step % 2 == 0 ? step / 2 : -(step + 1) / 2;
            BlockPos feet = at.above(offset);
            if (mob.level().getBlockState(feet).getCollisionShape(mob.level(), feet).isEmpty()
                && !mob.level().getBlockState(feet.below()).getCollisionShape(mob.level(), feet.below()).isEmpty()) {
                return new Vec3(point.x, feet.getY(), point.z);
            }
        }
        return null;
    }

    /**
     * The launch that lands on the point when leaving at this many degrees above level, or nothing
     * where that needs more than the goal's top speed or, under `check_collision`, hits a block.
     *
     * The speed is found by flying the arc the way Java moves a living entity through the air, which
     * keeps nine tenths of its speed across and takes gravity off the rise each tick, so the entity
     * comes down where it was aimed.
     */
    private Optional<Vec3> arc(Vec3 landing, int degrees) {
        Vec3 way = landing.subtract(mob.position());
        double flat = Math.sqrt(way.x * way.x + way.z * way.z);
        double pitch = Math.toRadians(degrees);
        if (reach(maxVelocity, pitch, way.y) < flat) return Optional.empty();

        double slow = 0;
        double fast = maxVelocity;
        for (int halving = 0; halving < SEARCH_STEPS; halving++) {
            double speed = (slow + fast) / 2;
            if (reach(speed, pitch, way.y) < flat) {
                slow = speed;
            } else {
                fast = speed;
            }
        }
        Vec3 heading = new Vec3(way.x / flat, 0, way.z / flat);
        Vec3 launch = heading.scale(fast * Math.cos(pitch)).add(0, fast * Math.sin(pitch), 0);
        if (collides && !clear(launch, way.y)) return Optional.empty();

        return Optional.of(launch);
    }

    private static final int SEARCH_STEPS = 24;
    private static final int LONGEST_FLIGHT = 200;
    private static final double AIR_KEPT = 0.91;
    private static final double FALL_KEPT = 0.98;

    /** The tick of the launch is still taken on the ground, whose ordinary friction keeps less. */
    private static final double GROUND_KEPT = 0.6 * AIR_KEPT;

    /** How far across a launch at this speed and pitch comes down at the given height below or above. */
    private static double reach(double speed, double pitch, double drop) {
        List<Vec3> path = flight(new Vec3(speed * Math.cos(pitch), speed * Math.sin(pitch), 0), drop);
        return path.isEmpty() ? 0 : path.get(path.size() - 1).x;
    }

    /** Each position of a flight along x from the origin, ending where it comes down to the height. */
    private static List<Vec3> flight(Vec3 launch, double drop) {
        List<Vec3> path = new ArrayList<>();
        Vec3 at = Vec3.ZERO;
        Vec3 moving = launch;
        for (int tick = 0; tick < LONGEST_FLIGHT; tick++) {
            at = at.add(moving);
            path.add(at);
            if (moving.y < 0 && at.y <= drop) return path;

            moving = new Vec3(moving.x * (tick == 0 ? GROUND_KEPT : AIR_KEPT), (moving.y - GRAVITY) * FALL_KEPT, 0);
        }
        return path;
    }

    private boolean clear(Vec3 launch, double drop) {
        EntityDimensions box = mob.getDimensions(mob.getPose()).scale(boxScale);
        double across = Math.sqrt(launch.x * launch.x + launch.z * launch.z);
        Vec3 heading = new Vec3(launch.x / across, 0, launch.z / across);
        List<Vec3> path = flight(new Vec3(across, launch.y, 0), drop);
        // The last step ends a little inside whatever the entity lands on.
        return path.subList(0, Math.max(0, path.size() - 1)).stream()
            .map(step -> mob.position().add(heading.scale(step.x)).add(0, step.y, 0))
            .allMatch(at -> mob.level().noCollision(mob, box.makeBoundingBox(at)));
    }
}
