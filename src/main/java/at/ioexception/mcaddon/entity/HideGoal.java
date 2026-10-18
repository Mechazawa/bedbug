package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Runs to the nearest place of the kind `poi_type` names and stays there, while a bell rings or a
 * raid is on.
 *
 * `minecraft:behavior.hide` is how a Bedrock villager goes home when the alarm is raised, and Java
 * raises the same alarm for its own villager: a bell rung within thirty-two blocks, or a raid where
 * it stands. The entity hides for `duration` seconds once alarmed, and a run that cannot reach its
 * hiding place waits `timeout_cooldown` seconds before it tries again.
 */
public final class HideGoal extends Goal {
    /** How far Java's bell is heard, in blocks. */
    private static final int BELL_HEARD = 32;

    /** How often the entity listens for a bell, in ticks. A rung bell keeps shaking for fifty. */
    private static final int LISTEN_EVERY = 10;

    /** Within a block and a half of the place counts as hidden there. */
    private static final double HIDDEN = 2.25;

    private final AddonEntity mob;
    private final double speed;
    private final int duration;
    private final int cooldown;
    private final Predicate<Holder<PoiType>> wanted;
    private BlockPos hideout;
    private int hiddenUntil;
    private int readyAt;
    private int listenAt;
    private boolean lost;

    public HideGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.speed = Goals.number(goal, "speed_multiplier", 1);
        this.duration = Goals.ticks(goal, "duration", 1);
        this.cooldown = Goals.ticks(goal, "timeout_cooldown", 8);
        this.wanted = MoveToPoiGoal.kind(goal);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (wanted == null || mob.tickCount < Math.max(readyAt, listenAt)) return false;
        if (!(mob.level() instanceof ServerLevel level)) return false;

        listenAt = mob.tickCount + LISTEN_EVERY;
        if (!level.isRaided(mob.blockPosition()) && !bellRinging(level)) return false;

        hideout = level.getPoiManager().findClosest(wanted, mob.blockPosition(), MoveToPoiGoal.SEARCH_RADIUS,
            PoiManager.Occupancy.ANY).orElse(null);
        return hideout != null;
    }

    @Override
    public void start() {
        hiddenUntil = mob.tickCount + duration;
        lost = false;
    }

    @Override
    public boolean canContinueToUse() {
        return !lost && mob.tickCount < hiddenUntil;
    }

    @Override
    public void tick() {
        Vec3 place = Vec3.atBottomCenterOf(hideout);
        if (mob.distanceToSqr(place) <= HIDDEN) {
            mob.getNavigation().stop();
        } else if (mob.getNavigation().isDone()) {
            lost = !mob.getNavigation().moveTo(place.x, place.y, place.z, speed);
        }
    }

    @Override
    public void stop() {
        if (lost) readyAt = mob.tickCount + cooldown;
        mob.getNavigation().stop();
        hideout = null;
    }

    private boolean bellRinging(ServerLevel level) {
        return level.getPoiManager()
            .getInRange(type -> type.is(PoiTypes.MEETING), mob.blockPosition(), BELL_HEARD, PoiManager.Occupancy.ANY)
            .anyMatch(bell -> level.getBlockEntity(bell.getPos()) instanceof BellBlockEntity rung && rung.shaking);
    }
}
