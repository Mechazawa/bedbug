package at.ioexception.mcaddon.server;

//? if >=1.21 {
import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.mixin.ServerCommonPacketListenerAccessor;
import at.ioexception.mcaddon.network.SyncChannels;
import at.ioexception.mcaddon.sync.AddonManifest;
import at.ioexception.mcaddon.sync.Outgoing;
import at.ioexception.mcaddon.sync.ServedArchives;
import at.ioexception.mcaddon.sync.Transfer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
//?}

/**
 * Hands a joining client the server's add-ons during the configuration phase, before Fabric's
 * registry sync runs, so the client holds every entry that sync names.
 *
 * The task is added in an event phase ordered before the default one, which is where Fabric adds
 * its own sync, and the configuration tasks added there run one at a time in the order they were
 * added. The world's own player on a LAN host arrives over the in-memory connection and is never
 * given a task, because its add-ons are the server's.
 */
//? if >=1.21 {
public final class AddonSyncTask implements ConfigurationTask {
    public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type(McAddon.MOD_ID + ":sync");
    private static final Identifier EARLY = McAddon.id("sync");
    private static final Map<ServerConfigurationPacketListenerImpl, AddonSyncTask> RUNNING = new ConcurrentHashMap<>();

    private final AddonManifest manifest;
    private final Outgoing outgoing;

    private AddonSyncTask(ServerConfigurationPacketListenerImpl handler, AddonManifest manifest) {
        this.manifest = manifest;
        this.outgoing = new Outgoing(manifest, new Outgoing.Link() {
            @Override
            public void send(Transfer.Piece piece) {
                ServerConfigurationNetworking.send(handler, new SyncChannels.Piece(piece));
            }

            @Override
            public void refuse(String reason) {
                RUNNING.remove(handler);
                handler.disconnect(Component.literal(reason));
            }

            @Override
            public void done() {
                RUNNING.remove(handler);
                handler.completeTask(TYPE);
            }
        });
    }

    @Override
    public void start(Consumer<Packet<?>> send) {
        send.accept(ServerConfigurationNetworking.createClientboundPacket(
            new SyncChannels.Manifest(manifest.encode())));
    }

    @Override
    public ConfigurationTask.Type type() {
        return TYPE;
    }

    public static void register() {
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(EARLY, (handler, server) -> {
            if (((ServerCommonPacketListenerAccessor) handler).mcaddon$connection().isMemoryConnection()) return;
            AddonManifest manifest = ServedArchives.manifest();
            boolean modded = ServerConfigurationNetworking.canSend(handler, SyncChannels.Manifest.TYPE);
            // A client without this mod joins a server holding no add-ons as it would without it; one
            // with it is still told, so its own add-ons go dormant for the visit.
            if (!modded && manifest.entries().isEmpty()) return;
            if (!modded) {
                handler.disconnect(Component.literal("This server uses Bedrock add-ons. Install Bedrock Add-on Loader "
                    + McAddon.VERSION + " to join it."));
                return;
            }
            AddonSyncTask task = new AddonSyncTask(handler, manifest);
            RUNNING.put(handler, task);
            handler.addTask(task);
        });
        ServerConfigurationNetworking.registerGlobalReceiver(SyncChannels.Request.TYPE, (payload, context) ->
            of(context.packetListener(), outgoing -> outgoing.request(payload.wanted())));
        ServerConfigurationNetworking.registerGlobalReceiver(SyncChannels.Acknowledged.TYPE, (payload, context) ->
            of(context.packetListener(), Outgoing::acknowledged));
        ServerConfigurationNetworking.registerGlobalReceiver(SyncChannels.Ready.TYPE, (payload, context) ->
            of(context.packetListener(), outgoing -> outgoing.ready(payload.ready())));
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> RUNNING.remove(handler));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();
            RUNNING.values().forEach(task -> task.outgoing.expire(now));
        });
    }

    private static void of(ServerConfigurationPacketListenerImpl handler, Consumer<Outgoing> action) {
        AddonSyncTask task = RUNNING.get(handler);
        if (task != null) action.accept(task.outgoing);
    }
}
//?} else {
/*public final class AddonSyncTask {
    public static void register() {
    }
}
*///?}
