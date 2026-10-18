package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Stands still and watches a target that is still far away.
 *
 * `minecraft:behavior.hold_ground` is a patrol's: it keeps its place while whatever it is hunting is
 * at least `min_radius` blocks off. Once the target is closer it stops holding, raises
 * `within_radius_event` and, with `broadcast`, hands the target and the event to every entity of its
 * own kind within `broadcast_range`.
 */
public final class HoldGroundGoal extends Goal {
    private final AddonEntity mob;
    private final double minRadius;
    private final boolean broadcast;
    private final double broadcastRange;
    private final String arrivalEvent;
    private LivingEntity answered;

    public HoldGroundGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.minRadius = Goals.number(component, "min_radius", 10);
        this.broadcast = Goals.flag(component, "broadcast", false);
        this.broadcastRange = Goals.number(component, "broadcast_range", 0);
        this.arrivalEvent = Goals.event(component, "within_radius_event");
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            answered = null;
            return false;
        }
        if (mob.distanceTo(target) >= minRadius) return true;

        if (target != answered) arrived(target);
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.distanceTo(target) >= minRadius;
    }

    @Override
    public void tick() {
        mob.getLookControl().setLookAt(mob.getTarget(), 30f, 30f);
        mob.getNavigation().stop();
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
    }

    @Override
    public void stop() {
        LivingEntity target = mob.getTarget();
        if (target != null && target.isAlive()) arrived(target);
    }

    private void arrived(LivingEntity target) {
        answered = target;
        raise(mob);
        if (!broadcast) return;

        mob.level().getEntitiesOfClass(AddonEntity.class, mob.getBoundingBox().inflate(broadcastRange),
                kin -> kin != mob && kin.getType() == mob.getType())
            .forEach(kin -> {
                if (kin.getTarget() == null) kin.setTarget(target);
                raise(kin);
            });
    }

    private void raise(AddonEntity entity) {
        if (arrivalEvent != null) EntityEvents.trigger(entity, arrivalEvent, null, ServerHooks.state());
    }
}
