package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.script.ScriptDebugger;
import at.ioexception.mcaddon.script.ScriptDiagnostics;
import at.ioexception.mcaddon.script.ScriptProfiler;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * `/script`, Bedrock's script developer tools: the debugger Mojang's Visual Studio Code extension
 * attaches to, the script profiler and the diagnostics capture.
 *
 * The answers are Bedrock's own where Mojang shows them: `Debugger listening on port <port>`,
 * `Debugger connected to host` and `Profiler stopped. Profile saved to '<path>'`. Bedrock writes its
 * profiles and captures under its log folder, so these go under Java's, `logs/` in the server's
 * folder. A debugger socket is opened on the loopback address only.
 */
public final class ScriptCommand {
    private ScriptCommand() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("script")
            .requires(source -> Platform.allowedAt(source, 2))
            .then(Commands.literal("debugger")
                .then(Commands.literal("listen")
                    .then(Commands.argument("port", IntegerArgumentType.integer(0, 65535))
                        .executes(ScriptCommand::listen)))
                .then(Commands.literal("connect")
                    .executes(command -> connect(command, "127.0.0.1", ScriptDebugger.DEFAULT_PORT))
                    .then(Commands.argument("host", StringArgumentType.word())
                        .executes(command -> connect(command, StringArgumentType.getString(command, "host"),
                            ScriptDebugger.DEFAULT_PORT))
                        .then(Commands.argument("port", IntegerArgumentType.integer(0, 65535))
                            .executes(command -> connect(command, StringArgumentType.getString(command, "host"),
                                IntegerArgumentType.getInteger(command, "port"))))))
                .then(Commands.literal("close").executes(ScriptCommand::close)))
            .then(Commands.literal("profiler")
                .then(Commands.literal("start").executes(ScriptCommand::startProfiler))
                .then(Commands.literal("stop").executes(ScriptCommand::stopProfiler)))
            .then(Commands.literal("diagnostics")
                .then(Commands.literal("startcapture").executes(ScriptCommand::startCapture))
                .then(Commands.literal("stopcapture").executes(ScriptCommand::stopCapture))));
    }

    private static int listen(CommandContext<CommandSourceStack> command) {
        int port = IntegerArgumentType.getInteger(command, "port");
        try {
            ScriptDebugger.listen(command.getSource().getServer(), port);
        } catch (IOException refused) {
            return fail(command, "Debugger could not listen on port " + port + ": " + refused.getMessage());
        }
        return succeed(command, "Debugger listening on port " + port);
    }

    private static int connect(CommandContext<CommandSourceStack> command, String host, int port) {
        try {
            ScriptDebugger.connect(command.getSource().getServer(), host, port);
        } catch (IOException refused) {
            return fail(command, "Debugger could not connect to " + host + ":" + port + ": " + refused.getMessage());
        }
        return succeed(command, "Debugger connected to host");
    }

    private static int close(CommandContext<CommandSourceStack> command) {
        return ScriptDebugger.closeCurrent()
            ? succeed(command, "Debugger closed") : fail(command, "No debugger is open");
    }

    private static int startProfiler(CommandContext<CommandSourceStack> command) {
        if (ScriptProfiler.isRunning()) return fail(command, "Profiler is already running");
        return ScriptProfiler.start(ServerHooks.scripts())
            ? succeed(command, "Profiler started") : fail(command, "No behaviour pack runs a script");
    }

    private static int stopProfiler(CommandContext<CommandSourceStack> command) {
        JsonObject profile = ScriptProfiler.stop();
        if (profile == null) return fail(command, "Profiler is not running");
        Path saved = Path.of("logs", "profiles",
            "ScriptProfile__" + LocalDateTime.now().format(ScriptDiagnostics.LOG_NAME) + ".cpuprofile");
        try {
            Files.createDirectories(saved.getParent());
            Files.writeString(saved, profile.toString(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            return fail(command, "Profiler stopped and the profile could not be saved: " + failed.getMessage());
        }
        return succeed(command, "Profiler stopped. Profile saved to '" + saved.toAbsolutePath() + "'");
    }

    private static int startCapture(CommandContext<CommandSourceStack> command) {
        try {
            return ScriptDiagnostics.startCapture()
                ? succeed(command, "Diagnostics capture started")
                : fail(command, "Diagnostics capture is already running");
        } catch (IOException failed) {
            return fail(command, "Diagnostics capture could not start: " + failed.getMessage());
        }
    }

    private static int stopCapture(CommandContext<CommandSourceStack> command) {
        Path saved = ScriptDiagnostics.stopCapture();
        return saved == null ? fail(command, "Diagnostics capture is not running")
            : succeed(command, "Diagnostics capture stopped. Capture saved to '" + saved.toAbsolutePath() + "'");
    }

    private static int succeed(CommandContext<CommandSourceStack> command, String message) {
        command.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> command, String message) {
        command.getSource().sendFailure(Component.literal(message));
        return 0;
    }
}
