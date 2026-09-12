package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.network.Channels;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Tells every client watching an entity to run one of its pack's clips.
 *
 * An animation is a client effect in Bedrock too, so nothing about it is stored: a player who starts
 * watching afterwards sees the entity mid-pose rather than the clip from its beginning.
 */
public final class Animations {
    private Animations() {
    }

    public static void play(Entity entity, String animation, String stopExpression, float blendOut) {
        for (ServerPlayer player : PlayerLookup.tracking(entity)) {
            Channels.sendAnimation(player, entity.getId(), animation, stopExpression, blendOut);
        }
        if (entity instanceof ServerPlayer self) {
            Channels.sendAnimation(self, entity.getId(), animation, stopExpression, blendOut);
        }
    }
}
