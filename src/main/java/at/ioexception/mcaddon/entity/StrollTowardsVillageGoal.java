package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * `minecraft:behavior.stroll_towards_village`, which takes a Bedrock fox to the nearest village.
 *
 * A village is where Java keeps its beds, job sites and bells, so the walk is to the nearest of
 * them inside `search_range`. Java holds those places for loaded chunks alone, so a range of nothing,
 * Bedrock's "regardless of distance", reaches as far as {@link #UNBOUNDED} blocks round the entity.
 */
public final class StrollTowardsVillageGoal extends Goal {
    private static final int UNBOUNDED = 128;

    private final AddonEntity mob;
    private final double speed;
    private final double arrived;
    private final int range;
    private final int cooldown;
    private final double chance;
    private BlockPos village;
    private int readyAt;

    public StrollTowardsVillageGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.speed = Goals.number(goal, "speed_multiplier", 1);
        double radius = Goals.number(goal, "goal_radius", 1.5);
        this.arrived = radius * radius;
        int searched = (int) Goals.number(goal, "search_range", 0);
        this.range = searched > 0 ? searched : UNBOUNDED;
        this.cooldown = Goals.ticks(goal, "cooldown_time", 8);
        this.chance = Goals.number(goal, "start_chance", 1);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.tickCount < readyAt || mob.getRandom().nextDouble() >= chance) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;

        village = level.getPoiManager().findClosest(type -> type.is(PoiTypeTags.VILLAGE), mob.blockPosition(),
            range, PoiManager.Occupancy.ANY).orElse(null);
        return village != null && !reached();
    }

    @Override
    public void start() {
        Vec3 place = Vec3.atBottomCenterOf(village);
        mob.getNavigation().moveTo(place.x, place.y, place.z, speed);
    }

    @Override
    public boolean canContinueToUse() {
        return !reached() && !mob.getNavigation().isDone();
    }

    @Override
    public void stop() {
        readyAt = mob.tickCount + cooldown;
        mob.getNavigation().stop();
    }

    private boolean reached() {
        return mob.distanceToSqr(Vec3.atBottomCenterOf(village)) <= arrived;
    }
}
