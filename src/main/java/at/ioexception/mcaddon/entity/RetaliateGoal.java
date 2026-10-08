package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.TargetSelection;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;

/**
 * Turns on whatever hurt the entity, among those `minecraft:behavior.hurt_by_target` names.
 *
 * Java's goal answers every attacker. Bedrock's `entity_types` narrows that to the attackers its
 * filters describe, and `hurt_owner` is what allows the owner, or another of the owner's entities,
 * to be answered at all.
 */
final class RetaliateGoal extends HurtByTargetGoal {
    private final AddonEntity mob;
    private final TargetSelection selection;
    private final boolean hurtsOwner;

    RetaliateGoal(AddonEntity mob, JsonObject goal, TargetSelection selection) {
        super(mob);
        this.mob = mob;
        this.selection = selection;
        this.hurtsOwner = Goals.flag(goal, "hurt_owner", false);
        if (Goals.flag(goal, "alert_same_type", false)) setAlertOthers();
    }

    @Override
    public boolean canUse() {
        LivingEntity attacker = mob.getLastHurtByMob();
        return super.canUse() && attacker != null && selection.accepts(mob, attacker)
            && (hurtsOwner || !mob.sides(attacker));
    }

    /**
     * Calls the others of the entity's own kind in, which Java asks of every mob of the same class:
     * every pack's entity is one class here, so that would call in every pack's entity.
     */
    @Override
    protected void alertOthers() {
        LivingEntity attacker = mob.getLastHurtByMob();
        if (attacker == null) return;

        double within = getFollowDistance();
        mob.level().getEntitiesOfClass(AddonEntity.class, mob.getBoundingBox().inflate(within, ALERT_HEIGHT, within),
                other -> other != mob && other.getType() == mob.getType() && other.getTarget() == null
                    && !other.isAlliedTo(attacker))
            .forEach(other -> alertOther(other, attacker));
    }

    /** How far up and down the call carries, which is Java's own. */
    private static final double ALERT_HEIGHT = 10;
}
