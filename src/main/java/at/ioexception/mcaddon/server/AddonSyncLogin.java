package at.ioexception.mcaddon.server;

//? if <1.21 {
/*import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.mixin.ServerCommonPacketListenerAccessor;
import at.ioexception.mcaddon.network.LoginChannels;
import at.ioexception.mcaddon.sync.AddonManifest;
import at.ioexception.mcaddon.sync.Outgoing;
import at.ioexception.mcaddon.sync.ServedArchives;
import at.ioexception.mcaddon.sync.Transfer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
*///?}

/**
 * Hands a joining client the server's add-ons during the login, before 1.21 brought a
 * configuration phase. Fabric's registry sync runs as play begins on these versions, so a client
 * holding everything by the end of the login holds every entry that sync names.
 *
 * Every message is a login query the client answers: the manifest, answered with the archives it
 * wants; each piece, answered as it arrives; and a question asked once at the start and answered
 * only when the server's add-ons are active, which is what the login waits on. The login has no
 * keep-alive and two timers of its own, so while that answer is outstanding the server asks a
 * heartbeat every five seconds, which the client answers at once and which keeps both ends' read
 * timeouts quiet, and `ServerLoginTimerMixin` holds the server's own count of login ticks at zero.
 */
public final class AddonSyncLogin {
    //? if <1.21 {
    /*private static final int HEARTBEAT_TICKS = 100;
    private static final Map<ServerLoginPacketListenerImpl, Login> RUNNING = new ConcurrentHashMap<>();

    /^* One login's transfer, and the future that holds the login until the client is ready. ^/
    private record Login(Outgoing outgoing, PacketSender sender, CompletableFuture<Void> released) {
    }

    private AddonSyncLogin() {
    }

    /^* Whether this login is waiting on a transfer, which is what keeps the server's login timer at zero. ^/
    public static boolean holding(ServerLoginPacketListenerImpl handler) {
        return RUNNING.containsKey(handler);
    }

    public static void register() {
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            if (((ServerCommonPacketListenerAccessor) handler).mcaddon$connection().isMemoryConnection()) return;
            AddonManifest manifest = ServedArchives.manifest();
            CompletableFuture<Void> released = new CompletableFuture<>();
            Outgoing outgoing = new Outgoing(manifest, new Outgoing.Link() {
                @Override
                public void send(Transfer.Piece piece) {
                    sender.sendPacket(LoginChannels.PIECE, LoginChannels.piece(piece));
                }

                @Override
                public void refuse(String reason) {
                    RUNNING.remove(handler);
                    handler.disconnect(Component.literal(reason));
                    released.complete(null);
                }

                @Override
                public void done() {
                    RUNNING.remove(handler);
                    released.complete(null);
                }
            });
            RUNNING.put(handler, new Login(outgoing, sender, released));
            synchronizer.waitFor(released);
            sender.sendPacket(LoginChannels.MANIFEST, LoginChannels.manifest(manifest));
            sender.sendPacket(LoginChannels.READY, LoginChannels.empty());
        });
        ServerLoginNetworking.registerGlobalReceiver(LoginChannels.MANIFEST,
            (server, handler, understood, buffer, synchronizer, responseSender) -> {
                Login login = RUNNING.get(handler);
                if (login == null) return;
                if (!understood) {
                    if (ServedArchives.manifest().entries().isEmpty()) {
                        login.outgoing().ready(new Transfer.Ready(true, ""));
                    } else {
                        login.outgoing().ready(new Transfer.Ready(false, "This server uses Bedrock add-ons. "
                            + "Install Bedrock Add-on Loader " + McAddon.VERSION + " to join it."));
                    }
                    return;
                }
                Transfer.Ready refused = LoginChannels.refusal(buffer);
                if (refused != null) {
                    login.outgoing().ready(refused);
                } else {
                    login.outgoing().request(LoginChannels.wanted(buffer));
                }
            });
        ServerLoginNetworking.registerGlobalReceiver(LoginChannels.PIECE,
            (server, handler, understood, buffer, synchronizer, responseSender) -> {
                Login login = RUNNING.get(handler);
                if (login != null) login.outgoing().acknowledged();
            });
        ServerLoginNetworking.registerGlobalReceiver(LoginChannels.READY,
            (server, handler, understood, buffer, synchronizer, responseSender) -> {
                Login login = RUNNING.get(handler);
                if (login != null && understood) login.outgoing().ready(LoginChannels.ready(buffer));
            });
        ServerLoginNetworking.registerGlobalReceiver(LoginChannels.HEARTBEAT,
            (server, handler, understood, buffer, synchronizer, responseSender) -> { });
        ServerLoginConnectionEvents.DISCONNECT.register((handler, server) -> {
            Login login = RUNNING.remove(handler);
            if (login != null) login.released().complete(null);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();
            boolean beat = server.getTickCount() % HEARTBEAT_TICKS == 0;
            RUNNING.values().forEach(login -> {
                if (login.outgoing().expire(now)) return;
                if (beat) login.sender().sendPacket(LoginChannels.HEARTBEAT, LoginChannels.empty());
            });
        });
    }
    *///?} else {
    private AddonSyncLogin() {
    }

    public static void register() {
    }
    //?}
}
