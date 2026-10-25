package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;

import java.util.function.Predicate;

/**
 * Breaks a door in the way, as long as the pack's `break_time` says and only from the difficulty
 * its `min_difficulty` names.
 *
 * Java's own goal never takes less than 240 ticks, which is Bedrock's default of twelve seconds,
 * so a shorter time is carried out by asking for the time here instead of through the constructor.
 */
public final class DoorBreakingGoal extends BreakDoorGoal {
    private final int breakTicks;

    public DoorBreakingGoal(Mob mob, JsonObject annotation) {
        super(mob, atLeast(Goals.text(annotation, "min_difficulty")));
        this.breakTicks = Goals.ticks(annotation, "break_time", 12);
    }

    @Override
    protected int getDoorBreakTime() {
        return breakTicks;
    }

    /** Bedrock's default is `hard`; a name it does not know is read as that default. */
    private static Predicate<Difficulty> atLeast(String named) {
        Difficulty minimum = switch (named == null ? "hard" : named) {
            case "peaceful" -> Difficulty.PEACEFUL;
            case "easy" -> Difficulty.EASY;
            case "normal" -> Difficulty.NORMAL;
            default -> Difficulty.HARD;
        };
        return difficulty -> difficulty.getId() >= minimum.getId();
    }
}
