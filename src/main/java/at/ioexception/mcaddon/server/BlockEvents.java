package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A behaviour pack's block events, and the components that fire them.
 *
 * A block event is a named list of responses, the same shape an entity event has. An entity event
 * swaps component groups in and out, and a block carries none while the game runs, so a block event
 * works with what is left: the commands it queues, the states it sets on itself, and destroying the
 * block. Every other response Bedrock allows is counted by {@link AddonEntities#unsupported}.
 */
public final class BlockEvents {
    /** The components that name an event directly. */
    private static final List<String> TRIGGERS = List.of(
        "minecraft:on_placed", "minecraft:on_player_placing", "minecraft:on_player_destroyed",
        "minecraft:on_interact", "minecraft:on_step_on", "minecraft:on_step_off",
        "minecraft:on_fall_on");

    /** The components that name an event under `on_tick`, one clock each. */
    private static final List<String> TICKING = List.of(
        "minecraft:tick", "minecraft:queued_ticking", "minecraft:random_ticking");

    /** Where each entity last stood on a block whose pack asked to hear about it leaving. */
    private static final Map<UUID, Standing> STANDING = new HashMap<>();

    private final Map<String, Trigger> triggers;
    private final Map<String, List<Response>> events;
    private final Map<String, Property<?>> properties;
    private final Map<String, List<JsonElement>> values;
    private final Clock clock;

    private BlockEvents(Map<String, Trigger> triggers, Map<String, List<Response>> events,
        Map<String, Property<?>> properties, Map<String, List<JsonElement>> values, Clock clock) {
        this.triggers = triggers;
        this.events = events;
        this.properties = properties;
        this.values = values;
        this.clock = clock;
    }

    /** Null when the block names no event, so a block with none carries nothing. */
    public static BlockEvents of(BlockDefinition definition, Map<String, Property<?>> properties) {
        JsonObject components = definition.components();
        Map<String, Trigger> triggers = new LinkedHashMap<>();

        for (String component : TRIGGERS) {
            Trigger trigger = trigger(component, components.get(component), components);
            if (trigger != null) triggers.put(component, trigger);
        }
        for (String component : TICKING) {
            JsonObject body = object(components.get(component));
            Trigger trigger = body == null ? null : trigger(component, body.get("on_tick"), components);
            if (trigger != null) triggers.put(component, trigger);
            if (body != null && trigger == null) AddonEntities.unsupported(component + " with no on_tick");
        }
        if (triggers.isEmpty()) return null;

        Map<String, List<Response>> events = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : definition.events().entrySet()) {
            if (entry.getValue().isJsonObject()) {
                events.put(entry.getKey(), responses(entry.getValue().getAsJsonObject()));
            }
        }
        return new BlockEvents(triggers, events, properties, definition.states(), clock(components));
    }

    /** Runs what the named component asks for, when the Molang it carries holds. */
    public void fire(String component, ServerLevel level, BlockPos position, Entity actor) {
        Trigger trigger = triggers.get(component);
        if (trigger != null) run(trigger, level, position, actor);
    }

    /** `minecraft:on_fall_on` waits for the drop the pack asked for before it fires. */
    public void fellOn(ServerLevel level, BlockPos position, Entity actor, double distance) {
        Trigger trigger = triggers.get("minecraft:on_fall_on");
        if (trigger != null && distance >= trigger.minFallDistance()) run(trigger, level, position, actor);
    }

    /** Puts the block on the clock a `minecraft:tick` or a `minecraft:queued_ticking` asked for. */
    public void schedule(ServerLevel level, BlockPos position, Block block) {
        if (clock != null) level.scheduleTick(position, block, clock.delay(level.getRandom()));
    }

    /** Runs the scheduled event, and asks for another tick while the pack wants the clock to loop. */
    public void ticked(ServerLevel level, BlockPos position, Block block) {
        if (clock == null) return;

        fire(clock.component(), level, position, null);
        if (clock.looping()) schedule(level, position, block);
    }

    /**
     * Remembers that an entity is standing here, so `minecraft:on_step_off` can fire when it goes.
     *
     * Java calls `stepOn` for every tick an entity spends on the block and has nothing for the tick
     * it leaves, so leaving is the tick on which the block was not stepped on again.
     */
    public void stoodOn(ServerLevel level, BlockPos position, Entity actor) {
        fire("minecraft:on_step_on", level, position, actor);
        if (!triggers.containsKey("minecraft:on_step_off")) return;

        STANDING.put(actor.getUUID(),
            new Standing(this, level.dimension(), position, level.getServer().getTickCount()));
    }

    /** Fires `minecraft:on_step_off` for everyone who was standing on such a block and is not now. */
    public static void endTick(MinecraftServer server) {
        if (STANDING.isEmpty()) return;

        int now = server.getTickCount();
        List<Map.Entry<UUID, Standing>> left = STANDING.entrySet().stream()
            .filter(entry -> entry.getValue().tick() != now)
            .toList();

        left.forEach(entry -> {
            STANDING.remove(entry.getKey());
            Standing standing = entry.getValue();
            ServerLevel level = server.getLevel(standing.dimension());
            if (level != null) {
                standing.events().fire("minecraft:on_step_off", level, standing.position(),
                    level.getEntity(entry.getKey()));
            }
        });
    }

    public static void forget() {
        STANDING.clear();
    }

    private void run(Trigger trigger, ServerLevel level, BlockPos position, Entity actor) {
        BlockState state = level.getBlockState(position);
        if (!trigger.condition().evaluate(environment(state)).truthy()) return;

        for (Response response : events.getOrDefault(trigger.event(), List.of())) {
            switch (response) {
                case RunCommands commands -> runCommands(commands, level, position, actor);
                case SetStates states -> setStates(states, level, position);
                case Die() -> level.destroyBlock(position, true);
            }
        }
    }

    /** Bedrock's commands are Java's apart from the leading slash, the way an entity event runs them. */
    private static void runCommands(RunCommands response, ServerLevel level, BlockPos position,
        Entity actor) {
        MinecraftServer server = level.getServer();
        CommandSourceStack source = response.onActor() && actor != null
            ? Platform.commandSourceOf(actor, level)
            : server.createCommandSourceStack().withLevel(level).withPosition(Vec3.atCenterOf(position));

        for (String command : response.commands()) {
            String trimmed = command.startsWith("/") ? command.substring(1) : command;
            try {
                server.getCommands().getDispatcher().execute(trimmed, source.withSuppressedOutput());
            } catch (CommandSyntaxException error) {
                McAddon.LOGGER.debug("A block event ran a command that did not parse: {}", trimmed);
            }
        }
    }

    /** `set_block_state` writes Molang into the pack's own states, which are Java properties here. */
    private void setStates(SetStates response, ServerLevel level, BlockPos position) {
        BlockState before = level.getBlockState(position);
        MolangEnvironment environment = environment(before);
        BlockState state = before;

        for (Map.Entry<String, MolangExpression> entry : response.values().entrySet()) {
            state = with(state, entry.getKey(), entry.getValue().evaluate(environment));
        }
        if (state != before) level.setBlockAndUpdate(position, state);
    }

    /** The state a pack wrote, as the Java property value standing for it. */
    private BlockState with(BlockState state, String name, MolangValue value) {
        Property<?> property = properties.get(name);
        List<JsonElement> declared = values.get(name);
        if (property == null || declared == null || !state.hasProperty(property)) return state;

        if (property instanceof BooleanProperty flag) return state.setValue(flag, value.truthy());
        if (!(property instanceof IntegerProperty index)) return state;

        for (int at = 0; at < declared.size(); at++) {
            if (value.matches(molang(declared.get(at)))) return state.setValue(index, at);
        }
        return state;
    }

    /**
     * Answers `q.block_state('name')` from the block standing in the world.
     *
     * An event that destroys the block runs while air stands in its place, so every state read here
     * asks the state whether it carries the property before reading it.
     */
    private MolangEnvironment environment(BlockState state) {
        MolangEnvironment environment = new MolangEnvironment();
        environment.setQueries((query, arguments) -> {
            if (!query.equals("block_state") || arguments.isEmpty()) return MolangValue.ZERO;

            String name = arguments.get(0).text();
            Property<?> property = name == null ? null : properties.get(name);
            if (property == null || !state.hasProperty(property)) return MolangValue.ZERO;
            if (property instanceof BooleanProperty flag) return MolangValue.of(state.getValue(flag));
            if (!(property instanceof IntegerProperty index)) return MolangValue.ZERO;

            List<JsonElement> declared = values.getOrDefault(name, List.of());
            int at = state.getValue(index);
            return at < declared.size() ? molang(declared.get(at)) : MolangValue.of(at);
        });
        return environment;
    }

    private static MolangValue molang(JsonElement value) {
        if (!value.isJsonPrimitive()) return MolangValue.ZERO;
        if (value.getAsJsonPrimitive().isBoolean()) return MolangValue.of(value.getAsBoolean());
        if (value.getAsJsonPrimitive().isNumber()) return MolangValue.of(value.getAsDouble());
        return MolangValue.of(value.getAsString());
    }

    /**
     * One trigger component, which names an event and the Molang deciding whether it happens.
     *
     * `minecraft:entity_fall_on` carries the drop `minecraft:on_fall_on` waits for, which is why the
     * whole component set is read here rather than the trigger's own body alone.
     */
    private static Trigger trigger(String component, JsonElement body, JsonObject components) {
        JsonObject values = object(body);
        String event = values == null ? null : Json.text(values, "event", null);
        if (event == null) return null;

        String target = Json.text(values, "target", "self");
        if (!target.equals("self")) AddonEntities.unsupported(component + " target " + target);

        return new Trigger(event, MolangParser.parse(Json.text(values, "condition", "1")),
            Math.max(number(values, "min_fall_distance"),
                number(object(components.get("minecraft:entity_fall_on")), "min_fall_distance")));
    }

    private static Clock clock(JsonObject components) {
        for (String component : List.of("minecraft:tick", "minecraft:queued_ticking")) {
            JsonObject body = object(components.get(component));
            if (body == null || !body.has("on_tick")) continue;

            List<Integer> range = Json.list(body, "interval_range").stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsInt)
                .toList();
            int shortest = range.isEmpty() ? 10 : range.get(0);
            int longest = range.size() < 2 ? shortest : range.get(1);
            JsonElement looping = body.get("looping");
            return new Clock(component, shortest, longest,
                looping == null || !looping.isJsonPrimitive() || looping.getAsBoolean());
        }
        return null;
    }

    private static List<Response> responses(JsonObject body) {
        List<Response> responses = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
            Response response = response(entry.getKey(), object(entry.getValue()));
            if (response == null) {
                AddonEntities.unsupported("block event response " + entry.getKey());
            } else {
                responses.add(response);
            }
        }
        return responses;
    }

    private static Response response(String kind, JsonObject values) {
        JsonObject body = values == null ? new JsonObject() : values;
        return switch (kind) {
            case "run_command" -> new RunCommands(
                Json.list(body, "command").stream().filter(JsonElement::isJsonPrimitive)
                    .map(JsonElement::getAsString).toList(),
                "other".equals(Json.text(body, "target", "self")));
            case "set_block_state" -> new SetStates(states(body));
            case "die" -> new Die();
            default -> null;
        };
    }

    /** A state is written as Molang in a string, and a pack may write the bare value instead. */
    private static Map<String, MolangExpression> states(JsonObject body) {
        Map<String, MolangExpression> parsed = new LinkedHashMap<>();
        body.entrySet().stream()
            .filter(entry -> entry.getValue().isJsonPrimitive())
            .forEach(entry -> parsed.put(entry.getKey(),
                MolangParser.parse(entry.getValue().getAsString())));
        return parsed;
    }

    private static double number(JsonObject values, String key) {
        JsonElement value = values == null ? null : values.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
            ? value.getAsDouble() : 0;
    }

    /** Null when the pack wrote something other than an object, which nothing here can read. */
    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private record Trigger(String event, MolangExpression condition, double minFallDistance) {
    }

    /** The interval a scheduled component asked for, and whether it asks again afterwards. */
    private record Clock(String component, int shortest, int longest, boolean looping) {
        private int delay(RandomSource random) {
            int spread = Math.max(longest - shortest, 0);
            return Math.max(shortest + (spread == 0 ? 0 : random.nextInt(spread + 1)), 1);
        }
    }

    private sealed interface Response {
    }

    private record RunCommands(List<String> commands, boolean onActor) implements Response {
    }

    private record SetStates(Map<String, MolangExpression> values) implements Response {
    }

    private record Die() implements Response {
    }

    private record Standing(BlockEvents events, ResourceKey<Level> dimension, BlockPos position,
        int tick) {
    }
}
