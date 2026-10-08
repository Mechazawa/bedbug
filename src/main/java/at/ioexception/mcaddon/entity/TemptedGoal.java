package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;

/**
 * Follows a player holding one of the items `minecraft:behavior.tempt` names.
 *
 * Java's own tempt goal reaches ten blocks in every direction, stops two and a half blocks short
 * and is never startled; Bedrock states the reach, whether height counts towards it, where to stop
 * and whether a sudden move scares the entity off, so the walk is its own here. Being startled is
 * Java's rule for it: the player moving or turning while within six blocks.
 */
final class TemptedGoal extends Goal {
    /** How close a player has to be for moving to startle the entity, which is Java's own six. */
    private static final double STARTLE_RANGE = 6;

    /** How long a startled or disappointed entity waits before it can be tempted again, Java's five seconds. */
    private static final int CALM_DOWN_TICKS = 100;

    /** Java's reach for a tempt goal, which a pack stating no `within_radius` gets. */
    private static final double JAVA_REACH = 10;

    private final AddonEntity mob;
    private final double speed;
    private final Set<String> items;
    private final double reach;
    private final boolean vertical;
    private final double stopDistance;
    private final boolean scares;
    private final boolean whileRidden;
    private final String startEvent;
    private final String endEvent;
    private Player player;
    private double seenX;
    private double seenY;
    private double seenZ;
    private float seenPitch;
    private float seenYaw;
    private int calmDown;

    TemptedGoal(AddonEntity mob, JsonObject goal, Set<String> items) {
        this.mob = mob;
        this.items = items;
        this.speed = Goals.number(goal, "speed_multiplier", 1);
        double stated = Goals.number(goal, "within_radius", 0);
        this.reach = stated > 0 ? stated : JAVA_REACH;
        this.vertical = Goals.flag(goal, "can_tempt_vertically", false);
        this.stopDistance = Goals.number(goal, "stop_distance", 1.5);
        this.scares = Goals.flag(goal, "can_get_scared", false);
        this.whileRidden = Goals.flag(goal, "can_tempt_while_ridden", false);
        this.startEvent = Goals.event(goal, "on_tempt_start");
        this.endEvent = Goals.event(goal, "on_tempt_end");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (calmDown > 0) {
            calmDown--;
            return false;
        }
        if (!whileRidden && mob.isVehicle()) return false;

        player = nearestTempting();
        return player != null;
    }

    /**
     * The nearest player holding something the entity wants, within the reach.
     *
     * Without `can_tempt_vertically` only the distance across counts, so a player on a ledge above
     * tempts the entity as readily as one beside it.
     */
    private Player nearestTempting() {
        return mob.level().players().stream()
            .filter(candidate -> candidate.isAlive() && !candidate.isSpectator())
            .filter(candidate -> Platform.tempts(items, candidate.getMainHandItem())
                || Platform.tempts(items, candidate.getOffhandItem()))
            .filter(candidate -> within(candidate) <= reach)
            .min(Comparator.comparingDouble(this::within))
            .orElse(null);
    }

    private double within(Player candidate) {
        double dx = candidate.getX() - mob.getX();
        double dz = candidate.getZ() - mob.getZ();
        double dy = vertical ? candidate.getY() - mob.getY() : 0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean canContinueToUse() {
        if (scares && player != null && startled()) return false;
        Player still = player;
        return canUse() && player == still;
    }

    private boolean startled() {
        if (mob.distanceTo(player) < STARTLE_RANGE) {
            if (player.distanceToSqr(seenX, seenY, seenZ) > 0.01) return true;
            if (Math.abs(player.getXRot() - seenPitch) > 5 || Math.abs(player.getYRot() - seenYaw) > 5) return true;
        } else {
            remember();
        }
        seenPitch = player.getXRot();
        seenYaw = player.getYRot();
        return false;
    }

    private void remember() {
        seenX = player.getX();
        seenY = player.getY();
        seenZ = player.getZ();
    }

    @Override
    public void start() {
        remember();
        seenPitch = player.getXRot();
        seenYaw = player.getYRot();
        if (startEvent != null) mob.raiseEvent(startEvent);
    }

    @Override
    public void stop() {
        player = null;
        mob.getNavigation().stop();
        calmDown = CALM_DOWN_TICKS;
        if (endEvent != null) mob.raiseEvent(endEvent);
    }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(player, mob.getMaxHeadYRot() + 20, mob.getMaxHeadXRot());
        if (mob.distanceTo(player) < stopDistance) {
            mob.getNavigation().stop();
        } else {
            mob.getNavigation().moveTo(player, speed);
        }
    }
}
