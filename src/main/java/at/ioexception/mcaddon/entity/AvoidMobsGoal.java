package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs away from what frightens the entity, and says so for as long as it is running.
 *
 * `minecraft:behavior.avoid_mob_type` lists what to flee as filters run against the other entity,
 * each with the distance it is noticed from and the speeds to flee at, and Java's own goal does the
 * fleeing. What Java keeps nowhere is the fact of fleeing, which a pack reads back through
 * `is_avoiding_mobs`, so the entity carries it while the goal holds.
 */
public final class AvoidMobsGoal extends AvoidEntityGoal<LivingEntity> {
    /** Bedrock's reach for an entry that states none, and for a goal that states none either. */
    private static final double DEFAULT_REACH = 16;

    private final AddonEntity mob;

    public AvoidMobsGoal(AddonEntity mob, JsonObject goal) {
        this(mob, Entry.all(goal));
    }

    private AvoidMobsGoal(AddonEntity mob, List<Entry> entries) {
        super(mob, LivingEntity.class, (float) entries.stream().mapToDouble(Entry::reach).max().orElse(0),
            entries.isEmpty() ? 1 : entries.get(0).walk(), entries.isEmpty() ? 1 : entries.get(0).sprint(),
            candidate -> entries.stream().anyMatch(entry -> entry.frightens(mob, candidate)));
        this.mob = mob;
    }

    @Override
    public void start() {
        super.start();
        mob.setValue("is_avoiding_mobs", true);
    }

    @Override
    public void stop() {
        super.stop();
        mob.setValue("is_avoiding_mobs", false);
    }

    /** One line of `entity_types`: what is fled, from how far, and how fast. */
    private record Entry(Filter filter, double reach, double walk, double sprint) {
        static List<Entry> all(JsonObject goal) {
            double reach = Goals.number(goal, "max_dist", DEFAULT_REACH);
            double walk = Goals.number(goal, "walk_speed_multiplier", 1);
            double sprint = Goals.number(goal, "sprint_speed_multiplier", 1);

            List<Entry> entries = new ArrayList<>();
            for (JsonElement element : Json.list(goal, "entity_types")) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                entries.add(new Entry(Goals.filter(entry, "filters"),
                    Goals.number(entry, "max_dist", reach),
                    Goals.number(entry, "walk_speed_multiplier", walk),
                    Goals.number(entry, "sprint_speed_multiplier", sprint)));
            }
            return entries;
        }

        boolean frightens(AddonEntity mob, LivingEntity candidate) {
            return mob.distanceTo(candidate) <= reach
                && filter.matches(new Filter.Context(mob, candidate, candidate, ServerHooks.state()));
        }
    }
}
