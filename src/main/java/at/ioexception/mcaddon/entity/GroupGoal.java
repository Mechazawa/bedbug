package at.ioexception.mcaddon.entity;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * A goal a component group brings, which runs only while that group is on.
 *
 * Java builds an entity's goals once, and Bedrock switches behaviours on and off with the groups
 * that carry them: a wolf attacks only while its angry group is on and a villager sleeps only while
 * its bed time group is. So each goal a group declares is built up front and kept out of the running
 * whenever its group is not live.
 */
final class GroupGoal extends Goal {
    private final AddonEntity entity;
    private final String group;
    private final Goal goal;

    GroupGoal(AddonEntity entity, String group, Goal goal) {
        this.entity = entity;
        this.group = group;
        this.goal = goal;
        setFlags(goal.getFlags());
    }

    private boolean live() {
        return entity.activeGroups().contains(group);
    }

    @Override
    public boolean canUse() {
        return live() && goal.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return live() && goal.canContinueToUse();
    }

    @Override
    public boolean isInterruptable() {
        return goal.isInterruptable();
    }

    @Override
    public void start() {
        goal.start();
    }

    @Override
    public void stop() {
        goal.stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return goal.requiresUpdateEveryTick();
    }

    @Override
    public void tick() {
        goal.tick();
    }

    @Override
    public String toString() {
        return goal + " while " + group + " is on";
    }
}
