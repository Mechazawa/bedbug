package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.DamageCause;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Objects;
import java.util.UUID;

/**
 * A behaviour pack's block events, and the components that fire them.
 *
 * A block event is a named list of responses, the same shape an entity event has. An entity event
 * swaps component groups in and out, and a block carries none while the game runs, so a block event
 * works with what is left: what it does to the world around it, to whoever set it off, and to
 * itself. A response Bedrock allows and this cannot carry out is counted by
 * {@link AddonEntities#unsupported}.
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

    /** The namespace the block's own identifier carries, which is where its loot tables were filed. */
    private final String namespace;

    private BlockEvents(Map<String, Trigger> triggers, Map<String, List<Response>> events,
        Map<String, Property<?>> properties, Map<String, List<JsonElement>> values, Clock clock,
        String namespace) {
        this.triggers = triggers;
        this.events = events;
        this.properties = properties;
        this.namespace = namespace;
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
        int colon = definition.identifier().indexOf(':');
        String namespace = at.ioexception.mcaddon.convert.Names.sanitise(
            colon > 0 ? definition.identifier().substring(0, colon) : "minecraft");
        return new BlockEvents(triggers, events, properties, definition.states(), clock(components),
            namespace);
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
                case PlaySound sound -> playSound(sound, level, position);
                case PlayEffect effect -> playEffect(effect, level, position);
                case Damage hurt -> damage(hurt, level, position, actor);
                case ChangeEffect effect -> changeEffect(effect, actor);
                case SpendHeld spend -> spendHeld(spend, actor);
                case Swing() -> swing(actor);
                case Teleport away -> teleport(away, level, actor);
                case SpawnLoot loot -> spawnLoot(loot, level, position, actor);
                case SetBlock placed -> setBlock(placed, level, position);
            }
        }
    }

    /** The event's commands, written by the add-on whose namespace the block carries. */
    private void runCommands(RunCommands response, ServerLevel level, BlockPos position, Entity actor) {
        CommandSourceStack source = response.onActor() && actor != null
            ? Platform.commandSourceOf(actor, level)
            : level.getServer().createCommandSourceStack().withLevel(level).withPosition(Vec3.atCenterOf(position));

        for (String command : response.commands()) {
            PackCommand line = PackCommand.tryFrom(namespace, command);
            if (line != null) line.run(source, CommandReport.DATA);
        }
    }

    /** `play_sound` names a sound event, which is registered under the same name the pack gave it. */
    private static void playSound(PlaySound response, ServerLevel level, BlockPos position) {
        net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT
            .getOptional(response.sound())
            .ifPresentOrElse(
                event -> level.playSound(null, position, event,
                    net.minecraft.sounds.SoundSource.BLOCKS, 1f, 1f),
                () -> AddonEntities.unsupported("block event play_sound " + response.sound()));
    }

    /**
     * `play_effect` names a particle effect and how many of it to make.
     *
     * A pack's own effect is registered by this mod under the name the pack gave it, and a Bedrock
     * name for one of the game's own is translated the same way a particle command is.
     */
    private static void playEffect(PlayEffect response, ServerLevel level, BlockPos position) {
        net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE
            .getOptional(response.effect())
            .ifPresentOrElse(type -> {
                if (!(type instanceof net.minecraft.core.particles.ParticleOptions options)) return;

                Vec3 at = Vec3.atCenterOf(position);
                level.sendParticles(options, at.x, at.y, at.z, response.count(), 0.25, 0.25, 0.25, 0);
            }, () -> AddonEntities.unsupported("block event play_effect " + response.effect()));
    }

    /** `damage` hurts whoever set the event off, which is the only target a block can reach. */
    private static void damage(Damage response, ServerLevel level, BlockPos position, Entity actor) {
        if (actor == null || response.amount() <= 0) return;

        Platform.hurt(actor, level, response.cause().source(level.registryAccess(), null), (float) response.amount());
    }

    /** `add_mob_effect` and `remove_mob_effect` both act on whoever set the event off. */
    private static void changeEffect(ChangeEffect response, Entity actor) {
        if (!(actor instanceof net.minecraft.world.entity.LivingEntity living)) return;

        if (response.adding()) {
            Platform.addEffect(living, response.effect(),
                (int) (response.seconds() * 20), response.amplifier(), false, true);
        } else {
            Platform.removeEffect(living, response.effect());
        }
    }

    /**
     * `decrement_stack` spends one of what is held, and `transform_item` swaps it for another.
     *
     * Both are how a Bedrock block takes payment for what it just did, and both act on the hand that
     * set the event off rather than on the block.
     */
    private static void spendHeld(SpendHeld response, Entity actor) {
        if (!(actor instanceof net.minecraft.world.entity.player.Player player)) return;

        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return;

        if (response.becomes() == null) {
            if (!player.getAbilities().instabuild) held.shrink(1);
            return;
        }
        net.minecraft.world.item.Item into =
            at.ioexception.mcaddon.registry.AddonItems.named(response.becomes());
        if (into == null) {
            AddonEntities.unsupported("block event transform_item " + response.becomes());
            return;
        }
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new net.minecraft.world.item.ItemStack(into));
    }

    /** `swing` is the arm swing of whoever set the event off. */
    private static void swing(Entity actor) {
        if (actor instanceof net.minecraft.world.entity.LivingEntity living) {
            living.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    /** `teleport` moves whoever set the event off, somewhere inside the range the pack allowed. */
    private static void teleport(Teleport response, ServerLevel level, Entity actor) {
        if (actor == null) return;

        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < TELEPORT_ATTEMPTS; attempt++) {
            double x = actor.getX() + (random.nextDouble() - 0.5) * 2 * response.range();
            double z = actor.getZ() + (random.nextDouble() - 0.5) * 2 * response.range();
            double y = actor.getY() + (random.nextDouble() - 0.5) * 2 * response.range();
            BlockPos landing = BlockPos.containing(x, y, z);

            if (!level.getBlockState(landing).isAir()) continue;
            if (response.avoidWater() && !level.getFluidState(landing).isEmpty()) continue;
            if (response.landOnBlock() && level.getBlockState(landing.below()).isAir()) continue;

            Platform.placeAt(actor, x, y, z, actor.getYRot(), actor.getXRot());
            return;
        }
    }

    /** How many places a teleport tries before giving up and leaving the entity where it was. */
    private static final int TELEPORT_ATTEMPTS = 16;

    /** `spawn_loot` rolls a table and drops what it gives at the block. */
    private void spawnLoot(SpawnLoot response, ServerLevel level, BlockPos position, Entity actor) {
        Entity about = actor == null ? level.getRandomPlayer() : actor;
        if (about == null || level.getServer() == null) return;

        net.minecraft.resources.Identifier table = Platform.id(namespace,
            at.ioexception.mcaddon.bedrock.behaviour.LootTableDefinition.javaPath(response.table()));
        Vec3 at = Vec3.atCenterOf(position);
        for (net.minecraft.world.item.ItemStack stack : Platform.rollLoot(level, table, about)) {
            if (stack.isEmpty()) continue;

            level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(
                level, at.x, at.y, at.z, stack));
        }
    }

    /** `set_block` and `set_block_at_pos` both replace a block with one the pack named. */
    private static void setBlock(SetBlock response, ServerLevel level, BlockPos position) {
        BlockState placed = AddonBlocks.stateOf(response.block(), response.states(), AddonEntities::unsupported);
        if (placed == null) {
            AddonEntities.unsupported("block event set_block " + response.block());
            return;
        }
        level.setBlockAndUpdate(position.offset(response.offset()), placed);
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
     * asks the state whether it carries the property before reading it. Its temporaries are the ones
     * every other expression on the server reads and writes.
     */
    private MolangEnvironment environment(BlockState state) {
        MolangEnvironment environment = new MolangEnvironment();
        if (ServerHooks.state() != null) environment.shareTemporaries(ServerHooks.state().temporaries());
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
            case "play_sound" -> named(body, "sound") == null ? null
                : new PlaySound(named(body, "sound"));
            case "play_effect" -> named(body, "effect") == null ? null
                : new PlayEffect(named(body, "effect"),
                    Math.max(1, (int) number(body, "particle_count")));
            case "damage" -> new Damage(number(body, "amount"), Objects.requireNonNullElse(
                DamageCause.named(Json.text(body, "type", "none")), DamageCause.NONE));
            case "add_mob_effect" -> named(body, "effect") == null ? null
                : new ChangeEffect(named(body, "effect"), true,
                    number(body, "duration"), (int) number(body, "amplifier"));
            case "remove_mob_effect" -> named(body, "effect") == null ? null
                : new ChangeEffect(named(body, "effect"), false, 0, 0);
            case "decrement_stack" -> new SpendHeld(null);
            case "transform_item" -> new SpendHeld(Json.text(body, "transform", null));
            case "swing" -> new Swing();
            case "teleport" -> new Teleport(number(body, "max_range") <= 0 ? 8
                : number(body, "max_range"), flag(body, "avoid_water"), flag(body, "land_on_block"));
            case "spawn_loot" -> Json.text(body, "table", null) == null ? null
                : new SpawnLoot(Json.text(body, "table", null));
            case "set_block" -> SetBlock.of(body, net.minecraft.core.Vec3i.ZERO);
            case "set_block_at_pos" -> SetBlock.of(body, offset(body));
            default -> null;
        };
    }

    /** A flag a pack wrote, which Bedrock defaults to true for both of the ones read here. */
    private static boolean flag(JsonObject body, String key) {
        JsonElement value = body.get(key);
        return value == null || !value.isJsonPrimitive() || value.getAsBoolean();
    }

    /** A Bedrock name, which is as often written without its namespace as with it. */
    private static net.minecraft.resources.Identifier named(JsonObject body, String key) {
        String text = Json.text(body, key, null);
        return text == null ? null
            : net.minecraft.resources.Identifier.tryParse(
                text.contains(":") ? text : "minecraft:" + text);
    }


    /** How far from the block a `set_block_at_pos` reaches, which a pack writes as three numbers. */
    private static net.minecraft.core.Vec3i offset(JsonObject body) {
        List<JsonElement> written = Json.list(body, "block_offset");
        if (written.size() < 3) return net.minecraft.core.Vec3i.ZERO;

        return new net.minecraft.core.Vec3i(written.get(0).getAsInt(),
            written.get(1).getAsInt(), written.get(2).getAsInt());
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

    private record PlaySound(net.minecraft.resources.Identifier sound) implements Response {
    }

    private record PlayEffect(net.minecraft.resources.Identifier effect, int count)
        implements Response {
    }

    private record Damage(double amount, DamageCause cause) implements Response {
    }

    private record ChangeEffect(net.minecraft.resources.Identifier effect, boolean adding,
        double seconds, int amplifier) implements Response {
    }

    /** A null replacement means the held stack is spent rather than swapped. */
    private record SpendHeld(String becomes) implements Response {
    }

    private record Swing() implements Response {
    }

    private record Teleport(double range, boolean avoidWater, boolean landOnBlock)
        implements Response {
    }

    private record SpawnLoot(String table) implements Response {
    }

    /**
     * The block a `set_block` names, which a pack writes bare or as an object carrying its name and
     * states, in Bedrock's spelling of either.
     */
    private record SetBlock(String block, Map<String, String> states, net.minecraft.core.Vec3i offset)
        implements Response {
        static SetBlock of(JsonObject body, net.minecraft.core.Vec3i offset) {
            JsonElement declared = body.get("block_type");
            if (declared != null && declared.isJsonPrimitive()) {
                return new SetBlock(declared.getAsString(), Map.of(), offset);
            }
            if (declared == null || !declared.isJsonObject()) return null;

            JsonObject descriptor = declared.getAsJsonObject();
            String name = Json.text(descriptor, "name", null);
            Map<String, String> states = new java.util.LinkedHashMap<>();
            JsonObject written = descriptor.getAsJsonObject("states");
            if (written != null) {
                written.entrySet().forEach(state -> states.put(state.getKey(), state.getValue().getAsString()));
            }
            return name == null ? null : new SetBlock(name, states, offset);
        }
    }

    private record Standing(BlockEvents events, ResourceKey<Level> dimension, BlockPos position,
        int tick) {
    }
}
