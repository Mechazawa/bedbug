package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/** Finding what the server summoned in the client's copy of the world. */
final class Entities {
    private Entities() {
    }

    /** The first add-on entity of a type the client holds, waiting until there is one. */
    static AddonEntity waitForClient(ClientGameTestContext context, String identifier) {
        context.waitFor(client -> client.level != null
            && find(client.level.entitiesForRendering(), identifier) != null);
        return context.computeOnClient(client -> find(client.level.entitiesForRendering(), identifier));
    }

    /**
     * Takes every entity of a type out of the world at once and waits until the client holds none,
     * because a killed one stays in the client's world for as long as it takes to die.
     */
    static void discardAll(ClientGameTestContext context, TestServerContext server, String identifier) {
        server.runOnServer(minecraft -> minecraft.getAllLevels().forEach(level -> {
            List<Entity> found = new ArrayList<>();
            level.getAllEntities().forEach(entity -> {
                if (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(identifier)) {
                    found.add(entity);
                }
            });
            found.forEach(Entity::discard);
        }));
        context.waitFor(client -> client.level != null
            && find(client.level.entitiesForRendering(), identifier) == null);
    }

    /** The first living entity of a type the client holds, waiting until there is one. */
    static LivingEntity waitForClientLiving(ClientGameTestContext context, String identifier) {
        context.waitFor(client -> client.level != null
            && findLiving(client.level.entitiesForRendering(), identifier) != null);
        return context.computeOnClient(client -> findLiving(client.level.entitiesForRendering(), identifier));
    }

    private static LivingEntity findLiving(Iterable<Entity> entities, String identifier) {
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living
                && BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(identifier)) {
                return living;
            }
        }
        return null;
    }

    private static AddonEntity find(Iterable<Entity> entities, String identifier) {
        for (Entity entity : entities) {
            if (entity instanceof AddonEntity addon
                && BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(identifier)) {
                return addon;
            }
        }
        return null;
    }
}
