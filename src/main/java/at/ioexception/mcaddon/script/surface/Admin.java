package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.script.ScriptConfig;
import at.ioexception.mcaddon.script.ScriptCoverage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.graalvm.polyglot.HostAccess;

import java.io.IOException;
import java.util.UUID;

/**
 * The Java side of `@minecraft/server-admin`: the server's script configuration and its operators.
 *
 * A pack is named by its header uuid, which is the folder its scripts sit in inside the guest and
 * which the module reads off the calling frame.
 */
public final class Admin extends Surface {
    private final ScriptConfig config;

    public Admin(MinecraftServer server, ScriptCoverage coverage, ScriptConfig config) {
        super(server, coverage);
        this.config = config;
    }

    /** The pack's variables as JSON text, or the default folder's alone when the caller is unknown. */
    @HostAccess.Export
    public String variables(String pack) {
        return config.variables(packOf(pack)).toString();
    }

    @HostAccess.Export
    public String[] secretNames(String pack) {
        return config.secretNames(packOf(pack)).toArray(String[]::new);
    }

    /** A game test server calls itself dedicated, so the class decides rather than the server's answer. */
    @HostAccess.Export
    public boolean dedicated() {
        return server instanceof net.minecraft.server.dedicated.DedicatedServer;
    }

    @HostAccess.Export
    public void reloadScriptingConfig() {
        config.reload();
    }

    /** The server's permissions are its operator list, which is read again from `ops.json`. */
    @HostAccess.Export
    public String reloadPermissions() {
        try {
            server.getPlayerList().getOps().load();
        } catch (IOException unreadable) {
            McAddon.LOGGER.error("The operator list could not be read: {}", unreadable.getMessage());
            return "EngineError";
        }
        server.getPlayerList().getPlayers().forEach(server.getPlayerList()::sendPlayerPermissionLevel);
        return null;
    }

    /** Makes or unmakes an operator, answering with the name of the error Bedrock throws, or null. */
    @HostAccess.Export
    public String setOperator(String playerId, boolean operator) {
        if (!(entity(playerId) instanceof ServerPlayer player)) return "InvalidArgumentError";
        if (Platform.isOperator(server, player) == operator) {
            return operator ? "PlayerAlreadyOpError" : "CannotDeopPlayerError";
        }
        Platform.setOperator(server, player, operator);
        return null;
    }

    private static UUID packOf(String pack) {
        try {
            return pack == null ? null : UUID.fromString(pack);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
