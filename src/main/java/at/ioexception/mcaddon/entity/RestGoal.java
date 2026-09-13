package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Stops and settles for a while, which is what sitting and lying down both come to.
 *
 * Java gives each of its own mobs its own sitting goal, tied to an owner or to a bed. A pack's
 * entity has neither, so what carries across is the part a player sees: it stays where it is, in
 * the pose the pack asked for, until it gets up again.
 */
public final class RestGoal extends Goal {
    private final Mob mob;
    private final Pose pose;
    private final double chance;
    private int held;

    private RestGoal(Mob mob, JsonObject component, Pose pose) {
        this.mob = mob;
        this.pose = pose;
        this.chance = number(component, "start_chance", 0.02);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    /** `minecraft:behavior.random_sitting`, which sits down every so often for no reason. */
    public static RestGoal sitting(Mob mob, JsonObject component) {
        return new RestGoal(mob, component, Pose.SITTING);
    }

    /** `minecraft:behavior.lay_down`, which a pack uses for something that naps where it stands. */
    public static RestGoal lyingDown(Mob mob, JsonObject component) {
        return new RestGoal(mob, component, Pose.SLEEPING);
    }

    @Override
    public boolean canUse() {
        return mob.getTarget() == null && mob.onGround() && mob.getRandom().nextDouble() < chance;
    }

    @Override
    public boolean canContinueToUse() {
        return held > 0 && mob.getTarget() == null;
    }

    @Override
    public void start() {
        held = 100 + mob.getRandom().nextInt(200);
        mob.getNavigation().stop();
        mob.setPose(pose);
    }

    @Override
    public void tick() {
        held--;
        mob.setDeltaMovement(mob.getDeltaMovement().multiply(0, 1, 0));
    }

    @Override
    public void stop() {
        held = 0;
        mob.setPose(Pose.STANDING);
    }

    private static double number(JsonObject component, String key, double fallback) {
        return component == null || !component.has(key) || !component.get(key).isJsonPrimitive()
            ? fallback : component.get(key).getAsDouble();
    }
}
