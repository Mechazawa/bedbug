package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Holds a pet still, either where it was told to sit or beside its sleeping owner.
 *
 * Both of Bedrock's goals end with a pet that has stopped moving, and Java has neither, because its
 * own cat and wolf write sitting into `TamableAnimal` rather than into a goal. The sleeping half also
 * owns `minecraft:on_wake_with_owner`, which is raised on the tick the owner gets up again.
 */
public final class PetRestGoal extends Goal {
    private final AddonEntity mob;
    private final boolean besideOwner;
    private final double speed;
    private final double searchRange;
    private final double searchHeight;
    private final double radius;
    private boolean sleptBeside;

    private PetRestGoal(AddonEntity mob, JsonObject component, boolean besideOwner) {
        this.mob = mob;
        this.besideOwner = besideOwner;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.searchRange = Goals.number(component, "search_range", 4);
        this.searchHeight = Goals.number(component, "search_height", 1);
        this.radius = Goals.number(component, "goal_radius", 0.5);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    /** `minecraft:behavior.pet_sleep_with_owner`, which curls up on the bed beside the owner. */
    public static PetRestGoal besideSleepingOwner(AddonEntity mob, JsonObject component) {
        return new PetRestGoal(mob, component, true);
    }

    /** `minecraft:behavior.stay_while_sitting`, which is a pet that was told to wait here. */
    public static PetRestGoal whileSitting(AddonEntity mob) {
        return new PetRestGoal(mob, new JsonObject(), false);
    }

    @Override
    public boolean canUse() {
        if (!besideOwner) return mob.getPose() == Pose.SITTING;

        LivingEntity owner = mob.getOwner();
        return owner != null && owner.isSleeping()
            && Math.abs(owner.getY() - mob.getY()) <= Math.max(1, searchHeight)
            && mob.distanceTo(owner) <= Math.max(1, searchRange);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
        if (!besideOwner) {
            mob.getNavigation().stop();
            return;
        }

        LivingEntity owner = mob.getOwner();
        if (owner == null) return;

        if (mob.distanceTo(owner) > radius) {
            mob.getNavigation().moveTo(owner, speed);
            return;
        }
        mob.getNavigation().stop();
        mob.setPose(Pose.SLEEPING);
        sleptBeside = true;
    }

    @Override
    public void stop() {
        if (besideOwner) mob.setPose(Pose.STANDING);
        if (!sleptBeside) return;

        sleptBeside = false;
        String event = Goals.event(mob.componentBody("minecraft:on_wake_with_owner"), "event");
        if (event != null) EntityEvents.trigger(mob, event, mob.getOwner(), ServerHooks.state());
    }
}
