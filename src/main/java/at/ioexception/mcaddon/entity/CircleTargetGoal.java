package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Keeps its distance and walks around whatever it is hunting, `minecraft:behavior.move_around_target`.
 *
 * It circles at the range the pack named, which keeps a mob out of reach between its own attacks,
 * the part Java has no goal for.
 */
public final class CircleTargetGoal extends Goal {
    private final PathfinderMob mob;
    private final double speed;
    private final double distance;
    private double angle;

    public CircleTargetGoal(PathfinderMob mob, JsonObject component) {
        this.mob = mob;
        this.speed = number(component, "speed_multiplier", 1);
        this.distance = number(component, "distance_from_target", 4);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() != null && mob.getTarget().isAlive();
    }

    @Override
    public void start() {
        angle = mob.getRandom().nextDouble() * Math.PI * 2;
        stalking(true);
    }

    @Override
    public void stop() {
        stalking(false);
    }

    /** A pack's controller reads the circling back as `q.is_stalking`. */
    private void stalking(boolean around) {
        if (mob instanceof AddonEntity addon) addon.setValue("is_stalking", around);
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;

        angle += 0.08;
        Vec3 around = target.position().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        mob.getNavigation().moveTo(around.x, around.y, around.z, speed);
        mob.getLookControl().setLookAt(target, 30f, 30f);
    }

    private static double number(JsonObject component, String key, double fallback) {
        return component == null || !component.has(key) || !component.get(key).isJsonPrimitive()
            ? fallback : component.get(key).getAsDouble();
    }
}
