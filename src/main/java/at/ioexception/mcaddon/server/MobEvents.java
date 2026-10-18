package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedrock's mob events: a pillager patrol, a wandering trader and the ender dragon, each switched on
 * or off on its own, and `events_enabled`, which switches every one of them off without forgetting
 * what each was set to.
 *
 * Java states the first two as game rules, which hold whether each happens: an event's rule is on
 * while both the event and `events_enabled` are. Java spawns the dragon the first time the End's
 * fight finds none, with no rule, so the fight asks `dragonSpawns` first. What each event was set to
 * is kept beside the world; a world that never set one starts from the rules it has.
 */
public final class MobEvents {
    private static final String SECTION = "mobEvents";
    private static final String ALL = "events_enabled";
    private static final String PATROLS = "minecraft:pillager_patrols_event";
    private static final String TRADER = "minecraft:wandering_trader_event";
    private static final String DRAGON = "minecraft:ender_dragon_event";

    private static final Map<String, Boolean> SET = new LinkedHashMap<>();

    private MobEvents() {
    }

    /** What the world's events were set to, or what its rules say for a world that never set any. */
    public static void load(AddonState state, MinecraftServer server) {
        SET.clear();
        JsonElement saved = state == null ? null : state.section(SECTION);
        if (saved != null && saved.isJsonObject()) {
            saved.getAsJsonObject().entrySet()
                .forEach(entry -> SET.put(entry.getKey(), entry.getValue().getAsBoolean()));
        }
        SET.putIfAbsent(ALL, true);
        SET.putIfAbsent(PATROLS, ruleIsOn(server, Platform.patrolSpawningRule()));
        SET.putIfAbsent(TRADER, ruleIsOn(server, Platform.traderSpawningRule()));
        SET.putIfAbsent(DRAGON, true);
    }

    /** Whether the End's fight may make a dragon where it finds none. */
    public static boolean dragonSpawns() {
        return SET.getOrDefault(ALL, true) && SET.getOrDefault(DRAGON, true);
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> mobevent = Commands.literal("mobevent")
            .requires(source -> Platform.allowedAt(source, 1));
        // Each event is a literal because its Bedrock name carries a colon, which an unquoted string
        // argument stops at.
        for (String event : List.of(ALL, PATROLS, TRADER, DRAGON)) {
            mobevent.then(Commands.literal(event)
                .executes(command -> query(command.getSource(), event))
                .then(Commands.argument("value", BoolArgumentType.bool())
                    .executes(command -> set(command.getSource(), event,
                        BoolArgumentType.getBool(command, "value")))));
        }
        dispatcher.register(mobevent);
    }

    /** Says whether the event is set on, and returns one when it is. */
    private static int query(CommandSourceStack source, String event) {
        boolean on = SET.getOrDefault(event, true);
        source.sendSuccess(() -> Component.literal(event + " is " + (on ? "enabled." : "disabled.")), false);
        return on ? 1 : 0;
    }

    private static int set(CommandSourceStack source, String event, boolean on) {
        SET.put(event, on);
        AddonState state = ServerHooks.state();
        if (state != null) {
            JsonObject saved = new JsonObject();
            SET.forEach(saved::addProperty);
            state.setSection(SECTION, saved);
        }

        MinecraftServer server = source.getServer();
        boolean all = SET.get(ALL);
        if (event.equals(ALL) || event.equals(PATROLS)) {
            setRule(server, Platform.patrolSpawningRule(), all && SET.get(PATROLS));
        }
        if (event.equals(ALL) || event.equals(TRADER)) {
            setRule(server, Platform.traderSpawningRule(), all && SET.get(TRADER));
        }
        source.sendSuccess(() -> Component.literal(event + " is now " + (on ? "enabled." : "disabled.")), true);
        return 1;
    }

    private static void setRule(MinecraftServer server, String rule, boolean on) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
            "gamerule " + rule + " " + on);
    }

    /** A boolean rule's value, which Java's own `gamerule` query returns as its result. */
    private static boolean ruleIsOn(MinecraftServer server, String rule) {
        try {
            return server.getCommands().getDispatcher().execute("gamerule " + rule,
                server.createCommandSourceStack().withSuppressedOutput()) != 0;
        } catch (CommandSyntaxException unknown) {
            return true;
        }
    }
}
