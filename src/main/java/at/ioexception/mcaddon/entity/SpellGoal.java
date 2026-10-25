package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Casts one of the spells the pack wrote, step by step on the delays it gave.
 *
 * Bedrock states spellcasting twice: `send_event` sends events at the target, `summon_entity` puts
 * entities in the world. Both are a weighted choice between spells, each holding a sequence whose
 * steps happen at their own delay, so the choosing and the timing are the same goal and only the
 * step differs. A spell lasts `cast_duration` seconds, or until its last step is due where it states
 * none, and a step that falls after the end is never cast.
 */
public final class SpellGoal extends Goal {
    private final AddonEntity mob;
    private final boolean summons;
    private final List<Spell> spells = new ArrayList<>();
    private Spell casting;
    private int cast;
    private int readyAt;

    private SpellGoal(AddonEntity mob, List<JsonElement> choices, boolean summons) {
        this.mob = mob;
        this.summons = summons;
        for (JsonElement element : choices) {
            if (element.isJsonObject()) spells.add(new Spell(element.getAsJsonObject()));
        }
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.send_event`, which is a spell whose every step is a pack event. */
    public static SpellGoal sendingEvents(AddonEntity mob, JsonObject component) {
        return new SpellGoal(mob, Json.list(component, "event_choices"), false);
    }

    /** `minecraft:behavior.summon_entity`, which is a spell whose every step is a mob. */
    public static SpellGoal summoning(AddonEntity mob, JsonObject component) {
        return new SpellGoal(mob, Json.list(component, "summon_choices"), true);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (mob.tickCount < readyAt || spells.isEmpty()) return false;

        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;

        casting = choose(target);
        return casting != null;
    }

    @Override
    public boolean canContinueToUse() {
        return casting != null && cast < casting.duration;
    }

    @Override
    public void start() {
        cast = 0;
        mob.setValue("is_casting", true);
        mob.makeNoise(casting.startSound);
    }

    @Override
    public void tick() {
        if (casting == null) return;

        LivingEntity target = mob.getTarget();
        if (target != null) mob.getLookControl().setLookAt(target, 30f, 30f);

        for (Step step : casting.steps) step.run(this, cast, target);
        cast++;
    }

    @Override
    public void stop() {
        mob.setValue("is_casting", false);
        readyAt = mob.tickCount + (casting == null ? 0 : casting.cooldown);
        casting = null;
    }

    /** Puts the step's one entity, number `index` of its `count`, where the step's shape says. */
    private void summon(Step step, int index) {
        if (!(mob.level() instanceof ServerLevel level) || step.entityType == null) return;

        EntityType<?> type = AddonEntities.typeOf(step.entityType);
        if (type == null || capped(level, type, step)) return;

        LivingEntity target = mob.getTarget();
        Entity around = "self".equals(step.target) || target == null ? mob : target;
        Entity summoned = Platform.createMob(type, level);
        if (summoned == null) return;

        double x = around.getX();
        double z = around.getZ();
        if (step.circle) {
            double angle = Math.PI * 2 * index / Math.max(1, step.count);
            x += Math.cos(angle) * step.size;
            z += Math.sin(angle) * step.size;
        } else {
            double facing = target == null ? Math.toRadians(mob.getYRot() + 90)
                : Math.atan2(target.getZ() - mob.getZ(), target.getX() - mob.getX());
            double along = step.size * (index + 1) / Math.max(1, step.count);
            x += Math.cos(facing) * along;
            z += Math.sin(facing) * along;
        }
        Platform.placeAt(summoned, x, around.getY(), z, mob.getYRot(), 0f);
        level.addFreshEntity(summoned);
        if (step.lifespan >= 0) mob.expire(summoned, step.lifespan);
        if (step.event != null) {
            EntityEvents.trigger(summoned, step.event, mob, ServerHooks.state());
        }
    }

    /** Whether `summon_cap` of this type already stand inside `summon_cap_radius` of the caster. */
    private boolean capped(ServerLevel level, EntityType<?> type, Step step) {
        if (step.cap <= 0) return false;

        double radius = step.capRadius > 0 ? step.capRadius : UNBOUNDED_CAP_RADIUS;
        return level.getEntitiesOfClass(Entity.class, mob.getBoundingBox().inflate(radius),
            other -> other.getType() == type && other.isAlive()).size() >= step.cap;
    }

    /** How far a cap with no radius of its own counts, which is as far as a loaded chunk goes. */
    private static final double UNBOUNDED_CAP_RADIUS = 128;

    /** The heaviest spell whose range and filters allow it, weighted so a tie is decided by chance. */
    private Spell choose(LivingEntity target) {
        List<Spell> allowed = spells.stream()
            .filter(spell -> mob.distanceTo(target) >= spell.minRange)
            .filter(spell -> mob.distanceTo(target) <= spell.maxRange)
            .filter(spell -> spell.conditions.matches(
                new Filter.Context(mob, target, target, ServerHooks.state())))
            .toList();
        double total = allowed.stream().mapToDouble(spell -> spell.weight).sum();
        if (total <= 0) return allowed.isEmpty() ? null : allowed.get(0);

        double roll = mob.getRandom().nextDouble() * total;
        for (Spell spell : allowed) {
            roll -= spell.weight;
            if (roll <= 0) return spell;
        }
        return allowed.get(allowed.size() - 1);
    }

    /** One of the spells a pack listed, with everything deciding whether it is the one cast. */
    private static final class Spell {
        private final double weight;
        private final double minRange;
        private final double maxRange;
        private final int cooldown;
        private final int duration;
        private final String startSound;
        private final Filter conditions;
        private final List<Step> steps = new ArrayList<>();

        private Spell(JsonObject choice) {
            this.weight = Goals.number(choice, "weight", 0);
            this.minRange = Goals.number(choice, "min_activation_range", 1);
            this.maxRange = Goals.number(choice, "max_activation_range", 32);
            this.cooldown = Goals.ticks(choice, "cooldown_time", 0);
            this.startSound = Goals.text(choice, "start_sound_event");
            this.conditions = Goals.filter(choice, "filters");
            for (JsonElement element : Json.list(choice, "sequence")) {
                if (element.isJsonObject()) steps.add(new Step(element.getAsJsonObject()));
            }
            int stated = Goals.ticks(choice, "cast_duration", -1);
            this.duration = stated >= 0 ? stated
                : steps.stream().mapToInt(Step::lastTick).max().orElse(0) + 1;
        }
    }

    /** One step of a spell: when it happens, and what happens. */
    private static final class Step {
        private final int delay;
        private final int stagger;
        private final String event;
        private final String sound;
        private final String entityType;
        private final String target;
        private final int count;
        private final double size;
        private final boolean circle;
        private final int lifespan;
        private final int cap;
        private final double capRadius;

        private Step(JsonObject step) {
            this.delay = Goals.ticks(step, "base_delay", 0);
            this.stagger = Goals.ticks(step, "delay_per_summon", 0);
            this.event = Json.text(step, "summon_event", Json.text(step, "event", null));
            this.sound = Goals.text(step, "sound_event");
            this.entityType = Json.text(step, "entity_type", null);
            this.target = Json.text(step, "target", "self");
            this.count = (int) Goals.number(step, "num_entities_spawned", 1);
            this.size = Goals.number(step, "size", 1);
            this.circle = "circle".equals(Json.text(step, "shape", "line"));
            this.lifespan = (int) (Goals.number(step, "entity_lifespan", -1) * Goals.TICKS_PER_SECOND);
            this.cap = (int) Goals.number(step, "summon_cap", 0);
            this.capRadius = Goals.number(step, "summon_cap_radius", 0);
        }

        /** The tick of the spell the step's last entity appears on. */
        private int lastTick() {
            return delay + stagger * Math.max(0, count - 1);
        }

        /** Does whatever this step does on the given tick of the spell, which is only its own ticks. */
        private void run(SpellGoal goal, int tick, LivingEntity victim) {
            if (tick == delay) goal.mob.makeNoise(sound);
            if (!goal.summons) {
                if (tick == delay && event != null && victim != null) {
                    EntityEvents.trigger(victim, event, goal.mob, ServerHooks.state());
                }
                return;
            }

            int into = tick - delay;
            if (into < 0 || (stagger == 0 ? into != 0 : into % stagger != 0)) return;

            int first = stagger == 0 ? 0 : into / stagger;
            int last = stagger == 0 ? count - 1 : first;
            for (int index = first; index <= last && index < count; index++) goal.summon(this, index);
        }
    }
}
