package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * A command a pack wrote for one of its entities to run, as the entity and with the server's authority.
 *
 * `@s` is the entity and a relative position is its own. The authority is the server's rather than
 * the entity's, because a mob carries none of its own and a pack writing a command means the one
 * the console would run: an entity's own source may not run `/scoreboard` or `/tag` at all. Bedrock
 * writes the command with a leading slash as often as without one.
 *
 * @param line the command without its leading slash
 */
public record PackCommand(String line) {
    public static PackCommand of(String written) {
        return new PackCommand(written.startsWith("/") ? written.substring(1) : written);
    }

    /** Runs the command as the entity, and says so in the log when it does not parse. */
    public void runAs(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) return;

        MinecraftServer server = level.getServer();
        CommandSourceStack source = server.createCommandSourceStack()
            .withLevel(level)
            .withEntity(entity)
            .withPosition(entity.position())
            .withRotation(entity.getRotationVector())
            .withSuppressedOutput();
        try {
            server.getCommands().getDispatcher().execute(line, source);
        } catch (CommandSyntaxException error) {
            McAddon.LOGGER.debug("A pack's command as {} did not parse: {}", entity.getType(), line);
        }
    }
}
