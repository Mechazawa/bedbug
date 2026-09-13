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
 * Bedrock states spellcasting twice: `send_event` sends events at a mob, `summon_entity` puts
 * entities in the world. Both are a weighted choice between spells, each holding a sequence whose
 * steps happen at their own delay, so the choosing and the timing are the same goal and only the
 * step differs.
 */
public final class SpellGoal extends Goal {
    private final AddonEntity mob;
    private final boolean summons;
    private final List<Spell> spells = new ArrayList<>();
    private Spell casting;
    private int cast;
    private int untilNext;
    private int done;

    private SpellGoal(AddonEntity mob, JsonObject component, boolean summons, String choicesKey) {
        this.mob = mob;
        this.summons = summons;
        for (JsonElement element : Json.list(component, choicesKey)) {
            if (element.isJsonObject()) spells.add(new Spell(element.getAsJsonObject()));
        }
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** `minecraft:behavior.send_event`, which is a spell whose every step is a pack event. */
    public static SpellGoal sendingEvents(AddonEntity mob, JsonObject component) {
        return new SpellGoal(mob, component, false, "event_choices");
    }

    /** `minecraft:behavior.summon_entity`, which is a spell whose every step is a mob. */
    public static SpellGoal summoning(AddonEntity mob, JsonObject component) {
        return new SpellGoal(mob, component, true, "summon_choices");
    }

    @Override
    public boolean canUse() {
        if (untilNext-- > 0 || spells.isEmpty()) return false;

        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;

        casting = choose(target);
        return casting != null;
    }

    @Override
    public boolean canContinueToUse() {
        return casting != null && done < casting.steps.size();
    }

    @Override
    public void start() {
        cast = 0;
        done = 0;
    }

    @Override
    public void tick() {
        if (casting == null) return;

        cast++;
        LivingEntity target = mob.getTarget();
        if (target != null) mob.getLookControl().setLookAt(target, 30f, 30f);

        while (done < casting.steps.size() && cast >= casting.steps.get(done).delay) {
            apply(casting.steps.get(done), target);
            done++;
        }
    }

    @Override
    public void stop() {
        untilNext = casting == null ? 0 : casting.cooldown;
        casting = null;
    }

    private void apply(Step step, LivingEntity target) {
        if (summons) {
            summon(step);
        } else if (step.event != null) {
            EntityEvents.trigger(mob, step.event, target, ServerHooks.state());
        }
    }

    /** Puts the step's entities in a ring around whichever end of the spell the pack named. */
    private void summon(Step step) {
        if (!(mob.level() instanceof ServerLevel level) || step.entityType == null) return;

        EntityType<?> type = AddonEntities.typeOf(step.entityType);
        if (type == null) return;

        LivingEntity target = mob.getTarget();
        Entity around = "self".equals(step.target) || target == null ? mob : target;
        for (int i = 0; i < step.count; i++) {
            Entity summoned = Platform.createMob(type, level);
            if (summoned == null) return;

            double angle = Math.PI * 2 * i / Math.max(1, step.count);
            Platform.placeAt(summoned, around.getX() + Math.cos(angle) * step.size,
                around.getY(), around.getZ() + Math.sin(angle) * step.size, mob.getYRot(), 0f);
            level.addFreshEntity(summoned);
            if (step.event != null) {
                EntityEvents.trigger(summoned, step.event, mob, ServerHooks.state());
            }
        }
    }

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
        private final Filter conditions;
        private final List<Step> steps = new ArrayList<>();

        private Spell(JsonObject choice) {
            this.weight = Goals.number(choice, "weight", 1);
            this.minRange = Goals.number(choice, "min_activation_range", 0);
            this.maxRange = Goals.number(choice, "max_activation_range", 32);
            this.cooldown = Goals.ticks(choice, "cooldown_time", 0);
            this.conditions = Goals.filter(choice, "filters");
            for (JsonElement element : Json.list(choice, "sequence")) {
                if (element.isJsonObject()) steps.add(new Step(element.getAsJsonObject()));
            }
        }
    }

    /** One step of a spell: when it happens, and what happens. */
    private static final class Step {
        private final int delay;
        private final String event;
        private final String entityType;
        private final String target;
        private final int count;
        private final double size;

        private Step(JsonObject step) {
            this.delay = Goals.ticks(step, "base_delay", 0);
            this.event = Json.text(step, "summon_event", Json.text(step, "event", null));
            this.entityType = Json.text(step, "entity_type", null);
            this.target = Json.text(step, "target", "self");
            this.count = (int) Goals.number(step, "num_entities_spawned", 1);
            this.size = Goals.number(step, "size", 1);
        }
    }
}
