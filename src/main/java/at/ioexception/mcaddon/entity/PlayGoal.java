package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits;
import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * `minecraft:behavior.play`, which has a Bedrock villager child run about with a friend.
 *
 * The goal starts at `chance_to_start` each time it is asked and plays for at most
 * `max_play_duration_seconds`. A friend is the nearest entity inside `friend_search_area` that one of
 * `friend_types` accepts, asked about as `other`; the entity keeps within `follow_distance` of it and
 * runs to random places within `random_pos_search_range` across and `random_pos_search_height` up
 * whenever it is that close. A pack naming no friend types plays alone, and one naming some plays
 * only when a friend is there.
 */
public final class PlayGoal extends Goal {
    /** How often the way to a friend who moved is worked out again, in ticks. */
    private static final int REPATH = 10;

    private final AddonEntity mob;
    private final double speed;
    private final double chance;
    private final int playTime;
    private final double followDistance;
    private final Vec3 searchArea;
    private final List<Filter> friendTypes;
    private final int range;
    private final int height;
    private LivingEntity friend;
    private int playingUntil;

    public PlayGoal(AddonEntity mob, JsonObject goal) {
        this.mob = mob;
        this.speed = Goals.number(goal, "speed_multiplier", 1);
        this.chance = Goals.number(goal, "chance_to_start", 0.0025);
        this.playTime = Goals.ticks(goal, "max_play_duration_seconds", 50);
        this.followDistance = Goals.number(goal, "follow_distance", 2);
        this.searchArea = BedrockUnits.blocks(goal.get("friend_search_area"), new Vec3(6, 3, 6));
        this.friendTypes = Json.list(goal, "friend_types").stream()
            .filter(JsonElement::isJsonObject)
            .map(entry -> Filter.parse(entry.getAsJsonObject().get("filters")))
            .toList();
        this.range = Math.max(1, (int) Goals.number(goal, "random_pos_search_range", 16));
        this.height = Math.max(1, (int) Goals.number(goal, "random_pos_search_height", 3));
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.getRandom().nextDouble() >= chance) return false;

        friend = friendTypes.isEmpty() ? null : mob.level().getEntitiesOfClass(LivingEntity.class,
                mob.getBoundingBox().inflate(searchArea.x, searchArea.y, searchArea.z), this::befriends)
            .stream().min(Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
        return friendTypes.isEmpty() || friend != null;
    }

    @Override
    public void start() {
        playingUntil = mob.tickCount + playTime;
    }

    @Override
    public boolean canContinueToUse() {
        return mob.tickCount < playingUntil && (friend == null || friend.isAlive());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        boolean behind = friend != null && mob.distanceTo(friend) > followDistance;
        if (behind && (mob.getNavigation().isDone() || mob.tickCount % REPATH == 0)) {
            mob.getNavigation().moveTo(friend, speed);
        } else if (!behind && mob.getNavigation().isDone()) {
            Vec3 away = DefaultRandomPos.getPos(mob, range, height);
            if (away != null) mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
        }
    }

    @Override
    public void stop() {
        friend = null;
        mob.getNavigation().stop();
    }

    private boolean befriends(LivingEntity candidate) {
        Filter.Context context = new Filter.Context(mob, candidate, candidate, ServerHooks.state());
        return candidate != mob && friendTypes.stream().anyMatch(filter -> filter.matches(context));
    }
}
