package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Goes to a workstation and spends a while at it.
 *
 * `minecraft:behavior.work` sends a Bedrock villager to the job site it claimed. A pack's entity
 * claims nothing, so the nearest station of a kind a villager could work at is what it walks to.
 * `minecraft:behavior.work_composter` is the same walk to a composter, with the composting itself
 * as what the entity does once it is standing there.
 */
public final class WorkGoal extends Goal {
    /** How far a villager looks for its work, which is a village's own reach. */
    private static final int SEARCH_RADIUS = 48;

    /** How close counts as standing at the station, squared. */
    private static final double ARRIVED = 4;

    /** Ticks between two searches for somewhere to work. */
    private static final int SEARCH_INTERVAL = 100;

    /** How long the walk to the station may take before the entity gives up on it. */
    private static final int TRAVEL_LIMIT = 400;

    private final AddonEntity mob;
    private final boolean composts;
    private final double speed;
    private final int activeTicks;
    private final boolean worksInRain;
    private final String onArrival;
    private final String onUseBlock;
    private final int usesPerVisit;
    private BlockPos station;
    private int worked;
    private int used;
    private int untilSearch;
    private int travelled;

    private WorkGoal(AddonEntity mob, JsonObject component, boolean composts) {
        this.mob = mob;
        this.composts = composts;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.activeTicks = Goals.ticks(component, "active_time", 10);
        this.worksInRain = Goals.flag(component, "can_work_in_rain", true);
        this.onArrival = Goals.event(component, "on_arrival");
        this.onUseBlock = Goals.event(component, "on_use_block");
        this.usesPerVisit = (int) Goals.number(component, "use_block_max", 1);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.work`, which is a shift at whichever station is nearest. */
    public static WorkGoal working(AddonEntity mob, JsonObject component) {
        return new WorkGoal(mob, component, false);
    }

    /** `minecraft:behavior.work_composter`, which is a shift spent filling a composter. */
    public static WorkGoal composting(AddonEntity mob, JsonObject component) {
        return new WorkGoal(mob, component, true);
    }

    @Override
    public boolean canUse() {
        // Searching the places index over a village's reach is not a per-tick question, so the
        // goal asks it every few seconds instead of every time the selector comes round.
        if (untilSearch-- > 0) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;

        untilSearch = SEARCH_INTERVAL;
        if (!worksInRain && level.isRaining()) return false;

        station = nearestStation(level);
        return station != null;
    }

    @Override
    public boolean canContinueToUse() {
        // A station the entity cannot reach would otherwise hold the goal, and with it every other
        // goal that wants to move, for as long as the entity lives.
        return station != null && worked < activeTicks && travelled < TRAVEL_LIMIT;
    }

    @Override
    public void start() {
        worked = 0;
        used = 0;
        travelled = 0;
        mob.getNavigation().moveTo(station.getX() + 0.5, station.getY(), station.getZ() + 0.5, speed);
    }

    @Override
    public void stop() {
        station = null;
    }

    /** The selector ticks a running goal every second tick unless it asks for each one. */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (station == null) return;

        Vec3 at = Vec3.atCenterOf(station);
        mob.getLookControl().setLookAt(at.x, at.y, at.z);
        if (mob.distanceToSqr(at) > ARRIVED) {
            travelled++;
            if (mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(at.x, at.y, at.z, speed);
            }
            return;
        }

        if (worked == 0) fire(onArrival);
        worked++;
        if (composts) compost();
    }

    /** One handful into the composter per second of the shift, up to what the pack allows. */
    private void compost() {
        if (used >= usesPerVisit || worked % Goals.TICKS_PER_SECOND != 0) return;
        if (!(mob.level() instanceof ServerLevel level)) return;

        BlockState state = level.getBlockState(station);
        if (!(state.getBlock() instanceof ComposterBlock)) return;

        used++;
        int filled = state.getValue(ComposterBlock.LEVEL);
        if (filled < ComposterBlock.MAX_LEVEL) {
            level.setBlockAndUpdate(station, state.setValue(ComposterBlock.LEVEL, filled + 1));
        }
        fire(onUseBlock);
    }

    /** The nearest place this entity could work at, which for composting is a composter. */
    private BlockPos nearestStation(ServerLevel level) {
        if (composts) {
            return level.getPoiManager().findClosest(
                type -> type.value().is(Blocks.COMPOSTER.defaultBlockState()),
                mob.blockPosition(), SEARCH_RADIUS, PoiManager.Occupancy.ANY).orElse(null);
        }
        return level.getPoiManager().findClosest(type -> type.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),
            mob.blockPosition(), SEARCH_RADIUS, PoiManager.Occupancy.ANY).orElse(null);
    }

    private void fire(String event) {
        if (event != null) EntityEvents.trigger(mob, event, null, ServerHooks.state());
    }
}
