package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;

/**
 * Walks after another of its own kind, which is what a calf, a caravan and a shoal all amount to.
 *
 * Java writes each of those into the mob it belongs to: a parent for an animal, a caravan for a
 * llama, a leader for a schooling fish. A pack's entity is none of them, so the shared part is done
 * here and what differs is only which of its own kind counts as worth following.
 */
public final class FollowKinGoal extends Goal {
    /** Close enough that walking any nearer would only push the two apart again. */
    private static final double COMFORTABLE = 3;

    private final AddonEntity mob;
    private final double speed;
    private final double range;
    private final boolean grownOnly;

    /** Whether walking in line here is a caravan, which a pack's `in_caravan` filter asks about. */
    private final boolean caravan;

    /** Whether the one worth following is the raid captain rather than the nearest of its kind. */
    private final boolean captain;
    /** How near the one followed the entity stops, which only a raider's `follow_distance` states. */
    private final double close;
    private AddonEntity leader;
    private int untilRecheck;

    private FollowKinGoal(AddonEntity mob, JsonObject component, double range, boolean grownOnly,
        boolean caravan, boolean captain) {

        this.mob = mob;
        this.speed = number(component, "speed_multiplier", 1);
        this.range = captain ? number(component, "within_radius", range) : range;
        this.grownOnly = grownOnly;
        this.caravan = caravan;
        this.captain = captain;
        this.close = captain ? number(component, "follow_distance", COMFORTABLE) : COMFORTABLE;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** `minecraft:behavior.follow_parent`, which only a young entity acts on and only follows adults. */
    public static FollowKinGoal parent(AddonEntity mob, JsonObject component) {
        return new FollowKinGoal(mob, component, 16, true, false, false);
    }

    /** `minecraft:behavior.follow_target_leader`, a shoal keeping up with the one in front. */
    public static FollowKinGoal leader(AddonEntity mob, JsonObject component) {
        return new FollowKinGoal(mob, component, 12, false, false, false);
    }

    /** `minecraft:behavior.follow_caravan`, the same walk with the line itself a state of the entity. */
    public static FollowKinGoal caravan(AddonEntity mob, JsonObject component) {
        return new FollowKinGoal(mob, component, 12, false, true, false);
    }

    /**
     * `minecraft:behavior.follow_target_captain`, a raider walking behind whoever leads the raid.
     *
     * The captain is the one carrying `minecraft:is_illager_captain`, which the pack switches on
     * through a component group, so what marks it is a value on the entity rather than a type of
     * its own: two raiders of one type and only one of them a captain is the whole point.
     */
    public static FollowKinGoal captain(AddonEntity mob, JsonObject component) {
        return new FollowKinGoal(mob, component, 64, false, false, true);
    }

    @Override
    public boolean canUse() {
        if (grownOnly && !mob.isBaby()) return false;
        if (untilRecheck-- > 0) return false;

        untilRecheck = 20;
        leader = nearest();
        return leader != null;
    }

    @Override
    public boolean canContinueToUse() {
        return leader != null && leader.isAlive() && mob.distanceTo(leader) <= range;
    }

    @Override
    public void start() {
        if (caravan) mob.setValue("in_caravan", true);
    }

    @Override
    public void stop() {
        leader = null;
        mob.getNavigation().stop();
        if (caravan) mob.setValue("in_caravan", false);
    }

    @Override
    public void tick() {
        if (leader == null) return;

        if (mob.distanceTo(leader) <= close) {
            mob.getNavigation().stop();
        } else {
            mob.getNavigation().moveTo(leader, speed);
        }
    }

    /** The closest one of its own kind that is not this entity, and is grown when that is asked. */
    private AddonEntity nearest() {
        List<AddonEntity> kin = mob.level().getEntitiesOfClass(AddonEntity.class,
            mob.getBoundingBox().inflate(range),
            other -> other != mob && worthFollowing(other)
                && (!grownOnly || !other.isBaby()));
        return kin.stream().min(java.util.Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }

    private boolean worthFollowing(AddonEntity other) {
        return captain ? Boolean.TRUE.equals(other.value("is_illager_captain"))
            : other.getType() == mob.getType();
    }

    private static double number(JsonObject component, String key, double fallback) {
        return component == null || !component.has(key) || !component.get(key).isJsonPrimitive()
            ? fallback : component.get(key).getAsDouble();
    }
}
