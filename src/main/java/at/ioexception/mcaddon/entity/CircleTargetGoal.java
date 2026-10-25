package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Keeps within a band of distance from whatever it is hunting, `minecraft:behavior.move_around_target`.
 *
 * Closer than the low end of `destination_position_range`, with the target less than
 * `height_difference_limit` above or below, it walks to a place away from the target found within
 * `horizontal_search_distance` and `vertical_search_distance`. Further than the high end, it walks to
 * a place inside the band behind the way the target faces, within `destination_pos_spread_degrees`
 * of straight behind. Inside the band it stays where it is. Each walk goes at `movement_speed`.
 */
public final class CircleTargetGoal extends Goal {
    private final PathfinderMob mob;
    private final Filter filters;
    private final double speed;
    private final double nearest;
    private final double furthest;
    private final double spread;
    private final double heightLimit;
    private final int searchAcross;
    private final int searchUp;
    private Vec3 destination;

    public CircleTargetGoal(PathfinderMob mob, JsonObject component) {
        this.mob = mob;
        this.filters = Goals.filter(component, "filters");
        this.speed = Goals.number(component, "movement_speed", 0.6);
        this.nearest = Goals.low(component, "destination_position_range", 4);
        this.furthest = Goals.high(component, "destination_position_range", 8);
        this.spread = Goals.number(component, "destination_pos_spread_degrees", 90);
        this.heightLimit = Goals.number(component, "height_difference_limit", 10);
        this.searchAcross = (int) Goals.number(component, "horizontal_search_distance", 5);
        this.searchUp = (int) Goals.number(component, "vertical_search_distance", 5);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()
            || !filters.matches(new Filter.Context(mob, target, target, ServerHooks.state()))) return false;

        destination = destination(target);
        return destination != null;
    }

    private Vec3 destination(LivingEntity target) {
        double distance = mob.distanceTo(target);
        if (distance < nearest && heightLimit > 0 && Math.abs(target.getY() - mob.getY()) < heightLimit) {
            return DefaultRandomPos.getPosAway(mob, searchAcross, searchUp, target.position());
        }
        if (distance <= furthest) return null;

        double turn = Math.toRadians(target.getYHeadRot() + 180 + (mob.getRandom().nextDouble() - 0.5) * spread);
        double away = nearest + mob.getRandom().nextDouble() * (furthest - nearest);
        return target.position().add(-Math.sin(turn) * away, 0, Math.cos(turn) * away);
    }

    @Override
    public boolean canContinueToUse() {
        return mob.getTarget() != null && mob.getTarget().isAlive() && !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(destination.x, destination.y, destination.z, speed);
        stalking(true);
    }

    @Override
    public void stop() {
        stalking(false);
    }

    /** A pack's controller reads the walk back as `q.is_stalking`. */
    private void stalking(boolean around) {
        if (mob instanceof AddonEntity addon) addon.setValue("is_stalking", around);
    }

    @Override
    public void tick() {
        if (mob.getTarget() != null) mob.getLookControl().setLookAt(mob.getTarget(), 30f, 30f);
    }
}
