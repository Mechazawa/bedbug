package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.level.block.Blocks;

/**
 * `minecraft:behavior.stomp_turtle_egg`, on Java's own goal for the same thing, the one its zombie
 * uses on a turtle egg.
 *
 * Bedrock gives the goal a one in `interval` chance each tick of looking for an egg, where Java waits
 * ten to twenty seconds between looks. Java's goal searches twenty-four blocks across whatever
 * `search_range` says, because its constructor fixes that number, and counts an egg reached from
 * where a mob stands on it whatever `goal_radius` says.
 */
public final class StompEggGoal extends RemoveBlockGoal {
    private final int interval;

    public StompEggGoal(PathfinderMob mob, JsonObject goal) {
        super(Blocks.TURTLE_EGG, mob, Goals.number(goal, "speed_multiplier", 1),
            (int) Goals.number(goal, "search_height", 1));
        this.interval = Math.max(1, (int) Goals.number(goal, "interval", 120));
    }

    /** A one in N chance each tick waits N ticks on average, which is twice that drawn evenly. */
    @Override
    protected int nextStartTick(PathfinderMob mob) {
        return reducedTickDelay(mob.getRandom().nextInt(2 * interval));
    }
}
