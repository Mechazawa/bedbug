package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.state.AddonState;
import at.ioexception.mcaddon.util.Json;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * Keeps every client's copy of an entity's properties up to date.
 *
 * A behaviour pack sets properties and a resource pack draws from them. On a server those two halves
 * are on different machines, so the server sends the values on every change and to each client that
 * starts tracking the entity. Without the second half, an entity changed before a player looked at it
 * draws wrong until it changes again.
 */
public final class EntityPropertySync {
    private EntityPropertySync() {
    }

    /**
     * The components whose whole effect is a number a resource pack draws from.
     *
     * A pack picks a geometry and a texture by these, so an entity that never gets them drawn from
     * its own declaration looks like every other entity of its kind.
     */
    private static final List<String> DRAWN_FROM =
        List.of("minecraft:variant", "minecraft:mark_variant", "minecraft:skin_id");

    /** Fills in the values a pack declared defaults for, the first time the entity is seen. */
    public static void seed(Entity entity, AddonState state) {
        if (!(entity instanceof AddonEntity addon) || addon.definition() == null) return;

        for (ServerEntityDefinition.Property property : addon.definition().properties().values()) {
            if (state.property(entity.getUUID(), property.name()) == null) {
                state.setProperty(entity.getUUID(), property.name(), property.initial());
            }
        }

        // An entity's own components are not an event's to apply, so the ones a pack draws from are
        // read straight off the definition it was built with.
        for (String component : DRAWN_FROM) {
            JsonObject body = addon.definition().components().get(component);
            String name = component.substring("minecraft:".length());
            if (body != null && state.property(entity.getUUID(), name) == null) {
                state.setProperty(entity.getUUID(), name,
                    body.has("value") ? body.get("value").getAsDouble() : 0d);
            }
        }
    }

    public static void sendTo(ServerPlayer player, Entity entity, AddonState state) {
        Map<String, Object> properties = state.properties(entity.getUUID());
        if (properties.isEmpty()) return;
        Channels.sendProperties(player, entity.getId(),
            Json.encode(PropertyOverrides.seenBy(player, entity, properties)));
    }

    public static void broadcast(Entity entity, AddonState state) {
        if (state.properties(entity.getUUID()).isEmpty()) return;

        for (ServerPlayer player : PlayerLookup.tracking(entity)) sendTo(player, entity, state);
    }
}
