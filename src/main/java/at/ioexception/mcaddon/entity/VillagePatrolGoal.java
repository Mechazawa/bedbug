package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal;

/**
 * `minecraft:behavior.move_through_village`, Java's walk from one claimed place of a village to the
 * next, which with `only_at_night` rests by day.
 *
 * Java's goal tells night by how bright the sky is, where every other goal here tells it by the
 * clock, so the night is asked here and Java's own question is left off. Java's goal also asks
 * whether the mob can get through a door, which Bedrock leaves to the navigator.
 */
public final class VillagePatrolGoal extends MoveThroughVillageGoal {
    private static final long DAY_TICKS = 24000L;
    private static final long NIGHT_FROM = 12000L;

    /** How far from a claimed place Java's goal looks for the next one, as its golem asks. */
    private static final int DISTANCE_TO_POI = 4;

    private final boolean onlyAtNight;

    public VillagePatrolGoal(PathfinderMob mob, JsonObject component) {
        super(mob, Goals.number(component, "speed_multiplier", 1), false, DISTANCE_TO_POI, () -> false);
        this.onlyAtNight = Goals.flag(component, "only_at_night", false);
    }

    @Override
    public boolean canUse() {
        return (!onlyAtNight || Platform.dayTime(mob.level()) % DAY_TICKS >= NIGHT_FROM) && super.canUse();
    }
}
