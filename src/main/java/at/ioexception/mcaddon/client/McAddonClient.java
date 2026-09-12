package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.client.render.AddonClientAssets;
import at.ioexception.mcaddon.client.render.AddonEntityRenderer;
import at.ioexception.mcaddon.client.render.AddonParticleProvider;
import at.ioexception.mcaddon.client.render.Animators;
import at.ioexception.mcaddon.client.render.EntityProperties;
import at.ioexception.mcaddon.client.screen.AddonFormScreen;
import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.network.PackList;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.world.entity.EntityType;

import java.util.List;

public final class McAddonClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AddonClientAssets.load();

        for (EntityType<?> type : AddonEntities.all().keySet()) {
            EntityRendererRegistry.register(cast(type), AddonEntityRenderer::new);
        }

        at.ioexception.mcaddon.registry.AddonParticles.all().keySet().forEach(identifier ->
            Platform.registerParticle(at.ioexception.mcaddon.registry.AddonParticles.typeOf(identifier),
                sprites -> new AddonParticleProvider(identifier, sprites)));

        Channels.receiveProperties(EntityProperties::accept);
        Channels.receivePacks(McAddonClient::reportMissingPacks);
        Channels.receiveAnimation(Animators::play);
        // An empty description is how a pack closes a form it opened.
        Channels.receiveForm((formId, description) -> Platform.setScreen(
            description.isEmpty() ? null : new AddonFormScreen(formId, description)));

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
            .register(client -> at.ioexception.mcaddon.client.render.AddonEmitters.tick());

        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Animators.forget(entity.getId());
            EntityProperties.forget(entity.getId());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Animators.clear();
            EntityProperties.clear();
            at.ioexception.mcaddon.client.render.AddonEmitters.clear();
        });
    }

    /**
     * Says which of the server's add-ons this client has not got.
     *
     * A pack is a file the player installs, so a server cannot hand one out. Without this the models
     * are simply missing and nothing says why.
     */
    private static void reportMissingPacks(String encoded) {
        List<PackList.Entry> missing = PackList.decode(encoded).missingFrom(PackList.of(McAddon.packs()));
        if (missing.isEmpty()) return;

        for (PackList.Entry entry : missing) {
            McAddon.LOGGER.warn("The server has an add-on this client has not got: {} {}",
                entry.name(), entry.version());
        }
        McAddon.LOGGER.warn("Entities and items from those add-ons will not appear until they are "
            + "installed here as well.");
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
