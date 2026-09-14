package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.fog.FogDefinition;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.registry.AddonFogs;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The fog stack `/fog` keeps for each player.
 *
 * Bedrock layers fogs and names each layer twice: once by the fog it draws and once by a name the
 * pack chose, which is what a later `pop` or `remove` finds it by. The layer pushed last is the one
 * drawn, so every change sends that player the fog now on top, or nothing when the stack is empty.
 *
 * The stack is a player's own and is held for as long as they are connected, which is how long a
 * Bedrock one lasts as well.
 */
public final class PlayerFogs {
    private static final Map<UUID, List<Layer>> STACKS = new HashMap<>();

    private record Layer(String userId, String fogId) {
    }

    private PlayerFogs() {
    }

    /** False when no pack describes that fog, which the command reports instead of pushing it. */
    public static boolean push(ServerPlayer player, String fogId, String userId) {
        if (AddonFogs.definitionOf(fogId) == null) return false;

        STACKS.computeIfAbsent(player.getUUID(), key -> new ArrayList<>())
            .add(new Layer(userId, fogId));
        send(player);
        return true;
    }

    /** Bedrock's `pop` takes off the layer that was pushed last under that name, and only that one. */
    public static boolean pop(ServerPlayer player, String userId) {
        List<Layer> stack = STACKS.get(player.getUUID());
        if (stack == null) return false;

        for (int i = stack.size() - 1; i >= 0; i--) {
            if (!stack.get(i).userId().equals(userId)) continue;
            stack.remove(i);
            send(player);
            return true;
        }
        return false;
    }

    /** `remove` takes off every layer pushed under that name. */
    public static boolean remove(ServerPlayer player, String userId) {
        List<Layer> stack = STACKS.get(player.getUUID());
        if (stack == null || !stack.removeIf(layer -> layer.userId().equals(userId))) return false;

        send(player);
        return true;
    }

    public static void forget(ServerPlayer player) {
        STACKS.remove(player.getUUID());
    }

    /** The layer on top, skipping any whose pack has gone since it was pushed. */
    private static void send(ServerPlayer player) {
        List<Layer> stack = STACKS.getOrDefault(player.getUUID(), List.of());

        for (int i = stack.size() - 1; i >= 0; i--) {
            FogDefinition fog = AddonFogs.definitionOf(stack.get(i).fogId());
            if (fog == null) continue;
            Channels.sendFog(player, fog.encode());
            return;
        }
        Channels.sendFog(player, "");
    }
}
