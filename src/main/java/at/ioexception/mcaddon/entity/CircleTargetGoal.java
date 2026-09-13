package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Keeps its distance and walks around whatever it is hunting.
 *
 * `minecraft:behavior.move_around_target` circles at the range the pack named and
 * `minecraft:behavior.jump_around_target` does the same while hopping. Both keep a mob out of reach
 * between its own attacks, which is the part Java has no goal for.
 */
public final class CircleTargetGoal extends Goal {
    private final PathfinderMob mob;
    private final double speed;
    private final double distance;
    private final boolean jumps;
    private double angle;

    private CircleTargetGoal(PathfinderMob mob, JsonObject component, boolean jumps) {
        this.mob = mob;
        this.speed = number(component, "speed_multiplier", 1);
        this.distance = number(component, "distance_from_target", 4);
        this.jumps = jumps;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** `minecraft:behavior.move_around_target`, which walks the circle. */
    public static CircleTargetGoal walking(PathfinderMob mob, JsonObject component) {
        return new CircleTargetGoal(mob, component, false);
    }

    /** `minecraft:behavior.jump_around_target`, which hops it. */
    public static CircleTargetGoal jumping(PathfinderMob mob, JsonObject component) {
        return new CircleTargetGoal(mob, component, true);
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() != null && mob.getTarget().isAlive();
    }

    @Override
    public void start() {
        angle = mob.getRandom().nextDouble() * Math.PI * 2;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;

        angle += 0.08;
        Vec3 around = target.position().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        mob.getNavigation().moveTo(around.x, around.y, around.z, speed);
        mob.getLookControl().setLookAt(target, 30f, 30f);

        if (jumps && mob.onGround()) {
            mob.setDeltaMovement(mob.getDeltaMovement().add(0, 0.42, 0));
        }
    }

    private static double number(JsonObject component, String key, double fallback) {
        return component == null || !component.has(key) || !component.get(key).isJsonPrimitive()
            ? fallback : component.get(key).getAsDouble();
    }
}
