package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.hud.HudElement;
import at.ioexception.mcaddon.network.Channels;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The pieces of each player's heads-up display that `/hud` and a script have hidden.
 *
 * Only that player's client draws them, so every change is sent to that player alone. The set is
 * held for as long as the player is connected; a client starts every connection with all of its
 * pieces drawn.
 */
public final class PlayerHuds {
    private static final Map<UUID, Set<HudElement>> HIDDEN = new HashMap<>();

    private PlayerHuds() {
    }

    public static void hide(ServerPlayer player, Collection<HudElement> elements) {
        if (hidden(player).containsAll(elements)) return;

        HIDDEN.computeIfAbsent(player.getUUID(), key -> EnumSet.noneOf(HudElement.class)).addAll(elements);
        send(player);
    }

    /** Draws these pieces again as the player's own settings say to. */
    public static void reset(ServerPlayer player, Collection<HudElement> elements) {
        Set<HudElement> hidden = HIDDEN.get(player.getUUID());
        if (hidden == null || !hidden.removeAll(elements)) return;

        send(player);
    }

    public static Set<HudElement> hidden(ServerPlayer player) {
        return Set.copyOf(HIDDEN.getOrDefault(player.getUUID(), Set.of()));
    }

    public static void forget(ServerPlayer player) {
        HIDDEN.remove(player.getUUID());
    }

    private static void send(ServerPlayer player) {
        Channels.sendHud(player, HudElement.bits(hidden(player)));
    }
}
