package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * The two things a Bedrock wither does that no other mob does.
 *
 * It shoots at whatever it likes rather than only at what it is fighting, and it settles on whoever
 * has taken the most off it. Java writes both into `WitherBoss`, where the first is a head with a
 * target of its own and the second is a field the damage path writes. A pack's entity has neither,
 * so the loose shot goes through the entity's own `minecraft:shooter` and the worst attacker is read
 * back off the entity, which counts what each one has cost it.
 */
public final class WitherGoal extends Goal {
    /** How far a loose shot looks for something to aim at. */
    private static final double SHOT_RANGE = 20;

    /** Ticks between two loose shots, which is the gap Java leaves between a wither's own. */
    private static final int SHOT_INTERVAL = 40;

    private final AddonEntity mob;
    private final boolean shoots;
    private final Filter allowed;
    private int untilShot;
    private LivingEntity found;

    private WitherGoal(AddonEntity mob, JsonObject component, boolean shoots) {
        this.mob = mob;
        this.shoots = shoots;
        this.allowed = Filter.entityTypes(component.get("entity_types"));
        setFlags(shoots ? EnumSet.of(Goal.Flag.LOOK) : EnumSet.of(Goal.Flag.TARGET));
    }

    /** `minecraft:behavior.wither_random_attack_pos_goal`, which fires at anything else in reach. */
    public static WitherGoal loosingShots(AddonEntity mob, JsonObject component) {
        return new WitherGoal(mob, component, true);
    }

    /** `minecraft:behavior.wither_target_highest_damage`, which answers whoever has hurt it most. */
    public static WitherGoal answeringTheWorstAttacker(AddonEntity mob, JsonObject component) {
        return new WitherGoal(mob, component, false);
    }

    @Override
    public boolean canUse() {
        // Both halves ask a question over a wide box, so neither is asked every tick.
        if (untilShot-- > 0) return false;

        untilShot = SHOT_INTERVAL;
        found = shoots ? somethingElseNearby() : worstAttacker();
        return found != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !shoots && mob.getTarget() != null && mob.getTarget().isAlive();
    }

    @Override
    public void start() {
        if (found == null) return;

        if (!shoots) {
            mob.setTarget(found);
            return;
        }
        mob.getLookControl().setLookAt(found, 30f, 30f);
        mob.performRangedAttack(found, 1f);
    }

    /** Something living in range that the entity is not already fighting. */
    private LivingEntity somethingElseNearby() {
        List<LivingEntity> nearby = mob.level().getEntitiesOfClass(LivingEntity.class,
            mob.getBoundingBox().inflate(SHOT_RANGE),
            other -> other != mob && other != mob.getTarget() && other.isAlive() && matches(other));
        return nearby.isEmpty() ? null : nearby.get(mob.getRandom().nextInt(nearby.size()));
    }

    /** Whoever has taken the most health off this entity and is still alive to answer for it. */
    private LivingEntity worstAttacker() {
        UUID worst = mob.worstAttacker();
        if (worst == null || !(mob.level() instanceof ServerLevel level)) return null;

        return Platform.entityByUuid(level, worst) instanceof LivingEntity attacker
            && attacker.isAlive() && matches(attacker) ? attacker : null;
    }

    private boolean matches(LivingEntity other) {
        return allowed.matches(new Filter.Context(mob, other, other, ServerHooks.state()));
    }
}
