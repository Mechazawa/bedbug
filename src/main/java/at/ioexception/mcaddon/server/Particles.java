package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.network.Channels;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells the clients near a point to start one of a pack's effects there.
 *
 * A pack's own effect is an emitter that keeps making particles, so a client is asked to run it
 * rather than sent a single particle. Distance is measured the way the game measures any other
 * effect: a player too far away to see it is not told about it.
 */
public final class Particles {
    /** How far an effect carries, matching what the game uses for its own particles. */
    private static final double RANGE = 32;

    private Particles() {
    }

    public static void start(ServerLevel level, String effect, double x, double y, double z) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) <= RANGE * RANGE) {
                Channels.sendParticle(player, effect, x, y, z);
            }
        }
    }
}
