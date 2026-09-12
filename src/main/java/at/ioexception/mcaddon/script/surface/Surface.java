package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.script.ScriptCoverage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * What every part of the script surface needs: the server, and the lookups that reach into it.
 *
 * The surface is split by what it answers about rather than kept as one class, because one class
 * answering for entities, blocks, items, containers and the world at once is a file nobody can hold
 * in their head. Each part exports its own members and the bridge hands them out under a name.
 */
public abstract class Surface {
    protected final MinecraftServer server;
    protected final ScriptCoverage coverage;

    protected Surface(MinecraftServer server, ScriptCoverage coverage) {
        this.server = server;
        this.coverage = coverage;
    }

    /** Reports a member a pack called that this mod does not answer, and hands back nothing. */
    protected Object unsupported(String member) {
        coverage.unsupported(member);
        return null;
    }

    /** The level a dimension id names, or null when nothing by that name is loaded. */
    protected ServerLevel level(String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (dimensionOf(level).equals(dimension)) return level;
        }
        return null;
    }

    protected static String dimensionOf(Level level) {
        return at.ioexception.mcaddon.platform.Platform.idOf(level.dimension()).toString();
    }

    /** The entity an id names, wherever it is. */
    protected Entity entity(String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }

        for (ServerLevel level : server.getAllLevels()) {
            Entity found = at.ioexception.mcaddon.platform.Platform.entityByUuid(level, uuid);
            if (found != null) return found;
        }
        return null;
    }
}
