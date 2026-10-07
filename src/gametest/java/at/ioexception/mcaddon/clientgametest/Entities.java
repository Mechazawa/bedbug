package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

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
