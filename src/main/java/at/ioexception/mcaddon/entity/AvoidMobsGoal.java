package at.ioexception.mcaddon.entity;

import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.monster.Monster;

/**
 * Runs away from what frightens the entity, and says so for as long as it is running.
 *
 * `minecraft:behavior.avoid_mob_type` names what to flee, and Java's own goal does the fleeing. What
 * Java keeps nowhere is the fact of fleeing, which a pack reads back through `is_avoiding_mobs`, so
 * the entity carries it while the goal holds.
 */
public final class AvoidMobsGoal extends AvoidEntityGoal<Monster> {
    /** How far a threat is noticed from, and the walking and running speeds away from it. */
    private static final float NOTICED = 8;
    private static final double WALK = 1.0;
    private static final double RUN = 1.2;

    private final AddonEntity mob;

    public AvoidMobsGoal(AddonEntity mob) {
        super(mob, Monster.class, NOTICED, WALK, RUN);
        this.mob = mob;
    }

    @Override
    public void start() {
        super.start();
        mob.setValue("is_avoiding_mobs", true);
    }

    @Override
    public void stop() {
        super.stop();
        mob.setValue("is_avoiding_mobs", false);
    }
}
