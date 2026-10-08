package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * Wanders to somewhere `xz_dist` blocks across and `y_dist` up or down, on land or through water.
 *
 * Java's stroll and swim goals pick a spot ten across and seven up or down whatever they are told,
 * which is Bedrock's default as well; a pack's own distances are what this goal adds. `interval`
 * is the one in N chance of setting out, which Java counts the same way.
 */
final class StrollGoal extends RandomStrollGoal {
    private final int across;
    private final int upDown;
    private final boolean swims;

    private StrollGoal(PathfinderMob mob, JsonObject goal, boolean swims) {
        super(mob, Goals.number(goal, "speed_multiplier", 1), (int) Goals.number(goal, "interval", 120));
        this.across = Math.max(1, (int) Goals.number(goal, "xz_dist", 10));
        this.upDown = Math.max(1, (int) Goals.number(goal, "y_dist", 7));
        this.swims = swims;
    }

    /** `minecraft:behavior.random_stroll`: somewhere on dry land where there is any. */
    static StrollGoal walking(PathfinderMob mob, JsonObject goal) {
        return new StrollGoal(mob, goal, false);
    }

    /** `minecraft:behavior.random_swim` and `swim_wander`: somewhere in the water. */
    static StrollGoal swimming(PathfinderMob mob, JsonObject goal) {
        return new StrollGoal(mob, goal, true);
    }

    @Override
    protected Vec3 getPosition() {
        if (swims) return BehaviorUtils.getRandomSwimmablePos(mob, across, upDown);

        Vec3 land = LandRandomPos.getPos(mob, across, upDown);
        return land != null ? land : DefaultRandomPos.getPos(mob, across, upDown);
    }
}
