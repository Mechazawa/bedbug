package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.EntityEvent;
import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.MinecraftServer;
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
    /** An event that triggers another, that triggers the first, would otherwise not return. */
    private static final int MAX_DEPTH = 8;

    private EntityEvents() {
    }

    public static boolean trigger(Entity entity, String name, Entity other, AddonState state) {
        ServerEntityDefinition definition = AddonEntities.definitionOf(entity.getType());
        if (definition == null) return false;

        EntityEvent event = definition.events().get(name);
        if (event == null) {
            AddonEntities.unsupported("event " + name);
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
            case EntityEvent.ChangeGroups groups -> changeGroups(entity, definition, groups, state);
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
            default -> { }
        }
    }

    /**
     * Swaps component groups in and out, then applies what the new set changes.
     *
     * Only the components with a Java meaning are applied; the rest are counted. The active set is
     * still tracked in full, because a pack's filters and its own later events read it.
     */
    private static void changeGroups(Entity entity, ServerEntityDefinition definition,
        EntityEvent.ChangeGroups change, AddonState state) {
        if (!(entity instanceof AddonEntity addon)) return;

        addon.activeGroups().removeAll(change.remove());
        addon.activeGroups().addAll(change.add());

        for (String group : change.add()) {
            JsonObject components = definition.componentGroups().get(group);
            if (components == null) continue;
            for (Map.Entry<String, JsonElement> component : components.entrySet()) {
                applyComponent(entity, component.getKey(), component.getValue(), state);
            }
        }
    }

    private static void applyComponent(Entity entity, String name, JsonElement body, AddonState state) {
        JsonObject values = body.isJsonObject() ? body.getAsJsonObject() : new JsonObject();
        switch (name) {
            case "minecraft:variant", "minecraft:mark_variant", "minecraft:skin_id" -> {
                double value = values.has("value") ? values.get("value").getAsDouble() : 0;
                state.setProperty(entity.getUUID(), name.substring("minecraft:".length()), value);
                EntityPropertySync.broadcast(entity, state);
            }
            case "minecraft:instant_despawn" -> entity.discard();
            case "minecraft:transformation" -> transform(entity, values, state);
            case "minecraft:health" -> {
                if (entity instanceof LivingEntity living && values.has("value")) {
                    living.setHealth(values.get("value").getAsFloat());
                }
            }
            case "minecraft:is_sheared", "minecraft:is_baby", "minecraft:is_dyeable" ->
                state.setProperty(entity.getUUID(), name.substring("minecraft:".length()), true);
            default -> AddonEntities.unsupported(name);
        }
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
        level.addFreshEntity(replacement);

        state.forget(entity.getUUID());
        entity.discard();
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
        if (!(entity.level() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();

        for (String command : commands.commands()) {
            String trimmed = command.startsWith("/") ? command.substring(1) : command;
            try {
                server.getCommands().getDispatcher().execute(trimmed,
                    Platform.commandSourceOf(entity, level).withSuppressedOutput());
            } catch (CommandSyntaxException error) {
                McAddon.LOGGER.debug("An entity event ran a command that did not parse: {}", trimmed);
            }
        }
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
