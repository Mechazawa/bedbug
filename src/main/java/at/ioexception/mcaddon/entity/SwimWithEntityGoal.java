package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;

/**
 * Keeps pace with another entity while both of them are in the water.
 *
 * `minecraft:behavior.swim_with_entity` is how Bedrock's dolphin swims beside a player. It starts on
 * a `success_rate` chance, and every `state_check_interval` it decides how to follow from how far off
 * the other is: past `catch_up_threshold` it closes in at `speed_multiplier`, past `stop_distance` it
 * closes in at its own pace, within `match_direction_threshold` it swims the way the other faces at
 * `catch_up_multiplier`, and in between it holds where it is. While the other rides something or is
 * out of the water it gives up on a `chance_to_stop` chance each tick.
 */
final class SwimWithEntityGoal extends Goal {
    /** How far ahead the entity aims when it swims the way the other faces. */
    private static final double MATCH_LEAD = 4;

    private enum Pace { CATCH_UP, CLOSE_IN, HOLD, MATCH }

    private final AddonEntity mob;
    private final double catchUpSpeed;
    private final double matchSpeed;
    private final double searchRange;
    private final double catchUpThreshold;
    private final double stopDistance;
    private final double matchThreshold;
    private final double successRate;
    private final double chanceToStop;
    private final int checkInterval;
    /** Who may be followed, or null for a swimming player, which is who Java's dolphin follows. */
    private final Filter followable;
    private LivingEntity followed;
    private Pace pace;
    private int untilCheck;
    private boolean givenUp;

    SwimWithEntityGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.catchUpSpeed = Goals.number(component, "speed_multiplier", 1.5);
        this.matchSpeed = Goals.number(component, "catch_up_multiplier", 2.5);
        this.searchRange = Goals.number(component, "search_range", 20);
        this.catchUpThreshold = Goals.number(component, "catch_up_threshold", 12);
        this.stopDistance = Goals.number(component, "stop_distance", 5);
        this.matchThreshold = Goals.number(component, "match_direction_threshold", 2);
        this.successRate = Goals.number(component, "success_rate", 0.1);
        this.chanceToStop = Goals.number(component, "chance_to_stop", 0.0333);
        this.checkInterval = Math.max(1, Goals.ticks(component, "state_check_interval", 0.5));
        this.followable = component.has("entity_types") ? Filter.entityTypes(component.get("entity_types")) : null;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mob.isInWater() || mob.getRandom().nextDouble() >= successRate) return false;

        followed = nearest();
        return followed != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !givenUp && followed.isAlive() && mob.isInWater()
            && mob.distanceToSqr(followed) <= searchRange * searchRange;
    }

    @Override
    public void start() {
        pace = Pace.CATCH_UP;
        untilCheck = 0;
        givenUp = false;
    }

    @Override
    public void stop() {
        followed = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!swimming(followed) && mob.getRandom().nextDouble() < chanceToStop) {
            givenUp = true;
            return;
        }
        if (--untilCheck <= 0) {
            untilCheck = checkInterval;
            pace = paceAt(mob.distanceTo(followed));
        }

        mob.getLookControl().setLookAt(followed, 30f, 30f);
        switch (pace) {
            case CATCH_UP -> mob.getNavigation().moveTo(followed, catchUpSpeed);
            case CLOSE_IN -> mob.getNavigation().moveTo(followed, 1);
            case MATCH -> {
                Vec3 facing = followed.getLookAngle().multiply(1, 0, 1).normalize();
                Vec3 ahead = mob.position().add(facing.scale(MATCH_LEAD));
                mob.getNavigation().moveTo(ahead.x, ahead.y, ahead.z, matchSpeed);
            }
            default -> mob.getNavigation().stop();
        }
    }

    private Pace paceAt(double distance) {
        if (distance > catchUpThreshold) return Pace.CATCH_UP;
        if (distance > stopDistance) return Pace.CLOSE_IN;
        return distance <= matchThreshold ? Pace.MATCH : Pace.HOLD;
    }

    /** Swimming is being in the water and riding nothing, which is what Bedrock asks of both entities. */
    private static boolean swimming(LivingEntity entity) {
        return entity.isInWater() && !entity.isPassenger();
    }

    private LivingEntity nearest() {
        return mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(searchRange),
                other -> other != mob && swimming(other) && accepts(other)).stream()
            .min(Comparator.comparingDouble(mob::distanceToSqr))
            .orElse(null);
    }

    private boolean accepts(LivingEntity other) {
        if (followable == null) return other instanceof Player;

        return followable.matches(new Filter.Context(mob, other, other, ServerHooks.state()));
    }
}
