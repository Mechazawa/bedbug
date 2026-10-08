package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Walks after the player who owns the entity, and steps to their side when they are too far ahead.
 *
 * `minecraft:behavior.follow_owner` is Java's own `FollowOwnerGoal`, which Java writes for its tamable
 * animals alone, so the same walk is carried out here over the owner taming stored. Bedrock states
 * the distances in blocks; the distance past which the entity teleports is the twelve Java's pets
 * teleport at, which Bedrock leaves unstated.
 */
final class FollowOwnerGoal extends Goal {
    private static final double TELEPORT_PAST = 12;

    /** How often the path to a moving owner is planned again, which is Java's own pet's pace. */
    private static final int REPATH_TICKS = 10;

    /** Places tried around the owner before the entity gives up and walks instead. */
    private static final int ATTEMPTS = 10;

    private final AddonEntity mob;
    private final double speed;
    private final double startDistance;
    private final double stopDistance;
    private final boolean canTeleport;
    private final double maxDistance;
    private final double postTeleportDistance;
    private LivingEntity owner;
    private int untilRepath;

    FollowOwnerGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.startDistance = Goals.number(component, "start_distance", 10);
        this.stopDistance = Goals.number(component, "stop_distance", 2);
        this.canTeleport = Goals.flag(component, "can_teleport", true);
        this.maxDistance = Goals.number(component, "max_distance", 60);
        this.postTeleportDistance = Goals.number(component, "post_teleport_distance", stopDistance + 1);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity found = mob.getOwner();
        if (found == null || found.isSpectator() || found.level() != mob.level() || held()) return false;

        double distance = mob.distanceTo(found);
        if (distance < startDistance || (!canTeleport && distance > maxDistance)) return false;

        owner = found;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return owner != null && owner.isAlive() && !held() && !mob.getNavigation().isDone()
            && mob.distanceTo(owner) > stopDistance;
    }

    /** A sitting, ridden or leashed entity stays where it was put, as Java's pets do. */
    private boolean held() {
        return mob.getPose() == Pose.SITTING || mob.isPassenger() || mob.isLeashed();
    }

    @Override
    public void start() {
        untilRepath = 0;
    }

    @Override
    public void stop() {
        owner = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (owner == null) return;

        mob.getLookControl().setLookAt(owner, 10f, mob.getMaxHeadXRot());
        if (--untilRepath > 0) return;

        untilRepath = REPATH_TICKS;
        if (canTeleport && mob.distanceTo(owner) >= TELEPORT_PAST && teleport()) return;
        mob.getNavigation().moveTo(owner, speed);
    }

    /**
     * Puts the entity down beside the owner, `post_teleport_distance` blocks off where it can stand.
     *
     * Mojang writes -1 on two of its pets, which is no distance at all; those land wherever Java's
     * own pet would, within a couple of blocks of the owner.
     */
    private boolean teleport() {
        double reach = postTeleportDistance > 0 ? postTeleportDistance : 2;
        for (int i = 0; i < ATTEMPTS; i++) {
            double angle = mob.getRandom().nextDouble() * Math.PI * 2;
            double x = owner.getX() + Math.cos(angle) * reach;
            double y = owner.getY() + mob.getRandom().nextInt(3) - 1;
            double z = owner.getZ() + Math.sin(angle) * reach;
            if (!mob.level().noCollision(mob, mob.getBoundingBox().move(
                x - mob.getX(), y - mob.getY(), z - mob.getZ()))) {
                continue;
            }

            Platform.placeAt(mob, x, y, z, mob.getYRot(), mob.getXRot());
            mob.getNavigation().stop();
            return true;
        }
        return false;
    }
}
