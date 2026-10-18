package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.script.ScriptHost;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bedrock's game rules Java keeps no rule of its own for, kept beside the world and acted on here.
 *
 * Two are the server's. `recipesUnlock` off stops a recipe being unlocked as a player collects what
 * it takes, which Java does by an advancement per recipe whose reward is the recipe, so a reward
 * stops handing recipes out while the rule is off; `/recipe` still gives them. `respawnBlocksExplode`
 * off leaves a bed or a respawn anchor used where it cannot hold a spawn standing, where Java would
 * blow it up.
 *
 * Four are a client's, sent to every player: coordinates and the day count drawn at the top left of
 * the screen, a toast for each recipe unlocked, and the lines naming what an item may break or be
 * placed on.
 */
public final class BedrockRules {
    public static final String KIND = "rules";

    public static final String RECIPES_UNLOCK = "recipesUnlock";
    public static final String RESPAWN_BLOCKS_EXPLODE = "respawnBlocksExplode";
    public static final String SHOW_COORDINATES = "showCoordinates";
    public static final String SHOW_DAYS_PLAYED = "showDaysPlayed";
    public static final String SHOW_RECIPE_MESSAGES = "showRecipeMessages";
    public static final String SHOW_TAGS = "showTags";

    private static final String SECTION = "bedrockRules";

    /**
     * Each rule and what a world starts with. A world shows no coordinates and no day count until a
     * pack asks, which keeps a Java world looking the way it does without one.
     */
    private static final Map<String, Boolean> DEFAULTS = Map.of(
        RECIPES_UNLOCK, true, RESPAWN_BLOCKS_EXPLODE, true, SHOW_COORDINATES, false,
        SHOW_DAYS_PLAYED, false, SHOW_RECIPE_MESSAGES, true, SHOW_TAGS, true);

    private static final Map<String, Boolean> SET = new LinkedHashMap<>();

    private BedrockRules() {
    }

    public static void load(AddonState state) {
        SET.clear();
        JsonElement saved = state == null ? null : state.section(SECTION);
        if (saved != null && saved.isJsonObject()) {
            saved.getAsJsonObject().entrySet().stream()
                .filter(entry -> DEFAULTS.containsKey(entry.getKey()))
                .forEach(entry -> SET.put(entry.getKey(), entry.getValue().getAsBoolean()));
        }
    }

    public static boolean on(String rule) {
        return SET.getOrDefault(rule, DEFAULTS.getOrDefault(rule, false));
    }

    /** The rule's value as text, or null for a name this does not hold, whatever case it is written in. */
    public static String value(String written) {
        String rule = named(written);
        return rule == null ? null : String.valueOf(on(rule));
    }

    /** Sets a rule by a name in any case, answering false for a name this does not hold. */
    public static boolean set(MinecraftServer server, String written, String value) {
        String rule = named(written);
        if (rule == null) return false;
        set(server, rule, Boolean.parseBoolean(value));
        return true;
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> gamerule = Commands.literal("gamerule")
            .requires(source -> Platform.allowedAt(source, 1));
        for (String rule : DEFAULTS.keySet()) {
            gamerule.then(Commands.literal(rule)
                .executes(command -> {
                    boolean on = on(rule);
                    command.getSource().sendSuccess(() -> Component.literal(rule + " = " + on), false);
                    return on ? 1 : 0;
                })
                .then(Commands.argument("value", BoolArgumentType.bool())
                    .executes(command -> {
                        boolean on = BoolArgumentType.getBool(command, "value");
                        set(command.getSource().getServer(), rule, on);
                        command.getSource().sendSuccess(() -> Component.literal("Game rule " + rule
                            + " has been updated to " + on), true);
                        return 1;
                    })));
        }
        dispatcher.register(gamerule);
    }

    /** Tells a player arriving what the client rules are. */
    public static void joined(ServerPlayer player) {
        Channels.sendInstruction(player, KIND, clientRules());
    }

    private static void set(MinecraftServer server, String rule, boolean on) {
        SET.put(rule, on);
        AddonState state = ServerHooks.state();
        if (state != null) {
            JsonObject saved = new JsonObject();
            SET.forEach(saved::addProperty);
            state.setSection(SECTION, saved);
        }

        String rules = clientRules();
        server.getPlayerList().getPlayers().forEach(player -> Channels.sendInstruction(player, KIND, rules));

        ScriptHost scripts = ServerHooks.scripts();
        if (scripts != null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("rule", rule);
            fields.put("value", on);
            scripts.raiseWorldEvent("gameRuleChange", fields);
        }
    }

    private static String clientRules() {
        JsonObject rules = new JsonObject();
        for (String rule : new String[] {SHOW_COORDINATES, SHOW_DAYS_PLAYED, SHOW_RECIPE_MESSAGES, SHOW_TAGS}) {
            rules.addProperty(rule, on(rule));
        }
        return rules.toString();
    }

    private static String named(String written) {
        return DEFAULTS.keySet().stream().filter(rule -> rule.equalsIgnoreCase(written)).findFirst().orElse(null);
    }
}
