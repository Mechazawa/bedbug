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
    private AddonEntity leader;
    private int untilRecheck;

    private FollowKinGoal(AddonEntity mob, JsonObject component, double range, boolean grownOnly) {
        this.mob = mob;
        this.speed = number(component, "speed_multiplier", 1);
        this.range = range;
        this.grownOnly = grownOnly;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** `minecraft:behavior.follow_parent`, which only a young entity acts on and only follows adults. */
    public static FollowKinGoal parent(AddonEntity mob, JsonObject component) {
        return new FollowKinGoal(mob, component, 16, true);
    }

    /**
     * `minecraft:behavior.follow_caravan` and `minecraft:behavior.follow_target_leader`.
     *
     * Both name an entity of the same kind up ahead, and neither cares how old it is.
     */
    public static FollowKinGoal leader(AddonEntity mob, JsonObject component) {
        return new FollowKinGoal(mob, component, 12, false);
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
    public void stop() {
        leader = null;
        mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (leader == null || mob.distanceTo(leader) <= COMFORTABLE) return;

        mob.getNavigation().moveTo(leader, speed);
    }

    /** The closest one of its own kind that is not this entity, and is grown when that is asked. */
    private AddonEntity nearest() {
        List<AddonEntity> kin = mob.level().getEntitiesOfClass(AddonEntity.class,
            mob.getBoundingBox().inflate(range),
            other -> other != mob && other.getType() == mob.getType()
                && (!grownOnly || !other.isBaby()));
        return kin.stream().min(java.util.Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }

    private static double number(JsonObject component, String key, double fallback) {
        return component == null || !component.has(key) || !component.get(key).isJsonPrimitive()
            ? fallback : component.get(key).getAsDouble();
    }
}
