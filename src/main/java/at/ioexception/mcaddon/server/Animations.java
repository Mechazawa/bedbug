package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.animation.PlayedAnimation;
import at.ioexception.mcaddon.network.Channels;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Tells every client watching an entity to run one of its pack's clips.
 *
 * An animation is a client effect in Bedrock too, so nothing about it is stored: a player who starts
 * watching afterwards sees the entity mid-pose rather than the clip from its beginning.
 */
public final class Animations {
    private Animations() {
    }

    /** Plays the clip for each watching player `shownTo` accepts, which a script may narrow. */
    public static void play(Entity entity, PlayedAnimation request, Predicate<ServerPlayer> shownTo) {
        List<ServerPlayer> viewers = new ArrayList<>(PlayerLookup.tracking(entity));
        if (entity instanceof ServerPlayer self) viewers.add(self);
        viewers.stream().filter(shownTo).forEach(viewer -> Channels.sendAnimation(viewer, entity.getId(), request));
    }
}
