package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * The four phases a Bedrock dragon spends between fights: circling, settling, leaving and watching.
 *
 * Java keeps these on `EnderDragon` as phases of its own, reachable from that entity and no other,
 * and each of them is written around the end podium. A pack's entity is a pathfinding mob with a
 * place it is kept near, so that place is what it circles, lands on and takes off from, and where it
 * stands while it picks somebody to go after. None of the four carries a value in its component body.
 */
public final class DragonFlightGoal extends Goal {
    /** How high above its anchor the entity cruises. */
    private static final int CRUISE_HEIGHT = 16;

    /** How wide the circle is that it holds. */
    private static final int CIRCLE_RADIUS = 20;

    /** How far round the circle one tick carries it, in degrees. */
    private static final double CIRCLE_STEP = 3;

    /** How fast it climbs and sinks, in blocks a tick. */
    private static final double CLIMB_SPEED = 0.3;

    /** How close to the cruising height counts as having got there. */
    private static final double HEIGHT_REACHED = 2;

    /** How far it looks for somebody worth going after. */
    private static final double SCAN_RANGE = 64;

    /** How long it watches before picking, and how long a takeoff or a landing may take. */
    private static final int SCAN_DURATION = 60;
    private static final int CLIMB_LIMIT = 200;

    /** Ticks between two looks for somebody to watch. */
    private static final int SCAN_INTERVAL = 20;

    private final AddonEntity mob;
    private final Kind kind;
    private Vec3 anchor;
    private Player watched;
    private double angle;
    private int held;

    private enum Kind { CIRCLING, TAKEOFF, LANDING, SCANNING }

    private DragonFlightGoal(AddonEntity mob, Kind kind) {
        this.mob = mob;
        this.kind = kind;
        setFlags(kind == Kind.SCANNING ? EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK)
            : EnumSet.of(Goal.Flag.MOVE));
    }

    /** `minecraft:behavior.dragonholdingpattern`, the circuit it flies with nothing else to do. */
    public static DragonFlightGoal circling(AddonEntity mob) {
        return new DragonFlightGoal(mob, Kind.CIRCLING);
    }

    /** `minecraft:behavior.dragontakeoff`, the climb back up off the place it was perched. */
    public static DragonFlightGoal takingOff(AddonEntity mob) {
        return new DragonFlightGoal(mob, Kind.TAKEOFF);
    }

    /** `minecraft:behavior.dragonlanding`, the descent onto it. */
    public static DragonFlightGoal landing(AddonEntity mob) {
        return new DragonFlightGoal(mob, Kind.LANDING);
    }

    /** `minecraft:behavior.dragonscanning`, the look round for somebody while it is perched. */
    public static DragonFlightGoal scanning(AddonEntity mob) {
        return new DragonFlightGoal(mob, Kind.SCANNING);
    }

    @Override
    public boolean canUse() {
        anchor = anchor();
        return switch (kind) {
            case CIRCLING -> mob.getTarget() == null && aloft();
            case TAKEOFF -> mob.getTarget() != null && !aloft();
            case LANDING -> mob.getTarget() == null && aloft();
            default -> mob.getTarget() == null && !aloft();
        };
    }

    @Override
    public boolean canContinueToUse() {
        return switch (kind) {
            case CIRCLING -> mob.getTarget() == null;
            case TAKEOFF -> held < CLIMB_LIMIT && !aloft();
            case LANDING -> held < CLIMB_LIMIT && aloft() && mob.getTarget() == null;
            default -> held < SCAN_DURATION && mob.getTarget() == null;
        };
    }

    @Override
    public void start() {
        held = 0;
        watched = null;
        angle = mob.getRandom().nextDouble() * Math.PI * 2;
        mob.getNavigation().stop();
    }

    /** The selector ticks a running goal every second tick unless it asks for each one. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        held++;
        switch (kind) {
            case CIRCLING -> circle();
            case TAKEOFF -> climb(CLIMB_SPEED);
            case LANDING -> climb(-CLIMB_SPEED);
            default -> watch();
        }
    }

    /** A point further round the circuit each tick, flown to rather than walked to. */
    private void circle() {
        angle += Math.toRadians(CIRCLE_STEP);
        Vec3 next = anchor.add(Math.cos(angle) * CIRCLE_RADIUS, CRUISE_HEIGHT,
            Math.sin(angle) * CIRCLE_RADIUS);
        mob.getMoveControl().setWantedPosition(next.x, next.y, next.z, 1);
    }

    /** A push up or down, held over the anchor so the entity arrives above where it started. */
    private void climb(double rise) {
        Vec3 moving = mob.getDeltaMovement();
        Vec3 towards = anchor.subtract(mob.position()).multiply(1, 0, 1).normalize().scale(0.05);
        mob.setDeltaMovement(moving.x * 0.9 + towards.x, rise, moving.z * 0.9 + towards.z);
    }

    /** Standing still and turning towards whoever is nearest, which is who it then goes after. */
    private void watch() {
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
        // Reading every player inside a scanning range costs too much to ask of every tick.
        if (held % SCAN_INTERVAL == 1) watched = nearestPlayer();
        if (watched == null || !watched.isAlive()) return;

        mob.getLookControl().setLookAt(watched, 30f, 30f);
        if (held >= SCAN_DURATION) mob.setTarget(watched);
    }

    private Player nearestPlayer() {
        List<Player> nearby = mob.level().getEntitiesOfClass(Player.class,
            mob.getBoundingBox().inflate(SCAN_RANGE), Player::isAlive);
        return nearby.stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }

    /** Whether the entity has got up to the height it holds its circuit at. */
    private boolean aloft() {
        return mob.getY() - anchor.y >= CRUISE_HEIGHT - HEIGHT_REACHED;
    }

    /** The place the entity is kept near, or where it is standing when the pack named none. */
    private Vec3 anchor() {
        if (anchor != null) return anchor;

        BlockPos home = Platform.homeOf(mob);
        return home == null ? mob.position() : Vec3.atCenterOf(home);
    }
}
