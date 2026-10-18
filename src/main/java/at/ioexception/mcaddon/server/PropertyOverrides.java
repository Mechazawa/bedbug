package at.ioexception.mcaddon.server;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The entity properties a script has shown one player differently from everybody else.
 *
 * `Player.setPropertyOverrideForEntity` changes what that player's client draws an entity from and
 * nothing else: the entity's own value, which its behaviour and every other player read, stays as it
 * is. So an override is laid over the entity's values only as they are sent to that one player. It
 * is held for as long as the player is connected.
 */
public final class PropertyOverrides {
    private static final Map<UUID, Map<UUID, Map<String, Object>>> BY_PLAYER = new HashMap<>();

    private PropertyOverrides() {
    }

    public static void set(ServerPlayer player, Entity entity, String name, Object value) {
        BY_PLAYER.computeIfAbsent(player.getUUID(), key -> new HashMap<>())
            .computeIfAbsent(entity.getUUID(), key -> new HashMap<>())
            .put(name, value);
        resend(player, entity);
    }

    public static void remove(ServerPlayer player, Entity entity, String name) {
        Map<String, Object> overridden = BY_PLAYER.getOrDefault(player.getUUID(), Map.of()).get(entity.getUUID());
        if (overridden == null || overridden.remove(name) == null) return;

        resend(player, entity);
    }

    public static void clear(ServerPlayer player, Entity entity) {
        Map<UUID, Map<String, Object>> byEntity = BY_PLAYER.get(player.getUUID());
        if (byEntity == null || byEntity.remove(entity.getUUID()) == null) return;

        resend(player, entity);
    }

    /** The entity's own values with this player's overrides laid over them. */
    public static Map<String, Object> seenBy(ServerPlayer player, Entity entity, Map<String, Object> properties) {
        Map<String, Object> overridden = BY_PLAYER.getOrDefault(player.getUUID(), Map.of()).get(entity.getUUID());
        if (overridden == null || overridden.isEmpty()) return properties;

        Map<String, Object> seen = new HashMap<>(properties);
        seen.putAll(overridden);
        return seen;
    }

    public static void forget(ServerPlayer player) {
        BY_PLAYER.remove(player.getUUID());
    }

    /** A player not tracking the entity is sent its values once they start to. */
    private static void resend(ServerPlayer player, Entity entity) {
        if (ServerHooks.state() == null || !PlayerLookup.tracking(entity).contains(player)) return;

        EntityPropertySync.sendTo(player, entity, ServerHooks.state());
    }
}
