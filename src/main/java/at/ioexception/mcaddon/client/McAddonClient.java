package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.client.render.AddonClientAssets;
import at.ioexception.mcaddon.client.render.AddonEntityRenderer;
import at.ioexception.mcaddon.client.render.Animators;
import at.ioexception.mcaddon.client.render.EntityProperties;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.world.entity.EntityType;

public final class McAddonClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AddonClientAssets.load();

        for (EntityType<?> type : AddonEntities.all().keySet()) {
            EntityRendererRegistry.register(cast(type), AddonEntityRenderer::new);
        }

        Channels.receiveProperties(EntityProperties::accept);

        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Animators.forget(entity.getId());
            EntityProperties.forget(entity.getId());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Animators.clear();
            EntityProperties.clear();
        });
    }

    /**
     * The registry holds the types without their entity class, which the renderer's signature needs.
     *
     * Every type in that map was built from `AddonEntity::new`, so the cast is safe and there is no
     * way to say so in the type system from here.
     */
    @SuppressWarnings("unchecked")
    private static <T extends net.minecraft.world.entity.Entity> EntityType<T> cast(EntityType<?> type) {
        return (EntityType<T>) type;
    }
}
