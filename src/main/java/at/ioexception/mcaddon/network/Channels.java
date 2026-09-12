package at.ioexception.mcaddon.network;

import at.ioexception.mcaddon.McAddon;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

//? if >=1.21 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}

/**
 * What the server tells clients about add-on entities.
 *
 * The server owns every entity property a behaviour pack declares, because a pack's script sets them
 * and scripts only run there. The client needs them to answer `q.property` while it draws, so each
 * change and each newly tracked entity is pushed across.
 *
 * Networking was rewritten in 1.21: payload objects with codecs replaced raw channel buffers.
 */
public final class Channels {
    private static final Identifier PROPERTIES = McAddon.id("entity_properties");

    private Channels() {
    }

    //? if >=1.21 {
    private record Properties(int entityId, String values) implements CustomPacketPayload {
        private static final CustomPacketPayload.Type<Properties> TYPE = new CustomPacketPayload.Type<>(PROPERTIES);

        private static final StreamCodec<FriendlyByteBuf, Properties> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Properties::entityId,
            ByteBufCodecs.STRING_UTF8, Properties::values,
            Properties::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    /** Called from the common initialiser, because the payload type has to exist on both sides. */
    public static void register() {
        //? if >=1.21 {
        PayloadTypeRegistry.clientboundPlay().register(Properties.TYPE, Properties.CODEC);
        //?}
    }

    public static void sendProperties(ServerPlayer player, int entityId, String values) {
        //? if >=1.21 {
        ServerPlayNetworking.send(player, new Properties(entityId, values));
        //?} else {
        /*FriendlyByteBuf buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buffer.writeVarInt(entityId);
        buffer.writeUtf(values);
        ServerPlayNetworking.send(player, PROPERTIES, buffer);
        *///?}
    }

    /** The client hands in what to do with an update; the wire shape stays in here. */
    public static void receiveProperties(BiConsumer<Integer, String> handler) {
        //? if >=1.21 {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            Properties.TYPE,
            (payload, context) -> context.client().execute(
                () -> handler.accept(payload.entityId(), payload.values())));
        //?} else {
        /*net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
            PROPERTIES,
            (client, network, buffer, sender) -> {
                int entityId = buffer.readVarInt();
                String values = buffer.readUtf();
                client.execute(() -> handler.accept(entityId, values));
            });
        *///?}
    }
}
