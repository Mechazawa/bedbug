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
 * The fog stack `/fog` and a script's `Player.fogSettings` keep for each player.
 *
 * Bedrock layers fogs and names each layer twice: once by the fog it draws and once by a tag the
 * pack chose, which is what a later `pop` or `remove` finds it by. The layer pushed last is the one
 * drawn, so every change sends that player the fog now on top, or nothing when the stack is empty.
 *
 * The stack is a player's own and is held for as long as they are connected, which is how long a
 * Bedrock one lasts as well.
 */
public final class PlayerFogs {
    /** How many layers Bedrock lets one player's stack hold. */
    public static final int MAX_LAYERS = 16;

    /** The tag a layer pushed without one carries, which is Bedrock's own spelling of it. */
    public static final String UNTAGGED = "untagged";

    private static final Map<UUID, List<Layer>> STACKS = new HashMap<>();

    private record Layer(String userId, String fogId) {
    }

    private PlayerFogs() {
    }

    /**
     * Where on the stack the fog went, counted from the bottom, or -1 when no pack describes that
     * fog or the stack is already full.
     */
    public static int push(ServerPlayer player, String fogId, String userId) {
        if (AddonFogs.definitionOf(fogId) == null) return -1;

        List<Layer> stack = STACKS.computeIfAbsent(player.getUUID(), key -> new ArrayList<>());
        if (stack.size() >= MAX_LAYERS) return -1;

        stack.add(new Layer(userId == null ? UNTAGGED : userId, fogId));
        send(player);
        return stack.size() - 1;
    }

    /**
     * Takes off the layer pushed last under that tag, or the last layer of all when the tag is null,
     * and answers with the fog it carried, or null when nothing came off.
     */
    public static String pop(ServerPlayer player, String userId) {
        List<Layer> stack = STACKS.get(player.getUUID());
        if (stack == null) return null;

        for (int i = stack.size() - 1; i >= 0; i--) {
            if (userId != null && !stack.get(i).userId().equals(userId)) continue;
            Layer popped = stack.remove(i);
            send(player);
            return popped.fogId();
        }
        return null;
    }

    /** Takes off every layer pushed under that tag, or every layer of all when the tag is null. */
    public static boolean remove(ServerPlayer player, String userId) {
        List<Layer> stack = STACKS.get(player.getUUID());
        if (stack == null || !stack.removeIf(layer -> userId == null || layer.userId().equals(userId))) {
            return false;
        }

        send(player);
        return true;
    }

    /**
     * Replaces the whole stack with these fogs, bottom first, all under the one tag.
     *
     * False, with the stack left as it was, when there are more than a stack holds or any of them
     * is a fog no pack describes.
     */
    public static boolean replace(ServerPlayer player, List<String> fogIds, String userId) {
        if (fogIds.size() > MAX_LAYERS
            || fogIds.stream().anyMatch(fogId -> AddonFogs.definitionOf(fogId) == null)) {
            return false;
        }

        String tag = userId == null ? UNTAGGED : userId;
        STACKS.put(player.getUUID(), new ArrayList<>(fogIds.stream()
            .map(fogId -> new Layer(tag, fogId)).toList()));
        send(player);
        return true;
    }

    /** The fogs on the stack, bottom first. */
    public static List<String> fogs(ServerPlayer player) {
        return STACKS.getOrDefault(player.getUUID(), List.of()).stream().map(Layer::fogId).toList();
    }

    /** Every tag a layer on the stack carries, each once, in the order they were first pushed. */
    public static List<String> tags(ServerPlayer player) {
        return STACKS.getOrDefault(player.getUUID(), List.of()).stream()
            .map(Layer::userId).distinct().toList();
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
