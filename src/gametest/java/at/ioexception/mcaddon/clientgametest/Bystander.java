package at.ioexception.mcaddon.clientgametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A second player on the dedicated server, with no client behind it.
 *
 * The test client is the only real one, so a message meant for somebody else has to have somebody
 * else to go to. This player joins the way Mojang's own game tests join a mock one, through a
 * connection whose far end is a queue, so everything the server sends it can be read back here.
 * Nothing is ever answered, which is the difference from the client that counts in these tests.
 */
final class Bystander {
    static final String NAME = "Bystander";

    private final ServerPlayer player;
    private final EmbeddedChannel channel;
    private final List<CustomPacketPayload> received = new ArrayList<>();

    private Bystander(ServerPlayer player, EmbeddedChannel channel) {
        this.player = player;
        this.channel = channel;
    }

    /** Joins the server's player list. Called on the server thread. */
    static Bystander join(MinecraftServer server) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), NAME);
        ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        return new Bystander(player, channel);
    }

    ServerPlayer player() {
        return player;
    }

    /** Every one of this mod's messages the server has sent this player on one channel, oldest first. */
    List<CustomPacketPayload> sent(String channelId) {
        Object packet;
        while ((packet = channel.readOutbound()) != null) {
            if (packet instanceof ClientboundCustomPayloadPacket custom) received.add(custom.payload());
        }
        return received.stream().filter(payload -> payload.type().id().toString().equals(channelId)).toList();
    }

    /** One field of a message, read the way the client's own decoder hands it to the receiver. */
    static Object field(CustomPacketPayload payload, String name) {
        for (RecordComponent component : payload.getClass().getRecordComponents()) {
            if (!component.getName().equals(name)) continue;
            try {
                component.getAccessor().setAccessible(true);
                return component.getAccessor().invoke(payload);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Could not read " + name + " from " + payload, failure);
            }
        }
        throw new AssertionError(payload + " carries no field named " + name + ".");
    }
}
