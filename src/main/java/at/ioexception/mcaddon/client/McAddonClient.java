package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.bedrock.camera.CameraShake;
import at.ioexception.mcaddon.bedrock.camera.CameraView;
import at.ioexception.mcaddon.bedrock.fog.FogDefinition;
import at.ioexception.mcaddon.client.render.AddonClientAssets;
import at.ioexception.mcaddon.client.render.AddonEntityRenderer;
import at.ioexception.mcaddon.client.render.AddonParticleProvider;
import at.ioexception.mcaddon.client.render.Animators;
import at.ioexception.mcaddon.client.render.JavaModelPoses;
import at.ioexception.mcaddon.client.render.EntityProperties;
import at.ioexception.mcaddon.client.render.PlayerCamera;
import at.ioexception.mcaddon.client.render.PlayerFog;
import at.ioexception.mcaddon.client.render.PlayerHud;
import at.ioexception.mcaddon.client.screen.AddonFormScreen;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.EntityType;

import java.util.List;

public final class McAddonClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        follow();
        ActiveAddons.onActivate(set -> follow());
        AddonSession.register();

        Channels.receiveProperties((entityId, json) -> {
            EntityProperties.accept(entityId, json);
            mirrorGroups(net.minecraft.client.Minecraft.getInstance().level == null
                ? null : net.minecraft.client.Minecraft.getInstance().level.getEntity(entityId));
        });
        Channels.receiveCameraView(encoded -> PlayerCamera.accept(CameraView.decode(encoded)));
        Channels.receiveCameraShake(encoded -> PlayerCamera.shake(CameraShake.decode(encoded)));
        Channels.receiveFog(encoded -> PlayerFog.accept(FogDefinition.decode(encoded)));
        Channels.receiveHud(PlayerHud::accept);
        Channels.receiveInputPermissions(PlayerInput::accept);
        Channels.receiveAnimation(Animators::play);
        Channels.receiveParticle((effect, x, y, z) ->
            at.ioexception.mcaddon.client.render.AddonEmitters.start(effect, null,
                new net.minecraft.world.phys.Vec3(x, y, z)));
        Channels.receiveForm(AddonFormScreen::receive);

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
            .register(client -> {
                at.ioexception.mcaddon.client.render.AddonEmitters.tick();
                if (client.level != null) Animators.tickOffscreen(client.level);
            });
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(ScreenshotRequests::tick);
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(LanRequests::tick);
        }

        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> mirrorGroups(entity));
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            Animators.forget(entity.getId());
            JavaModelPoses.forget(entity.getId());
            EntityProperties.forget(entity.getId());
        });
        // A connection to a server that drops is noticed on the network thread, which is where this
        // event is raised, while the render thread is still drawing from the same state.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            Animators.clear();
            JavaModelPoses.clear();
            EntityProperties.clear();
            PlayerCamera.clear();
            PlayerFog.clear();
            PlayerHud.clear();
            PlayerInput.clear();
            at.ioexception.mcaddon.client.render.AddonEmitters.clear();
        }));
    }

    /** What the drawing side registers once per object, so an object registered later is prepared too. */
    private static final java.util.Set<Object> PREPARED = new java.util.HashSet<>();
    private static final java.util.Set<net.minecraft.world.item.Item> TINTED = new java.util.HashSet<>();
    private static boolean started;

    /**
     * Prepares the drawing side for the active add-ons: their models, a renderer for every entity
     * type and a provider for every particle type registered so far, a tint for every dyeable item,
     * what the game prepared for every item at startup for an item registered since, and creative
     * tabs built again the next time they are opened.
     */
    private static void follow() {
        AddonClientAssets.load();
        AddonEntities.all().keySet().stream().filter(PREPARED::add)
            .forEach(type -> EntityRendererRegistry.register(cast(type), AddonEntityRenderer::new));
        at.ioexception.mcaddon.registry.AddonParticles.all().keySet().stream()
            .filter(identifier -> PREPARED.add(at.ioexception.mcaddon.registry.AddonParticles.typeOf(identifier)))
            .forEach(identifier -> Platform.registerParticle(
                at.ioexception.mcaddon.registry.AddonParticles.typeOf(identifier),
                sprites -> new AddonParticleProvider(identifier, sprites)));
        List<net.minecraft.world.item.Item> late = net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
            .filter(PREPARED::add).toList();
        // The game prepares every item there is as it starts, which is after the first of these.
        if (started) late.forEach(Platform::prepareLateItem);
        started = true;
        Platform.registerDyeTint(at.ioexception.mcaddon.registry.AddonItems.dyeable().stream()
            .filter(TINTED::add).toList());
        at.ioexception.mcaddon.mixin.CreativeModeTabsAccessor.mcaddon$setCachedParameters(null);
    }

    /** Properties can arrive before the entity they belong to, so both arrivals apply them. */
    private static void mirrorGroups(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof at.ioexception.mcaddon.entity.AddonEntity addon) {
            addon.mirrorGroups(EntityProperties.of(entity.getId()));
        }
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
