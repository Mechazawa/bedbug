package at.ioexception.mcaddon.network;

//? if >=1.21 {
import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.sync.AddonManifest;
import at.ioexception.mcaddon.sync.Transfer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
//?}

/**
 * The payloads an add-on transfer travels in during the configuration phase, which 1.21 brought.
 *
 * Their channels carry the transfer's protocol number, so a client and a server built against
 * different shapes of these messages never read each other's.
 */
public final class SyncChannels {
    private SyncChannels() {
    }

    //? if >=1.21 {
    public record Manifest(String json) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Manifest> TYPE =
            new CustomPacketPayload.Type<>(McAddon.id("sync/1/manifest"));
        private static final StreamCodec<FriendlyByteBuf, Manifest> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(AddonManifest.MAX_LENGTH), Manifest::json, Manifest::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<FriendlyByteBuf, Transfer.Wanted> WANTED = StreamCodec.composite(
        ByteBufCodecs.stringUtf8(64), Transfer.Wanted::sha256,
        ByteBufCodecs.VAR_LONG, Transfer.Wanted::offset,
        Transfer.Wanted::new);

    public record Request(List<Transfer.Wanted> wanted) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Request> TYPE =
            new CustomPacketPayload.Type<>(McAddon.id("sync/1/request"));
        private static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.composite(
            WANTED.apply(ByteBufCodecs.list(Transfer.MAX_WANTED)), Request::wanted, Request::new);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Piece(Transfer.Piece piece) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Piece> TYPE =
            new CustomPacketPayload.Type<>(McAddon.id("sync/1/piece"));
        private static final StreamCodec<FriendlyByteBuf, Piece> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), payload -> payload.piece().sha256(),
            ByteBufCodecs.VAR_LONG, payload -> payload.piece().offset(),
            ByteBufCodecs.byteArray(Transfer.PIECE), payload -> payload.piece().data(),
            (sha256, offset, data) -> new Piece(new Transfer.Piece(sha256, offset, data)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Acknowledged(Transfer.Wanted held) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Acknowledged> TYPE =
            new CustomPacketPayload.Type<>(McAddon.id("sync/1/acknowledged"));
        private static final StreamCodec<FriendlyByteBuf, Acknowledged> CODEC =
            WANTED.map(Acknowledged::new, Acknowledged::held);

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Ready(Transfer.Ready ready) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<Ready> TYPE =
            new CustomPacketPayload.Type<>(McAddon.id("sync/1/ready"));
        private static final StreamCodec<FriendlyByteBuf, Ready> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, payload -> payload.ready().ok(),
            ByteBufCodecs.stringUtf8(1024), payload -> payload.ready().reason(),
            (ok, reason) -> new Ready(new Transfer.Ready(ok, reason)));

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
    //?}

    /** Called from the common initialiser, because the payload types have to exist on both sides. */
    public static void register() {
        //? if >=1.21 {
        PayloadTypeRegistry.clientboundConfiguration().register(Manifest.TYPE, Manifest.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(Piece.TYPE, Piece.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(Acknowledged.TYPE, Acknowledged.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(Ready.TYPE, Ready.CODEC);
        //?}
    }
}
