package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The commands a pack's script registers on startup.
 *
 * Bedrock lets a script declare commands and expects them to work from then on. Java builds its
 * command tree while the server is still starting, before any pack script has run, so these are
 * added to the live dispatcher afterwards and the tree is resent to everyone who is already on.
 */
public final class CustomCommands {
    private CustomCommands() {
    }

    public static void install(MinecraftServer server, ScriptHost host) {
        String declared = host.startup();
        if (declared == null) return;

        JsonElement parsed = JsonParser.parseString(declared);
        if (!parsed.isJsonArray()) return;

        JsonArray commands = parsed.getAsJsonArray();
        int registered = 0;
        for (int index = 0; index < commands.size(); index++) {
            if (!commands.get(index).isJsonObject()) continue;
            if (register(server, host, commands.get(index).getAsJsonObject(), index)) registered++;
        }

        if (registered == 0) return;
        McAddon.LOGGER.info("Registered {} commands a pack's script declared.", registered);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
    }

    private static boolean register(MinecraftServer server, ScriptHost host, JsonObject command, int index) {
        String name = Json.text(command, "name", "");
        if (name.isBlank()) return false;

        List<JsonObject> mandatory = parameters(command, "mandatory");
        List<JsonObject> optional = parameters(command, "optional");
        List<JsonObject> all = new ArrayList<>(mandatory);
        all.addAll(optional);

        int permission = command.has("permissionLevel") ? command.get("permissionLevel").getAsInt() : 0;
        LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(name)
            .requires(source -> at.ioexception.mcaddon.platform.Platform.allowedAt(source, permission));

        // Every prefix of the argument list is executable, which is what makes the optional ones
        // optional without declaring the command several times over.
        literal = literal.executes(context -> run(host, index, context, all.subList(0, 0)));

        ArgumentBuilder<CommandSourceStack, ?> deepest = null;
        for (int at = all.size() - 1; at >= 0; at--) {
            JsonObject parameter = all.get(at);
            RequiredArgumentBuilder<CommandSourceStack, ?> argument =
                Commands.argument(Json.text(parameter, "name", "arg" + at), type(parameter));

            int count = at + 1;
            if (count >= mandatory.size()) {
                argument = argument.executes(context -> run(host, index, context, all.subList(0, count)));
            }
            if (deepest != null) argument = argument.then(deepest);
            deepest = argument;
        }
        if (deepest != null) literal = literal.then(deepest);

        server.getCommands().getDispatcher().register(literal);
        return true;
    }

    /** Bedrock's parameter types, mapped onto the Brigadier arguments that read the same text. */
    private static com.mojang.brigadier.arguments.ArgumentType<?> type(JsonObject parameter) {
        return switch (Json.text(parameter, "type", "String")) {
            case "Boolean" -> BoolArgumentType.bool();
            case "Integer" -> IntegerArgumentType.integer();
            case "Float" -> DoubleArgumentType.doubleArg();
            // A selector, a block, an item or a location all arrive as text and are resolved by the
            // pack itself, which is closer to Bedrock's behaviour than pre-resolving them would be.
            default -> StringArgumentType.string();
        };
    }

    private static int run(ScriptHost host, int index, CommandContext<CommandSourceStack> context,
        List<JsonObject> parameters) {
        JsonObject origin = new JsonObject();
        origin.addProperty("type", context.getSource().getEntity() == null ? "Server" : "Entity");
        if (context.getSource().getEntity() == null) {
            origin.add("entity", com.google.gson.JsonNull.INSTANCE);
        } else {
            origin.addProperty("entity", context.getSource().getEntity().getStringUUID());
        }

        JsonArray arguments = new JsonArray();
        for (JsonObject parameter : parameters) {
            String name = Json.text(parameter, "name", "");
            Object value = context.getArgument(name, Object.class);
            if (value instanceof Boolean flag) {
                arguments.add(flag);
            } else if (value instanceof Number number) {
                arguments.add(number);
            } else {
                arguments.add(String.valueOf(value));
            }
        }

        JsonObject outcome = host.runCommand(index, origin.toString(), arguments.toString());
        String message = Json.text(outcome, "message", "");
        if (!message.isBlank()) {
            context.getSource().sendSuccess(() -> Component.literal(message), false);
        }
        return outcome.has("status") && outcome.get("status").getAsInt() == 0 ? 1 : 0;
    }

    private static List<JsonObject> parameters(JsonObject command, String key) {
        List<JsonObject> parameters = new ArrayList<>();
        for (JsonElement element : Json.list(command, key)) {
            if (element.isJsonObject()) parameters.add(element.getAsJsonObject());
        }
        return parameters;
    }
}
