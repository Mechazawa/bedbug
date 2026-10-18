package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Picks a fight with whoever is threatening the place the entity lives.
 *
 * `minecraft:behavior.defend_village_target` is a golem answering an aggressor inside its village.
 * Java writes the village standing itself into its own golem and its raid bookkeeping, neither of
 * which a pack's entity takes part in, so what carries across is the rest: the nearest hostile within
 * range, weighed against the pack's own filter and the chance it gave.
 */
public final class DefendVillageGoal extends Goal {
    /** Ticks between two sweeps of the area around the entity. */
    private static final int SEARCH_INTERVAL = 40;

    private final AddonEntity mob;
    private final double withinRadius;
    private final double chance;
    private final boolean mustSee;
    private final boolean mustReach;
    private final boolean attacksOwner;
    private final Filter allowed;
    private int untilSearch;
    private LivingEntity found;

    public DefendVillageGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.withinRadius = Goals.number(component, "within_radius",
            mob.getAttributeValue(Attributes.FOLLOW_RANGE));
        this.chance = Goals.number(component, "attack_chance", 1);
        this.mustSee = Goals.flag(component, "must_see", false);
        this.mustReach = Goals.flag(component, "must_reach", false);
        this.attacksOwner = Goals.flag(component, "attack_owner", false);
        this.allowed = Filter.entityTypes(component);
        setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        // The sweep reads every living thing inside a village's reach, which no tick should pay for.
        if (untilSearch-- > 0) return false;

        untilSearch = SEARCH_INTERVAL;
        if (mob.getRandom().nextDouble() > chance) return false;

        found = nearestAggressor();
        return found != null;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.distanceTo(target) <= withinRadius;
    }

    @Override
    public void start() {
        mob.setTarget(found);
    }

    private LivingEntity nearestAggressor() {
        List<LivingEntity> nearby = mob.level().getEntitiesOfClass(LivingEntity.class,
            mob.getBoundingBox().inflate(withinRadius), this::threatens);
        return nearby.stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
    }

    /**
     * Whether this is somebody the entity has reason and permission to answer.
     *
     * Java decides a player's case on their standing with the village, which a pack's entity has no
     * part in. Everything hostile counts, and so does whoever last hit the entity, whatever they are.
     */
    private boolean threatens(LivingEntity other) {
        if (other == mob || !other.isAlive()) return false;
        if (!(other instanceof Enemy) && other != mob.getLastHurtByMob()) return false;
        if (!attacksOwner && other.getUUID().equals(mob.ownerUuid())) return false;
        if (mustSee && !mob.getSensing().hasLineOfSight(other)) return false;
        if (mustReach && mob.getNavigation().createPath(other, 0) == null) return false;

        return allowed.matches(new Filter.Context(mob, other, other, ServerHooks.state()));
    }
}
