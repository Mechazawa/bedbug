package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * `minecraft:behavior.move_to_random_block`, a walk to a block picked at random up to
 * `block_distance` blocks from the entity, over once the entity is within `within_radius` of it.
 *
 * Mojang's pillagers and vindicators pick a block 512 blocks off, past where Java plans a path, so a
 * block further than {@link #LEG} blocks is walked to a leg of that length at a time, the way Java's
 * own patrols cross the same distance.
 */
public final class MoveToRandomBlockGoal extends Goal {
    /** How far one planned leg of a long walk reaches. */
    private static final double LEG = 10;

    /** Ticks between two plans of the next leg, as long as Java's own patrols wait. */
    private static final int REPLAN_TICKS = 20;

    private final PathfinderMob mob;
    private final double speed;
    private final double distance;
    private final double within;
    private Vec3 block;
    private int replanAt;

    public MoveToRandomBlockGoal(PathfinderMob mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.distance = Goals.number(component, "block_distance", 16);
        this.within = Goals.number(component, "within_radius", 0);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        double angle = mob.getRandom().nextDouble() * Math.PI * 2;
        double away = mob.getRandom().nextDouble() * distance;
        block = new Vec3(Math.floor(mob.getX() + Math.cos(angle) * away) + 0.5, mob.getBlockY(),
            Math.floor(mob.getZ() + Math.sin(angle) * away) + 0.5);
        return !arrived();
    }

    @Override
    public boolean canContinueToUse() {
        return !arrived() && !(mob.getNavigation().isDone() && lastLeg());
    }

    @Override
    public void start() {
        walk();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        block = null;
    }

    @Override
    public void tick() {
        if (mob.tickCount >= replanAt && !lastLeg()) walk();
    }

    private void walk() {
        replanAt = mob.tickCount + REPLAN_TICKS;
        Vec3 towards = lastLeg() ? block
            : mob.position().add(block.subtract(mob.position()).multiply(1, 0, 1).normalize().scale(LEG));
        mob.getNavigation().moveTo(towards.x, towards.y, towards.z, speed);
    }

    private boolean lastLeg() {
        return block.distanceTo(mob.position()) <= LEG;
    }

    private boolean arrived() {
        return mob.position().closerThan(block, within);
    }
}
