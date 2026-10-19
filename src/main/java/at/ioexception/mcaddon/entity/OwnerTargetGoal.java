package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Picks a fight on the owner's behalf.
 *
 * Bedrock has four goals for this and they differ only in which side of the owner's fight the pet
 * takes: what the owner attacked, whoever attacked the owner, or whoever the owner is after right
 * now. Defending a trusted owner is the second of those with a radius around the owner and an event
 * when the pet joins in.
 */
public final class OwnerTargetGoal extends Goal {
    private final AddonEntity mob;
    private final boolean avenging;
    private final boolean copying;
    private final boolean defending;
    private final double withinRadius;
    private final boolean mustSee;
    private final String startEvent;
    private final Filter allowed;
    private LivingEntity found;

    private OwnerTargetGoal(AddonEntity mob, JsonObject component, boolean avenging,
        boolean defending) {
        this(mob, component, avenging, defending, false);
    }

    private OwnerTargetGoal(AddonEntity mob, JsonObject component, boolean avenging,
        boolean defending, boolean copying) {
        this.mob = mob;
        this.avenging = avenging;
        this.copying = copying;
        this.defending = defending;
        this.withinRadius = defending ? Goals.number(component, "within_radius", 0) : 0;
        this.mustSee = defending && Goals.flag(component, "must_see", false);
        this.startEvent = defending ? Goals.event(component, "on_defend_start") : null;
        this.allowed = Filter.entityTypes(component.get("entity_types"));
        setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    /** `minecraft:behavior.owner_hurt_target`, which finishes what the owner started. */
    public static OwnerTargetGoal whatTheOwnerHurt(AddonEntity mob, JsonObject component) {
        return new OwnerTargetGoal(mob, component, false, false);
    }

    /** `minecraft:behavior.owner_hurt_by_target`, which answers whoever hit the owner. */
    public static OwnerTargetGoal whoHurtTheOwner(AddonEntity mob, JsonObject component) {
        return new OwnerTargetGoal(mob, component, true, false);
    }

    /** `minecraft:behavior.defend_trusted_target`, the same with a range and an event of its own. */
    public static OwnerTargetGoal defendingTheOwner(AddonEntity mob, JsonObject component) {
        return new OwnerTargetGoal(mob, component, true, true);
    }

    /** `minecraft:behavior.vex_copy_owner_target`, which takes over whoever the owner is after. */
    public static OwnerTargetGoal whatTheOwnerIsAfter(AddonEntity mob, JsonObject component) {
        return new OwnerTargetGoal(mob, component, false, false, true);
    }

    @Override
    public boolean canUse() {
        // Defending reaches whoever `minecraft:trust` has added as well as the one owner, which is
        // what lets a pack's entity take the side of any of several players.
        LivingEntity owner = defending ? mob.guarded(withinRadius) : mob.getOwner();
        if (owner == null) return false;

        found = copying ? whoTheOwnerIsAfter(owner)
            : avenging ? owner.getLastHurtByMob() : owner.getLastHurtMob();
        if (found == null || !found.isAlive() || found == mob || found == owner) return false;
        if (withinRadius > 0 && owner.distanceTo(found) > withinRadius) return false;
        if (mustSee && !mob.getSensing().hasLineOfSight(found)) return false;

        return allowed.matches(new Filter.Context(mob, found, found, ServerHooks.state()));
    }

    @Override
    public void start() {
        mob.setTarget(found);
        if (startEvent != null) {
            EntityEvents.trigger(mob, startEvent, found, ServerHooks.state());
        }
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive();
    }

    /**
     * Whoever the owner is fighting at this moment.
     *
     * A mob owner is asked outright. A player has no target to be asked for, so what they last hit
     * is as close as Java gets to the same question.
     */
    private static LivingEntity whoTheOwnerIsAfter(LivingEntity owner) {
        return owner instanceof net.minecraft.world.entity.Mob fighting ? fighting.getTarget()
            : owner.getLastHurtMob();
    }
}
