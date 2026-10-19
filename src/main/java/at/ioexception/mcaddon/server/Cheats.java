package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bedrock's `allow-cheats`: while it is off, a player may type only the commands Bedrock marks as
 * needing no cheats, whatever their operator level.
 *
 * Everything else that runs a command keeps running it: the console, a command block, a function
 * run on a tick or on a schedule, a script and an entity's own commands. That is what a Bedrock
 * dedicated server does with cheats off, measured on one with a probe of each.
 *
 * A dedicated server starts with what its `server.properties` says, as Bedrock's does, and with
 * cheats on when the file says nothing, which is how a Java server behaves without this mod.
 * `changesetting allow-cheats` changes it until the server stops and leaves the file alone.
 */
public final class Cheats {
    private static final Pattern SPACE = Pattern.compile("\\s+");

    /**
     * The commands Bedrock's command metadata declares with `requires_cheats` false, with the other
     * names each is published under, and the commands Java lets every player run that Bedrock has no
     * command for.
     */
    private static final Set<String> WITHOUT_CHEATS = Set.of(
        "deop", "gamerule", "help", "?", "kick", "list", "me", "op", "packstack", "permission", "ops",
        "project", "say", "serveridentity", "tell", "msg", "w", "tellraw",
        "teammsg", "tm", "trigger");

    /** The commands a pack's script declared with `cheatsRequired` false. */
    private static final Set<String> DECLARED_WITHOUT_CHEATS = new HashSet<>();

    private static volatile boolean allowed = true;

    private Cheats() {
    }

    public static void start(MinecraftServer server) {
        DECLARED_WITHOUT_CHEATS.clear();
        Path properties = Path.of("server.properties");
        allowed = !server.isDedicatedServer() || !Files.isRegularFile(properties)
            || !"false".equalsIgnoreCase(Settings.loadFromFile(properties).getProperty("allow-cheats"));
    }

    public static void declare(String command, boolean cheatsRequired) {
        if (!cheatsRequired) DECLARED_WITHOUT_CHEATS.add(command.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this typed command is refused for want of cheats, telling the source so when it is.
     * A line typed after `/bedrock` is judged by the Bedrock command it names.
     */
    public static boolean refuses(CommandSourceStack source, String command) {
        if (allowed || !source.isPlayer()) return false;

        String[] words = SPACE.split(command.strip().replaceFirst("^/", "").toLowerCase(Locale.ROOT), 3);
        String name = words[0].equals("bedrock") && words.length > 1 ? words[1] : words[0];
        if (WITHOUT_CHEATS.contains(name) || DECLARED_WITHOUT_CHEATS.contains(name)) return false;

        source.sendFailure(Component.literal("Cheats aren't enabled in this world."));
        return true;
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("changesetting")
            .requires(source -> Platform.allowedAt(source, 4))
            .then(Commands.literal("allow-cheats")
                .then(Commands.argument("value", BoolArgumentType.bool())
                    .executes(command -> {
                        allowed = BoolArgumentType.getBool(command, "value");
                        command.getSource().sendSuccess(
                            () -> Component.literal("Changed setting allow-cheats to " + allowed + "."), true);
                        return 1;
                    }))));
    }
}
