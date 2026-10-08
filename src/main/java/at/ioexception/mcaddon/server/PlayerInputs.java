package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.script.ScriptHost;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The kinds of input `/inputpermission` and a script have taken away from each player.
 *
 * Moving and looking about are the client's own, so the categories switched off are sent to that
 * player's client, which drops the input; mounting and dismounting are the server's, which refuses
 * them here. The set is held for as long as the player is connected.
 */
public final class PlayerInputs {
    private static final Map<UUID, Set<InputPermission>> DISABLED = new HashMap<>();

    private PlayerInputs() {
    }

    /**
     * Switches one category on or off, and answers whether that changed anything. A change is sent
     * to the player's client and raised to the packs' scripts.
     */
    public static boolean set(ServerPlayer player, InputPermission permission, boolean enabled) {
        Set<InputPermission> disabled = DISABLED.computeIfAbsent(player.getUUID(),
            key -> EnumSet.noneOf(InputPermission.class));
        boolean changed = enabled ? disabled.remove(permission) : disabled.add(permission);
        if (!changed) return false;

        Channels.sendInputPermissions(player, InputPermission.bits(disabled));
        ScriptHost scripts = ServerHooks.scripts();
        if (scripts != null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("player", ScriptHost.EntityHandle.of(player));
            fields.put("category", permission.scriptValue());
            fields.put("enabled", enabled);
            scripts.raiseWorldEvent("playerInputPermissionCategoryChange", fields);
        }
        return true;
    }

    /** Whether the category itself is switched on, which is what a query and a selector read. */
    public static boolean enabled(ServerPlayer player, InputPermission permission) {
        return !DISABLED.getOrDefault(player.getUUID(), Set.of()).contains(permission);
    }

    /** Whether the player may give this input, with the categories covering it taken into account. */
    public static boolean allows(ServerPlayer player, InputPermission permission) {
        return permission.allowedWith(DISABLED.getOrDefault(player.getUUID(), Set.of()));
    }

    public static void forget(ServerPlayer player) {
        DISABLED.remove(player.getUUID());
    }
}
