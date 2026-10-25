package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * `minecraft:behavior.sleep`, which has an entity that owns a bed walk to it at night and lie on it.
 *
 * A bed is one of Java's, registered as a home point of interest, which is what a Java villager
 * claims; the entity takes the nearest free one within {@link MoveToPoiGoal#SEARCH_RADIUS} blocks.
 * It walks there at `speed_multiplier` and sleeps through Java's own bed sleeping, which occupies the
 * bed and wakes the entity when the bed goes. An entity with no bed does not sleep, as Bedrock's
 * documentation says. No search, no path or too long a walk is a failed attempt, after which
 * `timeout_cooldown` holds the goal back; Mojang publishes no limit for the walk, so
 * {@link #WALK_TIMEOUT} is decided here. A night slept holds it back for `cooldown_time`. A rider
 * takes part only when `can_sleep_while_riding` says so. The goal holds MOVE and LOOK but not JUMP:
 * Java disables the JUMP flag of a mob in a boat.
 */
public final class SleepGoal extends Goal {
    /** The span of the day Bedrock lets a bed be slept in, in ticks. */
    private static final long BED_OPENS = 12542L;
    private static final long BED_CLOSES = 23460L;

    /** How long the walk to the bed may take, in ticks. */
    static final int WALK_TIMEOUT = 600;

    /** How close to the bed's middle counts as arrived, squared. */
    private static final double ARRIVED = 4;

    private final AddonEntity mob;
    private final boolean sleepsRiding;
    private final double speed;
    private final int cooldown;
    private final int timeoutCooldown;
    private BlockPos bed;
    private int walkEndsAt;
    private boolean slept;
    private long readyAt;

    public SleepGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.sleepsRiding = Goals.flag(component, "can_sleep_while_riding", false);
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.cooldown = Goals.ticks(component, "cooldown_time", 0);
        this.timeoutCooldown = Goals.ticks(component, "timeout_cooldown", 8);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.level().getGameTime() < readyAt || !mayRest()) return false;

        bed = freeBed();
        if (bed == null) readyAt = mob.level().getGameTime() + timeoutCooldown;
        return bed != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!mayRest()) return false;
        if (mob.isSleeping()) return true;

        return mob.tickCount < walkEndsAt && !mob.getNavigation().isDone() || arrived();
    }

    @Override
    public void start() {
        slept = false;
        walkEndsAt = mob.tickCount + WALK_TIMEOUT;
        if (!arrived()) mob.getNavigation().moveTo(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5, speed);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (mob.isSleeping()) {
            mob.restedOn(Platform.dayTime(mob.level()) / 24000L);
        } else if (arrived()) {
            mob.getNavigation().stop();
            mob.startSleeping(bed);
            slept = true;
        }
    }

    @Override
    public void stop() {
        if (mob.isSleeping()) mob.stopSleeping();
        mob.getNavigation().stop();
        readyAt = mob.level().getGameTime() + (slept ? cooldown : timeoutCooldown);
        bed = null;
    }

    private boolean arrived() {
        return mob.distanceToSqr(Vec3.atCenterOf(bed)) <= ARRIVED;
    }

    private boolean mayRest() {
        long time = Platform.dayTime(mob.level()) % 24000L;
        return time >= BED_OPENS && time < BED_CLOSES && (sleepsRiding || !mob.isPassenger());
    }

    private static boolean isFree(ServerLevel level, BlockPos at) {
        var state = level.getBlockState(at);
        return state.getBlock() instanceof BedBlock && !state.getValue(BedBlock.OCCUPIED);
    }

    private BlockPos freeBed() {
        if (!(mob.level() instanceof ServerLevel level)) return null;

        return level.getPoiManager().findAll(type -> type.is(PoiTypes.HOME), at -> isFree(level, at),
                mob.blockPosition(),
                MoveToPoiGoal.SEARCH_RADIUS,
                PoiManager.Occupancy.ANY)
            .min(java.util.Comparator.comparingDouble(at -> at.distSqr(mob.blockPosition())))
            .orElse(null);
    }
}
