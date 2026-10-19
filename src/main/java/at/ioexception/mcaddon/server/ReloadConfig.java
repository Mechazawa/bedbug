package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.script.ScriptHost;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Bedrock's `/reloadconfig`, which reads the variables, secrets and permissions under `config/`
 * again, so a running script reads the new values from its next call on. Bedrock asks for an owner,
 * which on Java is an operator at the top level or the console.
 */
public final class ReloadConfig {
    private ReloadConfig() {
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("reloadconfig")
            .requires(source -> Platform.allowedAt(source, 4))
            .executes(command -> {
                ScriptHost scripts = ServerHooks.scripts();
                if (scripts != null) scripts.config().reload();
                command.getSource().sendSuccess(() -> Component.literal("Reloaded the script configuration."), true);
                return 1;
            }));
    }
}
