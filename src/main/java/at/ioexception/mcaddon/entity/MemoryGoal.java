package at.ioexception.mcaddon.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * A goal that walks towards or away from what the entity remembers.
 *
 * `minecraft:memory_behavior.follow_target` and `minecraft:memory_behavior.flee_threat` read the
 * entity written by `minecraft:memory_sensor.in_range_of_entity`, and
 * `minecraft:memory_behavior.move_to_position` reads the place
 * `minecraft:memory_sensor.game_event` heard something at. Each stays on what it picked until the
 * memory goes, which is what Bedrock means by sticking to the position it chose.
 */
public final class MemoryGoal extends Goal {
    private static final double FLEE_RANGE = 12;

    private final AddonEntity mob;
    private final double speed;
    private final boolean fleeing;
    private final boolean followsEntity;
    private Vec3 heading;

    private MemoryGoal(AddonEntity mob, double speed, boolean fleeing, boolean followsEntity) {
        this.mob = mob;
        this.speed = speed;
        this.fleeing = fleeing;
        this.followsEntity = followsEntity;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public static MemoryGoal following(AddonEntity mob) {
        return new MemoryGoal(mob, 1.0, false, true);
    }

    public static MemoryGoal fleeing(AddonEntity mob) {
        return new MemoryGoal(mob, 1.25, true, true);
    }

    public static MemoryGoal movingToPosition(AddonEntity mob) {
        return new MemoryGoal(mob, 1.0, false, false);
    }

    @Override
    public boolean canUse() {
        heading = pick();
        return heading != null;
    }

    @Override
    public boolean canContinueToUse() {
        return heading != null && !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        if (heading != null) mob.getNavigation().moveTo(heading.x, heading.y, heading.z, speed);
    }

    @Override
    public void stop() {
        heading = null;
        mob.getNavigation().stop();
    }

    /**
     * Where the goal is heading, or null while there is nothing remembered to head for.
     *
     * Fleeing asks Java's own random position picker for somewhere away from the threat, because
     * running straight backwards walks a mob into the first wall behind it.
     */
    private Vec3 pick() {
        Memories memories = mob.memories();
        if (memories == null) return null;

        if (!followsEntity) return memories.event(mob.tickCount);

        LivingEntity remembered = memories.nearby(mob.tickCount);
        if (remembered == null) return null;
        if (!fleeing) return remembered.position();

        return mob.distanceToSqr(remembered) > FLEE_RANGE * FLEE_RANGE ? null
            : DefaultRandomPos.getPosAway(mob, (int) FLEE_RANGE, 7, remembered.position());
    }
}
