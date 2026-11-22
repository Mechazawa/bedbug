package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.server.EntityEvents;
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
 * each with the distance it is noticed from and the speeds to flee at. Java's own goal finds the
 * spot to run to; the rest of the run is this goal's. What Java keeps nowhere is the fact of
 * fleeing, which a pack reads back through `is_avoiding_mobs`, so the entity carries it while the
 * goal holds.
 *
 * What is fled is decided by those filters alone. Java's own goal picks what to flee the way it
 * picks what to attack, which leaves out a creative player and every player on peaceful; a Bedrock
 * pack says so itself with `is_game_mode` when it means that. Without `ignore_visibility` only what
 * the entity can see is fled.
 *
 * The run lasts until the entity is `max_flee` blocks from what it fled, picking a new spot each time
 * it arrives, and `on_escape_event` is raised once it gets that far. A candidate carrying
 * `minecraft:strength` is fled at a chance of `probability_per_strength` for each point of it, rolled
 * each time the goal looks; one carrying none is always fled.
 */
public final class AvoidMobsGoal extends AvoidEntityGoal<LivingEntity> {
    /** Bedrock's reach for an entry that states none, and for a goal that states none either. */
    private static final double DEFAULT_REACH = 16;

    /** Bedrock's wait between two of the sounds, in seconds, when a pack states none. */
    private static final double DEFAULT_SOUND_LOW = 3;
    private static final double DEFAULT_SOUND_HIGH = 8;

    private final AddonEntity mob;
    private final double walk;
    private final double sprint;
    private final double sprintWithin;
    private final double fleeUntil;
    private final int fleeHorizontal;
    private final int fleeVertical;
    private final boolean throughWalls;
    private final boolean dropsTarget;
    private final double chancePerStrength;
    private final String sound;
    private final Goals.Span soundGap;
    private final JsonObject onEscape;
    private int nextSoundAt;

    public AvoidMobsGoal(AddonEntity mob, JsonObject goal) {
        this(mob, goal, Entry.all(goal));
    }

    private AvoidMobsGoal(AddonEntity mob, JsonObject goal, List<Entry> entries) {
        super(mob, LivingEntity.class, (float) entries.stream().mapToDouble(Entry::reach).max().orElse(0),
            1, 1, candidate -> entries.stream().anyMatch(entry -> entry.frightens(mob, candidate)));
        this.mob = mob;
        this.walk = entries.isEmpty() ? 1 : entries.get(0).walk();
        this.sprint = entries.isEmpty() ? 1 : entries.get(0).sprint();
        this.sprintWithin = Goals.number(goal, "sprint_distance", 7);
        this.fleeUntil = Goals.number(goal, "max_flee", 10);
        this.fleeHorizontal = (int) Goals.number(goal, "avoid_target_xz", 16);
        this.fleeVertical = (int) Goals.number(goal, "avoid_target_y", 7);
        this.throughWalls = Goals.flag(goal, "ignore_visibility", false);
        this.dropsTarget = Goals.flag(goal, "remove_target", false);
        this.chancePerStrength = Goals.number(goal, "probability_per_strength", 1);
        this.sound = Goals.text(goal, "avoid_mob_sound");
        this.soundGap = Goals.span(goal, "sound_interval", DEFAULT_SOUND_LOW, DEFAULT_SOUND_HIGH);
        this.onEscape = Goals.object(goal, "on_escape_event");
    }

    /** The one widening of the search Java's goal makes up and down, from its own `canUse`. */
    private static final double VERTICAL_REACH = 3;

    @Override
    public boolean canUse() {
        toAvoid = mob.level().getEntitiesOfClass(LivingEntity.class,
                mob.getBoundingBox().inflate(maxDist, VERTICAL_REACH, maxDist),
                candidate -> candidate != mob && !candidate.isSpectator() && predicateOnAvoidEntity.test(candidate)
                    && (throughWalls || mob.getSensing().hasLineOfSight(candidate)))
            .stream().min(java.util.Comparator.comparingDouble(mob::distanceToSqr)).orElse(null);
        if (toAvoid == null || !feared(toAvoid)) return false;

        return plan();
    }

    private boolean feared(LivingEntity candidate) {
        Double strength = strengthOf(candidate);
        return strength == null || mob.getRandom().nextDouble() < chancePerStrength * strength;
    }

    private static Double strengthOf(LivingEntity candidate) {
        if (candidate instanceof AddonEntity addon) {
            JsonObject strength = addon.liveComponent("minecraft:strength");
            return strength == null ? null : Goals.number(strength, "value", 1);
        }
        return candidate instanceof net.minecraft.world.entity.animal.equine.Llama llama
            ? (double) llama.getStrength() : null;
    }

    /** A path to somewhere further from what is fled than the entity stands now. */
    private boolean plan() {
        net.minecraft.world.phys.Vec3 away = net.minecraft.world.entity.ai.util.DefaultRandomPos.getPosAway(
            mob, fleeHorizontal, fleeVertical, toAvoid.position());
        if (away == null || toAvoid.distanceToSqr(away) < toAvoid.distanceToSqr(mob)) return false;

        path = pathNav.createPath(away.x, away.y, away.z, 0);
        return path != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!toAvoid.isAlive() || escaped()) return false;
        if (!pathNav.isDone()) return true;

        return plan() && pathNav.moveTo(path, walk);
    }

    private boolean escaped() {
        return mob.distanceTo(toAvoid) >= fleeUntil;
    }

    @Override
    public void start() {
        pathNav.moveTo(path, walk);
        mob.setValue("is_avoiding_mobs", true);
        if (dropsTarget) mob.setTarget(null);
        nextSoundAt = mob.tickCount;
    }

    @Override
    public void tick() {
        pathNav.setSpeedModifier(mob.distanceTo(toAvoid) < sprintWithin ? sprint : walk);
        if (sound == null || mob.tickCount < nextSoundAt) return;

        mob.makeNoise(sound);
        nextSoundAt = mob.tickCount + Math.max(1, soundGap.roll(mob.getRandom()));
    }

    @Override
    public void stop() {
        if (onEscape != null && toAvoid != null && escaped()) escape(toAvoid);
        super.stop();
        mob.setValue("is_avoiding_mobs", false);
    }

    private void escape(LivingEntity fled) {
        String event = Goals.event(onEscape, "event");
        if (event == null || !Goals.filter(onEscape, "filters")
            .matches(new Filter.Context(mob, fled, fled, ServerHooks.state()))) {
            return;
        }
        String target = Json.text(onEscape, "target", "self");
        EntityEvents.trigger("self".equals(target) ? mob : fled, event, "self".equals(target) ? fled : mob,
            ServerHooks.state());
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
