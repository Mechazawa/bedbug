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
 * `minecraft:behavior.avoid_mob_type` lists what to flee as filters run against the other entity.
 * Each entry of `entity_types` is a mob descriptor with its own reach, flee distance, speeds, sight
 * rule, cooldown and outnumbering check; where an entry leaves one out, the same field at the top of
 * the goal stands in, and then the descriptor's own default. Java's own goal finds the spot to run
 * to; the rest of the run is this goal's. What Java keeps nowhere is the fact of fleeing, which a
 * pack reads back through `is_avoiding_mobs`, so the entity carries it while the goal holds.
 *
 * What is fled is decided by those filters alone. Java's own goal picks what to flee the way it
 * picks what to attack, which leaves out a creative player and every player on peaceful; a Bedrock
 * pack says so itself with `is_game_mode` when it means that. An entry's `must_see` decides whether
 * only what the entity can see is fled; an entry stating none flees only what it sees unless the
 * goal states `ignore_visibility`.
 *
 * The run lasts until the entity is the entry's `max_flee` blocks from what it fled, picking a new
 * spot each time it arrives, and `on_escape_event` is raised once it gets that far. A candidate
 * carrying `minecraft:strength` is fled at a chance of `probability_per_strength` for each point of
 * it, rolled each time the goal looks; one carrying none is always fled.
 */
public final class AvoidMobsGoal extends AvoidEntityGoal<LivingEntity> {
    /** A mob descriptor's reach when neither it nor the goal states one. */
    private static final double DEFAULT_REACH = 16;

    /** Bedrock's wait between two of the sounds, in seconds, when a pack states none. */
    private static final double DEFAULT_SOUND_LOW = 3;
    private static final double DEFAULT_SOUND_HIGH = 8;

    /** The one widening of the search Java's goal makes up and down, from its own `canUse`. */
    private static final double VERTICAL_REACH = 3;

    private final AddonEntity mob;
    private final List<Entry> entries;
    private final double sprintWithin;
    private final int fleeHorizontal;
    private final int fleeVertical;
    private final boolean dropsTarget;
    private final double chancePerStrength;
    private final String sound;
    private final Goals.Span soundGap;
    private final JsonObject onEscape;
    private final int[] readyAt;
    private Entry fleeing;
    private int nextSoundAt;
    private int lastSeenAt;

    public AvoidMobsGoal(AddonEntity mob, JsonObject goal) {
        this(mob, goal, Entry.all(goal));
    }

    private AvoidMobsGoal(AddonEntity mob, JsonObject goal, List<Entry> entries) {
        super(mob, LivingEntity.class, (float) entries.stream().mapToDouble(Entry::reach).max().orElse(0),
            1, 1, candidate -> true);
        this.mob = mob;
        this.entries = entries;
        this.readyAt = new int[entries.size()];
        this.sprintWithin = Goals.number(goal, "sprint_distance", 7);
        this.fleeHorizontal = (int) Goals.number(goal, "avoid_target_xz", 16);
        this.fleeVertical = (int) Goals.number(goal, "avoid_target_y", 7);
        this.dropsTarget = Goals.flag(goal, "remove_target", false);
        this.chancePerStrength = Goals.number(goal, "probability_per_strength", 1);
        this.sound = Goals.text(goal, "avoid_mob_sound");
        this.soundGap = Goals.span(goal, "sound_interval", DEFAULT_SOUND_LOW, DEFAULT_SOUND_HIGH);
        this.onEscape = Goals.object(goal, "on_escape_event");
    }

    /**
     * The nearest candidate some entry frightens the entity with, the entry with the lowest
     * `priority` winning between two candidates.
     */
    @Override
    public boolean canUse() {
        double height = Math.max(VERTICAL_REACH, entries.stream().mapToDouble(Entry::height).max().orElse(0));
        record Found(LivingEntity candidate, Entry entry) {
        }
        Found found = mob.level().getEntitiesOfClass(LivingEntity.class,
                mob.getBoundingBox().inflate(maxDist, height, maxDist),
                candidate -> candidate != mob && !candidate.isSpectator())
            .stream()
            .map(candidate -> new Found(candidate, entryFor(candidate)))
            .filter(pair -> pair.entry() != null)
            .min(java.util.Comparator.<Found>comparingInt(pair -> pair.entry().priority())
                .thenComparingDouble(pair -> mob.distanceToSqr(pair.candidate())))
            .orElse(null);
        if (found == null || !feared(found.candidate())) return false;

        toAvoid = found.candidate();
        fleeing = found.entry();
        return plan();
    }

    /** The first entry the candidate answers, or none. */
    private Entry entryFor(LivingEntity candidate) {
        return entries.stream()
            .filter(entry -> mob.tickCount >= readyAt[entry.index()] && entry.frightens(mob, candidate))
            .findFirst().orElse(null);
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

    /**
     * The run holds until the entry's `max_flee` is reached; under `must_see` it also ends once what
     * is fled has been out of sight for `must_see_forget_duration`, and under
     * `reevaluate_description` once it stops answering the entry's filters.
     */
    @Override
    public boolean canContinueToUse() {
        if (!toAvoid.isAlive() || escaped()) return false;
        if (fleeing.forgets()) {
            if (mob.getSensing().hasLineOfSight(toAvoid)) {
                lastSeenAt = mob.tickCount;
            } else if (mob.tickCount - lastSeenAt > fleeing.forgetTicks()) {
                return false;
            }
        }
        if (fleeing.reevaluates() && !fleeing.answers(mob, toAvoid)) return false;
        if (!pathNav.isDone()) return true;

        return plan() && pathNav.moveTo(path, fleeing.walk());
    }

    private boolean escaped() {
        return mob.distanceTo(toAvoid) >= fleeing.fleeUntil();
    }

    @Override
    public void start() {
        pathNav.moveTo(path, fleeing.walk());
        mob.setValue("is_avoiding_mobs", true);
        if (dropsTarget) mob.setTarget(null);
        nextSoundAt = mob.tickCount;
        lastSeenAt = mob.tickCount;
    }

    @Override
    public void tick() {
        pathNav.setSpeedModifier(mob.distanceTo(toAvoid) < sprintWithin ? fleeing.sprint() : fleeing.walk());
        if (sound == null || mob.tickCount < nextSoundAt) return;

        mob.makeNoise(sound);
        nextSoundAt = mob.tickCount + Math.max(1, soundGap.roll(mob.getRandom()));
    }

    /** The entry's `cooldown` keeps it from choosing anything again until it has passed. */
    @Override
    public void stop() {
        if (onEscape != null && toAvoid != null && escaped()) escape(toAvoid);
        if (fleeing != null) readyAt[fleeing.index()] = mob.tickCount + fleeing.cooldownTicks();
        super.stop();
        fleeing = null;
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

    /**
     * One mob descriptor of `entity_types`: what is fled, from how far, how fast, and on what terms.
     *
     * `max_height` below nothing leaves the height unbounded. `priority` orders entries the way a
     * goal priority does, the lowest first.
     */
    private record Entry(int index, Filter filter, double reach, double height, double fleeUntil, double walk,
        double sprint, boolean sight, boolean forgets, int forgetTicks, boolean outnumbered, int cooldownTicks,
        boolean reevaluates, int priority) {
        static List<Entry> all(JsonObject goal) {
            double reach = Goals.number(goal, "max_dist", DEFAULT_REACH);
            double fleeUntil = Goals.number(goal, "max_flee", 10);
            double walk = Goals.number(goal, "walk_speed_multiplier", 1);
            double sprint = Goals.number(goal, "sprint_speed_multiplier", 1);
            boolean throughWalls = Goals.flag(goal, "ignore_visibility", false);

            List<Entry> entries = new ArrayList<>();
            for (JsonElement element : Json.list(goal, "entity_types")) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                boolean mustSee = Goals.flag(entry, "must_see", false);
                entries.add(new Entry(entries.size(), Goals.filter(entry, "filters"),
                    Goals.number(entry, "max_dist", reach),
                    Goals.number(entry, "max_height", -1),
                    Goals.number(entry, "max_flee", fleeUntil),
                    Goals.number(entry, "walk_speed_multiplier", walk),
                    Goals.number(entry, "sprint_speed_multiplier", sprint),
                    entry.has("must_see") ? mustSee : !throughWalls,
                    mustSee,
                    Goals.ticks(entry, "must_see_forget_duration", 3),
                    Goals.flag(entry, "check_if_outnumbered", false),
                    Goals.ticks(entry, "cooldown", 0),
                    Goals.flag(entry, "reevaluate_description", false),
                    (int) Goals.number(entry, "priority", 0)));
            }
            return entries;
        }

        boolean frightens(AddonEntity mob, LivingEntity candidate) {
            return mob.distanceTo(candidate) <= reach
                && (height < 0 || Math.abs(candidate.getY() - mob.getY()) <= height)
                && answers(mob, candidate)
                && (!sight || mob.getSensing().hasLineOfSight(candidate))
                && (!outnumbered || outnumbers(mob));
        }

        boolean answers(AddonEntity mob, LivingEntity candidate) {
            return filter.matches(new Filter.Context(mob, candidate, candidate, ServerHooks.state()));
        }

        /**
         * Whether more entities within reach answer this entry than there are of the entity's own
         * type, itself included, which is how Java's piglins and hoglins weigh each other up.
         */
        private boolean outnumbers(AddonEntity mob) {
            var near = mob.level().getEntitiesOfClass(LivingEntity.class, mob.getBoundingBox().inflate(reach),
                other -> other.isAlive() && mob.distanceTo(other) <= reach);
            long kin = near.stream().filter(other -> other.getType() == mob.getType()).count();
            long others = near.stream().filter(other -> other != mob && answers(mob, other)).count();
            return others > kin;
        }
    }
}
