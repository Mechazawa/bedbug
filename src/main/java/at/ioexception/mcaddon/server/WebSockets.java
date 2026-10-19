package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's half of Bedrock's `/wsserver`, which `/connect` is another name for.
 *
 * Bedrock opens the connection from the game the player is playing, so it reaches what that
 * player's machine reaches and outlives the world it was opened in. The command therefore only
 * tells the player's client where to connect, and the client holds the WebSocket. What the
 * WebSocket server asks of the game comes back as requests: the events it subscribed to, and
 * command lines, which run as the player with the player's own authority. The events are raised
 * here, where the game's own hooks are, and sent to each subscribed player alone.
 *
 * An event body carries the fields community tools record Bedrock sending; a field whose values no
 * such record pins down is left out rather than guessed at.
 */
public final class WebSockets {
    /** What a client is told: where to connect, the answer to a command, and an event. */
    public static final String CONNECT = "wsserver";
    public static final String ANSWER = "wsserver_answer";
    public static final String EVENT = "wsserver_event";

    /** What a client asks: the events its WebSocket server listens for, and a command to run. */
    public static final String SUBSCRIPTIONS = "wsserver_subscriptions";
    public static final String COMMAND = "wsserver_command";

    /** Bedrock's command status codes, as its WebSocket answers carry them. */
    private static final int SUCCESS = 0;
    private static final int FAILED_TO_PARSE_COMMAND = 0x80000000;
    private static final int EXECUTION_FAIL = 0x80020000;

    /** Bedrock's numbers for the three dimensions it has. */
    private static final Map<ResourceKey<Level>, Integer> DIMENSIONS =
        Map.of(Level.OVERWORLD, 0, Level.NETHER, 1, Level.END, 2);

    /** Java's enchantments in the order of Bedrock's enchantment numbers. */
    private static final List<String> ENCHANTMENTS = List.of("protection", "fire_protection",
        "feather_falling", "blast_protection", "projectile_protection", "thorns", "respiration",
        "depth_strider", "aqua_affinity", "sharpness", "smite", "bane_of_arthropods", "knockback",
        "fire_aspect", "looting", "efficiency", "silk_touch", "unbreaking", "fortune", "power", "punch",
        "flame", "infinity", "luck_of_the_sea", "lure", "frost_walker", "mending", "binding_curse",
        "vanishing_curse", "impaling", "riptide", "loyalty", "channeling", "multishot", "piercing",
        "quick_charge", "soul_speed", "swift_sneak", "wind_burst", "density", "breach");

    /** An empty slot, as Bedrock reports one: no name, and room for its largest stack. */
    private static final int EMPTY_SLOT_ROOM = 255;

    private static final Pattern WRITTEN = Pattern.compile("^/?(?:wsserver|connect)\\s+(.+)$");
    private static final SimpleCommandExceptionType TYPED_ONLY = new SimpleCommandExceptionType(Component.literal(
        "This command can only be typed by a player, not run by a function, command block, script or another command"));
    private static final SimpleCommandExceptionType NO_PERMISSION = new SimpleCommandExceptionType(
        Component.literal("You do not have permission to use this command"));

    private static final Map<UUID, Set<String>> SUBSCRIBED = new HashMap<>();

    /** What each player held when a break started, which the tool may not survive. */
    private static final Map<UUID, ItemStack> BREAKING_WITH = new HashMap<>();

    private WebSockets() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> wsserver = dispatcher.register(Commands.literal("wsserver")
            .requires(source -> Platform.allowedAt(source, 2))
            .then(Commands.argument("serverUri", StringArgumentType.greedyString())
                .executes(command -> {
                    throw TYPED_ONLY.create();
                })));
        dispatcher.register(Commands.literal("connect")
            .requires(source -> Platform.allowedAt(source, 2))
            .redirect(wsserver));
    }

    /**
     * Connects the player's client when the player typed the line themselves, as Bedrock's
     * `/wsserver` and `/connect` allow, and refuses it everywhere else.
     *
     * Bedrock keeps a command block and a function from running them and has no selector for them,
     * so only the player who types the command is connected. `typed` says the line came straight
     * from the player's own `/bedrock`: no function, command block, script, event, dialogue,
     * `execute` or line a WebSocket server sent. Any of those reaches the same commands through the
     * Bedrock tree instead, which refuses.
     *
     * @return whether the line was one of these two commands and has been dealt with
     */
    public static boolean connectTyped(CommandSourceStack source, String line, boolean typed)
        throws CommandSyntaxException {
        Matcher written = WRITTEN.matcher(line.strip());
        if (!written.matches()) return false;
        if (!(typed && source.getEntity() instanceof ServerPlayer player)) {
            throw TYPED_ONLY.create();
        }
        if (!Platform.allowedAt(source, 2)) throw NO_PERMISSION.create();

        Channels.sendInstruction(player, CONNECT, written.group(1));
        return true;
    }

    /** A client's request about its websocket connection, which a kind of any other request is not. */
    public static void request(ServerPlayer player, String kind, String body) {
        switch (kind) {
            case SUBSCRIPTIONS -> SUBSCRIBED.put(player.getUUID(), names(body));
            case COMMAND -> answer(player, JsonParser.parseString(body).getAsJsonObject());
            default -> {
            }
        }
    }

    public static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, position, state, blockEntity) -> {
            if (listens(player, "BlockBroken")) BREAKING_WITH.put(player.getUUID(), player.getMainHandItem().copy());
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, position, state, blockEntity) -> {
            ItemStack tool = BREAKING_WITH.remove(player.getUUID());
            if (tool == null || !(player instanceof ServerPlayer breaker)) return;

            JsonObject body = new JsonObject();
            body.add("block", block(state));
            body.addProperty("count", 1);
            body.add("player", player(breaker));
            body.add("tool", item(tool));
            body.addProperty("variant", 0);
            send(breaker, "BlockBroken", body);
        });
    }

    /** Forgets a player's subscriptions as the player leaves, which the client sends again on joining. */
    public static void forget(ServerPlayer player) {
        SUBSCRIBED.remove(player.getUUID());
        BREAKING_WITH.remove(player.getUUID());
    }

    private static Set<String> names(String body) {
        Set<String> names = new HashSet<>();
        JsonParser.parseString(body).getAsJsonArray().forEach(name -> names.add(name.getAsString()));
        return names;
    }

    private static boolean listens(Player player, String event) {
        Set<String> events = SUBSCRIBED.get(player.getUUID());
        return events != null && events.contains(event);
    }

    /** Runs a WebSocket server's command line as the player and tells the client what it said. */
    private static void answer(ServerPlayer player, JsonObject request) {
        String commandLine = request.get("commandLine").getAsString();
        PackCommand line = PackCommand.tryFrom(null,
            commandLine.startsWith("/") ? commandLine.substring(1) : commandLine);
        PackCommand.Said said = line == null ? new PackCommand.Said(false, true, "")
            : line.said(player.createCommandSourceStack());

        JsonObject answer = new JsonObject();
        answer.add("requestId", request.get("requestId"));
        answer.addProperty("statusCode", !said.read() ? FAILED_TO_PARSE_COMMAND
            : said.failed() ? EXECUTION_FAIL : SUCCESS);
        answer.addProperty("statusMessage", said.message());
        Channels.sendInstruction(player, ANSWER, answer.toString());
    }

    /**
     * A chat message reaching this player, raised as Bedrock's `PlayerMessage`.
     *
     * Bedrock's types are `chat`, `say`, `me` and `tell`, which are Java's player chat, `/say`, `/me`
     * and the receiving end of `/tell`; a tell names its receiver and the others name nobody.
     */
    public static void heard(ServerPlayer receiver, ChatType.Bound bound, Component message) {
        if (!listens(receiver, "PlayerMessage")) return;

        ResourceKey<ChatType> chatType = Platform.chatTypeOf(bound, receiver.level().registryAccess());
        String type = ChatType.CHAT.equals(chatType) ? "chat"
            : ChatType.SAY_COMMAND.equals(chatType) ? "say"
            : ChatType.EMOTE_COMMAND.equals(chatType) ? "me"
            : ChatType.MSG_COMMAND_INCOMING.equals(chatType) ? "tell"
            : null;
        if (type == null) return;

        JsonObject body = new JsonObject();
        body.addProperty("message", message.getString());
        body.addProperty("receiver", type.equals("tell") ? receiver.getName().getString() : "");
        body.addProperty("sender", bound.name().getString());
        body.addProperty("type", type);
        send(receiver, "PlayerMessage", body);
    }

    /**
     * A block a player placed from the stack they held, raised as Bedrock's `BlockPlaced`.
     *
     * `placedUnderWater` is read as the player being under water.
     */
    public static void placed(ServerPlayer player, BlockState state, ItemStack held) {
        if (!listens(player, "BlockPlaced")) return;

        JsonObject body = new JsonObject();
        body.add("block", block(state));
        body.addProperty("count", 1);
        body.addProperty("placedUnderWater", player.isUnderWater());
        body.addProperty("placementMethod", 0);
        body.add("player", player(player));
        body.add("tool", item(held));
        send(player, "BlockPlaced", body);
    }

    private static void send(ServerPlayer player, String event, JsonObject body) {
        JsonObject raised = new JsonObject();
        raised.addProperty("eventName", event);
        raised.add("body", body);
        Channels.sendInstruction(player, EVENT, raised.toString());
    }

    /** The data value is 0, which is what every block and item Bedrock has flattened carries. */
    private static JsonObject block(BlockState state) {
        return named(AddonBlocks.identifierOf(state));
    }

    /** An identifier as Bedrock reports one, its name and namespace apart; an empty one names nothing. */
    private static JsonObject named(String identifier) {
        int colon = identifier.indexOf(':');
        JsonObject named = new JsonObject();
        named.addProperty("aux", 0);
        named.addProperty("id", identifier.substring(colon + 1));
        named.addProperty("namespace", colon < 0 && !identifier.isEmpty() ? "minecraft"
            : identifier.substring(0, Math.max(colon, 0)));
        return named;
    }

    /**
     * A stack as Bedrock reports one: its type, how many it holds and could, and its enchantments.
     *
     * An enchantment carries its level and Bedrock's number for it; the name beside them is left out,
     * because no record shows which spelling Bedrock writes there.
     */
    private static JsonObject item(ItemStack stack) {
        if (stack.isEmpty()) {
            JsonObject empty = named("");
            empty.add("enchantments", new JsonArray());
            empty.addProperty("freeStackSize", EMPTY_SLOT_ROOM);
            empty.addProperty("maxStackSize", EMPTY_SLOT_ROOM);
            empty.addProperty("stackSize", 0);
            return empty;
        }

        JsonObject item = named(AddonItems.identifierOf(stack.getItem()));
        JsonArray enchantments = new JsonArray();
        Platform.enchantmentsOf(stack).entrySet().stream()
            .filter(enchantment -> ENCHANTMENTS.contains(enchantment.getKey()))
            .map(enchantment -> {
                JsonObject written = new JsonObject();
                written.addProperty("level", enchantment.getValue());
                written.addProperty("type", ENCHANTMENTS.indexOf(enchantment.getKey()));
                return (JsonElement) written;
            })
            .forEach(enchantments::add);
        item.add("enchantments", enchantments);
        item.addProperty("freeStackSize", stack.getMaxStackSize() - stack.getCount());
        item.addProperty("maxStackSize", stack.getMaxStackSize());
        item.addProperty("stackSize", stack.getCount());
        return item;
    }

    /** A player as Bedrock reports one; a dimension Bedrock has no number for is left out. */
    private static JsonObject player(ServerPlayer player) {
        JsonObject written = new JsonObject();
        Integer dimension = DIMENSIONS.get(player.level().dimension());
        if (dimension != null) written.addProperty("dimension", dimension);
        written.addProperty("id", player.getId());
        written.addProperty("name", player.getName().getString());
        JsonObject position = new JsonObject();
        position.addProperty("x", player.getX());
        position.addProperty("y", player.getY());
        position.addProperty("z", player.getZ());
        written.add("position", position);
        written.addProperty("type", "minecraft:player");
        written.addProperty("variant", 0);
        written.addProperty("yRot", player.getYRot());
        return written;
    }
}
