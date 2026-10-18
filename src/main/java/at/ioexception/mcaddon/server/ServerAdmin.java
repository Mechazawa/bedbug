package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Bedrock commands that run the server rather than the world: `save query`, `setmaxplayers`,
 * `packstack` and `permission`, which Bedrock also spells `ops`.
 *
 * Each answers from what a Java server already holds. `save hold` and `save resume` are Java's
 * `save-off` and `save-on`, and `save query` writes everything out the way `save-all flush` does and
 * lists the world's files. The player limit is the one the player list checks a login against, set
 * for this session only, as Bedrock's is. The pack stack is Java's own list of data packs, which
 * holds every converted add-on, and the add-on resource packs a client is expected to have. The
 * operators are Java's, kept in `ops.json`.
 */
public final class ServerAdmin {
    private ServerAdmin() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("save")
            .requires(source -> Platform.allowedAt(source, 4))
            .then(Commands.literal("query").executes(ServerAdmin::query)));

        dispatcher.register(Commands.literal("setmaxplayers")
            .requires(source -> Platform.allowedAt(source, 3))
            .then(Commands.argument("maxPlayers", IntegerArgumentType.integer())
                .executes(ServerAdmin::limitPlayers)));

        dispatcher.register(Commands.literal("packstack")
            .then(stack("server"))
            .then(stack("client")));

        for (String name : List.of("permission", "ops")) {
            dispatcher.register(Commands.literal(name)
                .requires(source -> Platform.allowedAt(source, 2))
                .then(Commands.literal("list").executes(ServerAdmin::listOperators))
                .then(Commands.literal("reload").executes(ServerAdmin::reloadOperators))
                .then(Commands.literal("set")
                    .then(Commands.argument("player", EntityArgument.players())
                        .then(Commands.literal("operator").executes(command -> setOperator(command, true)))
                        .then(Commands.literal("member").executes(command -> setOperator(command, false))))));
        }
    }

    /**
     * Fails while writing is still on, as Bedrock's does; otherwise writes everything held back and
     * lists every file of the world with its length, returning how many there are.
     */
    private static int query(CommandContext<CommandSourceStack> command) {
        MinecraftServer server = command.getSource().getServer();
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.noSave) return failed(command, "File writing has not been turned off.");
        }

        server.saveEverything(true, true, true);
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        List<String> files;
        try (Stream<Path> walked = Files.walk(world)) {
            files = walked.filter(Files::isRegularFile)
                .filter(file -> !file.getFileName().toString().equals("session.lock"))
                .map(file -> world.relativize(file).toString().replace('\\', '/') + ":" + length(file))
                .toList();
        } catch (IOException unreadable) {
            return failed(command, "The world's files could not be listed: " + unreadable.getMessage());
        }

        command.getSource().sendSuccess(
            () -> Component.literal("Data saved. Files are now ready to be copied."), false);
        command.getSource().sendSuccess(() -> Component.literal(String.join(", ", files)), false);
        return files.size();
    }

    private static long length(Path file) {
        try {
            return Files.size(file);
        } catch (IOException gone) {
            return 0;
        }
    }

    /** Bedrock bounds the limit to what a session can hold, of which the least is one player. */
    private static int limitPlayers(CommandContext<CommandSourceStack> command) {
        int asked = IntegerArgumentType.getInteger(command, "maxPlayers");
        int limit = Math.max(1, asked);
        ((PlayerLimit) command.getSource().getServer().getPlayerList()).mcaddon$limitPlayers(limit);
        command.getSource().sendSuccess(() -> Component.literal("Set max players to " + limit + "."
            + (limit != asked ? " (bounded)" : "")), true);
        return limit;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> stack(String kind) {
        return Commands.literal(kind)
            .executes(command -> packStack(command, kind, false, false))
            .then(Commands.literal("verbose")
                .executes(command -> packStack(command, kind, true, false))
                .then(Commands.literal("exclude-vanilla")
                    .executes(command -> packStack(command, kind, true, true))))
            .then(Commands.literal("exclude-vanilla")
                .executes(command -> packStack(command, kind, false, true)));
    }

    /**
     * The server's stack is Java's selected data packs in the order they apply, the game's own among
     * them unless `exclude-vanilla` leaves them out; the client's is the add-on resource packs. Bedrock
     * returns how many packs it listed, and one when there were none.
     */
    private static int packStack(CommandContext<CommandSourceStack> command, String kind, boolean verbose,
        boolean withoutVanilla) {
        List<String> lines;
        if (kind.equals("server")) {
            Collection<Pack> selected = command.getSource().getServer().getPackRepository().getSelectedPacks();
            lines = selected.stream()
                .filter(pack -> !withoutVanilla || !vanilla(pack))
                .map(pack -> pack.getId() + ": " + pack.getTitle().getString()
                    + (verbose ? " - " + pack.getDescription().getString() : ""))
                .toList();
        } else {
            lines = McAddon.packs().packs().stream()
                .filter(pack -> pack.role() == PackRole.RESOURCE)
                .map(pack -> describe(pack, verbose))
                .toList();
        }

        command.getSource().sendSuccess(() -> Component.literal("The " + kind + " pack stack holds "
            + lines.size() + " pack(s)."), false);
        lines.forEach(line -> command.getSource().sendSuccess(() -> Component.literal("- " + line), false));
        return Math.max(1, lines.size());
    }

    private static boolean vanilla(Pack pack) {
        return pack.getId().equals("vanilla") || pack.getPackSource() == PackSource.FEATURE;
    }

    private static String describe(BedrockPack pack, boolean verbose) {
        return pack.displayName() + (verbose
            ? " " + pack.manifest().uuid() + " " + pack.manifest().version() : "");
    }

    private static int listOperators(CommandContext<CommandSourceStack> command) {
        String[] operators = command.getSource().getServer().getPlayerList().getOps().getUserList();
        command.getSource().sendSuccess(() -> Component.literal(operators.length + " operator(s): "
            + String.join(", ", operators)), false);
        return operators.length;
    }

    /** Reads `ops.json` again and tells each player online what their level now is. */
    private static int reloadOperators(CommandContext<CommandSourceStack> command) {
        MinecraftServer server = command.getSource().getServer();
        try {
            server.getPlayerList().getOps().load();
        } catch (IOException unreadable) {
            return failed(command, "The operator list could not be read: " + unreadable.getMessage());
        }
        server.getPlayerList().getPlayers().forEach(server.getPlayerList()::sendPlayerPermissionLevel);
        command.getSource().sendSuccess(() -> Component.literal("Reloaded the operator list."), true);
        return 1;
    }

    /**
     * An operator is an operator in both editions, and a member is a player with no operator entry.
     * Bedrock's third level, a visitor who may only look, has no Java counterpart, so the line naming
     * it is reported by the translation and never reaches this.
     */
    private static int setOperator(CommandContext<CommandSourceStack> command, boolean operator)
        throws CommandSyntaxException {
        Collection<ServerPlayer> players = EntityArgument.getPlayers(command, "player");
        players.forEach(player -> Platform.setOperator(command.getSource().getServer(), player, operator));
        return players.size();
    }

    private static int failed(CommandContext<CommandSourceStack> command, String why) {
        command.getSource().sendFailure(Component.literal(why));
        return 0;
    }
}
