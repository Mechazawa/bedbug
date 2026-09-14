package at.ioexception.mcaddon.entity;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;

/**
 * Keeps the entity from letting itself in while it is indoors.
 *
 * `minecraft:behavior.restrict_open_door` is what stops a Bedrock villager opening the door it has
 * just shut behind it. Java asks the navigator rather than a goal, so the answer is turned off while
 * the entity stands under a roof and put back the moment it steps out.
 */
public final class RestrictOpenDoorGoal extends Goal {
    private final AddonEntity mob;

    public RestrictOpenDoorGoal(AddonEntity mob) {
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        return mob.getNavigation() instanceof GroundPathNavigation
            && !mob.level().canSeeSky(mob.blockPosition());
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        setDoors(false);
    }

    @Override
    public void stop() {
        setDoors(true);
    }

    private void setDoors(boolean allowed) {
        if (mob.getNavigation() instanceof GroundPathNavigation ground) {
            ground.setCanOpenDoors(allowed && mob.declares("minecraft:annotation.open_door"));
        }
    }
}
