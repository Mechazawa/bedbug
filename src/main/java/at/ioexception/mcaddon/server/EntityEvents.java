package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.behaviour.EntityEvent;
import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs a behaviour pack's entity events.
 *
 * An event is the pack's own way of changing an entity, and `queue_command` inside one is how the
 * data half reaches the script half through `/scriptevent`. Running these is what makes a pack's
 * behaviour happen at all rather than only its models being drawn.
 */
public final class EntityEvents {
    /** The events this mod raises on every entity, declared or not, as the game gives it cause. */
    private static final java.util.Set<String> RAISED_ON_EVERY_ENTITY = java.util.Set.of(
        "minecraft:entity_spawned", "minecraft:on_spawn", "minecraft:entity_born",
        "minecraft:entity_transformed", "minecraft:on_hurt", "minecraft:on_death");

    /** An event that triggers another, that triggers the first, would otherwise not return. */
    private static final int MAX_DEPTH = 8;

    private EntityEvents() {
    }

    public static boolean trigger(Entity entity, String name, Entity other, AddonState state) {
        ServerEntityDefinition definition = AddonEntities.definitionOf(entity.getType());
        if (definition == null) return false;

        EntityEvent event = definition.events().get(name);
        if (event == null) {
            // An entity that never declared the event simply has nothing to do when it happens. Only
            // an event a pack asked for by name is a gap; the ones this mod raises on every entity
            // are not, or every entity would report the ones it had no reason to declare.
            if (!RAISED_ON_EVERY_ENTITY.contains(name)) AddonEntities.unsupported("event " + name);
            return false;
        }
        run(entity, definition, event, other, state, 0);
        raiseDataDriven(entity, name);
        return true;
    }

    /**
     * A pack's script watches its own data half through this event.
     *
     * Bedrock raises it whenever a data-driven entity event fires, which is how a script reacts to
     * something the behaviour pack decided without the two halves naming each other. The host holds
     * it until the next tick, because a listener that triggers another event is ordinary.
     */
    private static void raiseDataDriven(Entity entity, String name) {
        if (ServerHooks.scripts() == null) return;

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entity", new at.ioexception.mcaddon.script.ScriptHost.EntityHandle(entity.getStringUUID()));
        fields.put("eventId", name);
        ServerHooks.scripts().queueWorldEvent("dataDrivenEntityTrigger", fields);
    }

    private static void run(Entity entity, ServerEntityDefinition definition, EntityEvent event,
        Entity other, AddonState state, int depth) {
        if (depth > MAX_DEPTH) return;

        Filter.Context context = new Filter.Context(entity, other == null ? entity : other, null, state);
        for (EntityEvent.Action action : event.actions()) {
            if (!action.condition().matches(context)) continue;
            apply(entity, definition, action, other, state, depth);
        }
    }

    private static void apply(Entity entity, ServerEntityDefinition definition, EntityEvent.Action action,
        Entity other, AddonState state, int depth) {
        switch (action) {
            case EntityEvent.ChangeGroups groups -> changeGroups(entity, definition, groups, other, state);
            case EntityEvent.SetProperties properties -> setProperties(entity, properties, state);
            case EntityEvent.RunCommands commands -> runCommands(entity, commands);
            case EntityEvent.TriggerEvent trigger -> trigger(entity, trigger.event(), other, state);
            case EntityEvent.Randomise randomise -> {
                EntityEvent chosen = pick(randomise);
                if (chosen != null) run(entity, definition, chosen, other, state, depth + 1);
            }
            case EntityEvent.Sequence sequence -> {
                for (EntityEvent step : sequence.steps()) {
                    run(entity, definition, step, other, state, depth + 1);
                }
            }
            // The first entry whose own filters hold, and nothing after it. An entry carrying no
            // filters of its own always holds, which is how a pack writes the last resort.
            case EntityEvent.FirstValid first -> {
                Filter.Context context =
                    new Filter.Context(entity, other == null ? entity : other, null, state);
                for (EntityEvent option : first.options()) {
                    if (!holds(option, context)) continue;

                    run(entity, definition, option, other, state, depth + 1);
                    break;
                }
            }
            default -> { }
        }
    }

    /** Whether any of an option's own responses would run, which is what makes it the valid one. */
    private static boolean holds(EntityEvent option, Filter.Context context) {
        return option.actions().stream().anyMatch(action -> action.condition().matches(context));
    }

    /**
     * Swaps component groups in and out, then applies what the new set changes.
     *
     * Only the components with a Java meaning are applied; the rest are counted. The active set is
     * still tracked in full, because a pack's filters and its own later events read it.
     */
    private static void changeGroups(Entity entity, ServerEntityDefinition definition,
        EntityEvent.ChangeGroups change, Entity other, AddonState state) {
        if (!(entity instanceof AddonEntity addon)) return;

        addon.activeGroups().removeAll(change.remove());
        addon.activeGroups().addAll(change.add());
        // `q.had_component_group` is how a pack's render controller branches on a group, so the
        // drawing side is told which are on.
        change.remove().forEach(group -> addon.setValue(AddonEntity.GROUP + group, false));
        change.add().forEach(group -> addon.setValue(AddonEntity.GROUP + group, true));
        // Whether the entity is still its rider's to steer is decided by the set of groups rather
        // than by any one of them, so it is answered once the whole swap is done.
        if (AddonEntities.anyoneDeclares("minecraft:out_of_control")) {
            addon.setValue("out_of_control", addon.declaresNow("minecraft:out_of_control"));
        }

        for (String group : change.add()) {
            JsonObject components = definition.componentGroups().get(group);
            if (components == null) continue;
            for (Map.Entry<String, JsonElement> component : components.entrySet()) {
                applyComponent(entity, component.getKey(), component.getValue(), other, state);
            }
        }
        // A marker is a fact about the whole entity rather than about the group that brought it, and
        // a group leaving takes its markers with it, so they are worked out again over the new set.
        // The age count is built first, because whether the entity is young is one of the flags and
        // because the size the entity is drawn at is read through it.
        addon.refreshAge();
        addon.refreshComponents();
        addon.publishMarkers();
    }

    private static void applyComponent(Entity entity, String name, JsonElement body, Entity other,
        AddonState state) {
        JsonObject values = body.isJsonObject() ? body.getAsJsonObject() : new JsonObject();
        switch (name) {
            case "minecraft:variant", "minecraft:mark_variant", "minecraft:skin_id" -> {
                state.setProperty(entity.getUUID(), name.substring("minecraft:".length()),
                    ServerEntityDefinition.number(values, "value", 0));
                EntityPropertySync.broadcast(entity, state);
            }
            case "minecraft:instant_despawn" -> entity.discard();
            case "minecraft:transformation" -> transform(entity, values, state);
            case "minecraft:health" -> {
                // `max` is the ceiling and `value` what the entity is at, so a group raising the
                // ceiling alone heals the entity to it rather than leaving it where it was.
                if (entity instanceof LivingEntity living && (values.has("value") || values.has("max"))) {
                    double ceiling = ServerEntityDefinition.number(values, "max", living.getMaxHealth());
                    living.setHealth((float) Math.min(ceiling,
                        ServerEntityDefinition.number(values, "value", ceiling)));
                }
            }
            case "minecraft:hide" -> hide(entity);
            case "minecraft:spell_effects" -> cast(entity, values);
            case "minecraft:trust" -> {
                if (entity instanceof AddonEntity addon && other != null) addon.trust(other.getUUID());
            }
            // The markers beside this one are published over the whole live set once the swap is
            // done, because each of them has to go false again when its group leaves.
            case "minecraft:is_baby" ->
                state.setProperty(entity.getUUID(), name.substring("minecraft:".length()), true);
            // A group can hand the entity a different table, so what it drops belongs to the entity
            // rather than to its type.
            case "minecraft:loot" -> {
                String table = at.ioexception.mcaddon.util.Json.text(values, "table", null);
                if (entity instanceof AddonEntity addon && table != null) {
                    addon.setLootTable(
                        at.ioexception.mcaddon.registry.AddonEntities.lootTable(entity.getType(), table));
                }
            }
            // A component this does nothing about at the moment a group brings it in may still be
            // one the entity honours from the moment it was built, so the register is asked rather
            // than told: reporting it here would name a gap that is not there.
            default -> {
                if (!AddonEntities.implemented(name)) AddonEntities.unsupported(name);
            }
        }
    }

    /**
     * `minecraft:spell_effects` is what a group puts on the entity and takes off it again.
     *
     * Bedrock writes the effects to add as a list carrying a duration and a strength each, and the
     * ones to take away as a name or a list of names, which is how a pack turns one omen into
     * another. A group carrying the component with nothing in it clears what an earlier one added.
     */
    private static void cast(Entity entity, JsonObject values) {
        if (!(entity instanceof LivingEntity living)) return;

        for (JsonElement element : at.ioexception.mcaddon.util.Json.list(values, "add_effects")) {
            if (!element.isJsonObject()) continue;

            JsonObject added = element.getAsJsonObject();
            net.minecraft.resources.Identifier id =
                effect(at.ioexception.mcaddon.util.Json.text(added, "effect", null));
            if (id == null) continue;

            Platform.addEffect(living, id, (int) (number(added, "duration", 1) * TICKS_PER_SECOND),
                (int) number(added, "amplifier", 0), true);
        }
        for (JsonElement element : at.ioexception.mcaddon.util.Json.list(values, "remove_effects")) {
            if (!element.isJsonPrimitive()) continue;

            net.minecraft.resources.Identifier id = effect(element.getAsString());
            if (id != null) Platform.removeEffect(living, id);
        }
    }

    /** An effect a spell names, which a pack writes as often without its namespace as with it. */
    private static net.minecraft.resources.Identifier effect(String named) {
        return named == null ? null
            : net.minecraft.resources.Identifier.tryParse(named.contains(":") ? named : "minecraft:" + named);
    }

    private static double number(JsonObject body, String key, double fallback) {
        JsonElement value = body.get(key);
        return value == null || !value.isJsonPrimitive() ? fallback : value.getAsDouble();
    }

    /** Bedrock states a spell's duration in seconds, where an effect is measured in ticks. */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * `minecraft:hide` sends the entity to whatever it calls home and leaves it there.
     *
     * Bedrock brings this in through a component group when a raid starts rather than declaring it
     * on the entity, which is why it acts the moment the group arrives instead of every tick. An
     * entity with nowhere to call home stops what it was doing and stays where it is.
     */
    private static void hide(Entity entity) {
        if (!(entity instanceof AddonEntity addon)) return;

        addon.setTarget(null);
        addon.setValue("is_hiding", true);
        net.minecraft.core.BlockPos home = Platform.home(addon);
        if (home == null) {
            addon.getNavigation().stop();
            return;
        }
        addon.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1);
    }

    /**
     * `minecraft:transformation` replaces the entity with another one.
     *
     * The new entity takes the old one's place and name; nothing else carries across, which is what
     * Bedrock does unless the pack asks for its equipment to be kept.
     */
    private static void transform(Entity entity, JsonObject values, AddonState state) {
        String into = at.ioexception.mcaddon.util.Json.text(values, "into", null);
        if (into == null || !(entity.level() instanceof ServerLevel level)) return;

        net.minecraft.world.entity.EntityType<?> type = AddonEntities.typeOf(into.split("<")[0]);
        if (type == null) {
            AddonEntities.unsupported("transformation into " + into);
            return;
        }

        Entity replacement = Platform.createMob(type, level);
        if (replacement == null) return;

        Platform.placeAt(replacement, entity.getX(), entity.getY(), entity.getZ(),
            entity.getYRot(), entity.getXRot());
        if (entity.getCustomName() != null) replacement.setCustomName(entity.getCustomName());
        carryOver(entity, replacement, values);
        level.addFreshEntity(replacement);

        state.forget(entity.getUUID());
        entity.discard();
    }

    /**
     * What the new entity keeps of the old one.
     *
     * Bedrock drops the equipment and the inventory on the ground unless the pack asks otherwise,
     * and `preserve_equipment` is the pack asking for it to be worn by what it became instead.
     */
    private static void carryOver(Entity before, Entity after, JsonObject values) {
        if (!(before instanceof LivingEntity old) || !(after instanceof LivingEntity fresh)) return;

        boolean kept = ServerEntityDefinition.flag(values, "preserve_equipment", false);
        boolean dropped = ServerEntityDefinition.flag(values, "drop_equipment", false);
        if (!kept && !dropped) return;

        for (net.minecraft.world.entity.EquipmentSlot slot
            : net.minecraft.world.entity.EquipmentSlot.values()) {
            net.minecraft.world.item.ItemStack worn = old.getItemBySlot(slot);
            if (worn.isEmpty()) continue;

            if (kept) fresh.setItemSlot(slot, worn.copy());
            if (dropped) {
                old.level().addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                    old.level(), old.getX(), old.getY(), old.getZ(), worn.copy()));
            }
        }
    }

    private static void setProperties(Entity entity, EntityEvent.SetProperties properties, AddonState state) {
        for (Map.Entry<String, JsonElement> value : properties.values().entrySet()) {
            state.setProperty(entity.getUUID(), value.getKey(), plain(value.getValue()));
        }
        EntityPropertySync.broadcast(entity, state);
    }

    /**
     * Bedrock's commands are Java's in the cases a pack's events use, apart from the leading slash.
     *
     * `/scriptevent` is registered by this mod, so the whole round trip from an entity event to a
     * pack's script works through here.
     */
    private static void runCommands(Entity entity, EntityEvent.RunCommands commands) {
        commands.commands().forEach(command -> PackCommand.of(command).runAs(entity));
    }

    private static EntityEvent pick(EntityEvent.Randomise randomise) {
        double total = randomise.options().stream().mapToDouble(EntityEvent.Randomise.Weighted::weight).sum();
        if (total <= 0) return null;

        double roll = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * total;
        for (EntityEvent.Randomise.Weighted option : randomise.options()) {
            roll -= option.weight();
            if (roll <= 0) return option.outcome();
        }
        return randomise.options().get(randomise.options().size() - 1).outcome();
    }

    private static Object plain(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        if (value.getAsJsonPrimitive().isNumber()) return value.getAsDouble();
        return value.getAsString();
    }
}
