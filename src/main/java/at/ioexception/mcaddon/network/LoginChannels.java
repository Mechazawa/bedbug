package at.ioexception.mcaddon.network;

//? if <1.21 {
/*import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.sync.AddonManifest;
import at.ioexception.mcaddon.sync.Transfer;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
*///?}

/**
 * The login queries an add-on transfer travels in before 1.21, and the shape of each one's
 * question and answer.
 *
 * The channels carry the transfer's protocol number, as the configuration channels do.
 */
public final class LoginChannels {
    //? if <1.21 {
    /*public static final Identifier MANIFEST = McAddon.id("sync/1/manifest");
    public static final Identifier PIECE = McAddon.id("sync/1/piece");
    public static final Identifier READY = McAddon.id("sync/1/ready");
    public static final Identifier HEARTBEAT = McAddon.id("sync/1/heartbeat");

    private LoginChannels() {
    }

    public static FriendlyByteBuf empty() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    public static FriendlyByteBuf manifest(AddonManifest manifest) {
        FriendlyByteBuf buffer = empty();
        buffer.writeUtf(manifest.encode(), AddonManifest.MAX_LENGTH);
        return buffer;
    }

    public static String manifest(FriendlyByteBuf buffer) {
        return buffer.readUtf(AddonManifest.MAX_LENGTH);
    }

    public static FriendlyByteBuf piece(Transfer.Piece piece) {
        FriendlyByteBuf buffer = empty();
        buffer.writeUtf(piece.sha256(), 64);
        buffer.writeVarLong(piece.offset());
        buffer.writeByteArray(piece.data());
        return buffer;
    }

    public static Transfer.Piece piece(FriendlyByteBuf buffer) {
        return new Transfer.Piece(buffer.readUtf(64), buffer.readVarLong(), buffer.readByteArray(Transfer.PIECE));
    }

    /^* The answer to the manifest: the archives wanted, or why the client refuses them all. ^/
    public static FriendlyByteBuf wanted(List<Transfer.Wanted> wanted) {
        FriendlyByteBuf buffer = empty();
        buffer.writeBoolean(true);
        buffer.writeVarInt(wanted.size());
        wanted.forEach(one -> {
            buffer.writeUtf(one.sha256(), 64);
            buffer.writeVarLong(one.offset());
        });
        return buffer;
    }

    public static FriendlyByteBuf refused(String reason) {
        FriendlyByteBuf buffer = empty();
        buffer.writeBoolean(false);
        buffer.writeUtf(reason, 1024);
        return buffer;
    }

    /^* The refusal an answer to the manifest carries, or null when it names archives instead. ^/
    public static Transfer.Ready refusal(FriendlyByteBuf buffer) {
        return buffer.readBoolean() ? null : new Transfer.Ready(false, buffer.readUtf(1024));
    }

    /^* The archives an answer to the manifest names, read after `refusal` found none. ^/
    public static List<Transfer.Wanted> wanted(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > Transfer.MAX_WANTED) throw new IllegalArgumentException("too many archives wanted");
        List<Transfer.Wanted> wanted = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            wanted.add(new Transfer.Wanted(buffer.readUtf(64), buffer.readVarLong()));
        }
        return wanted;
    }

    public static FriendlyByteBuf ready(Transfer.Ready ready) {
        FriendlyByteBuf buffer = empty();
        buffer.writeBoolean(ready.ok());
        buffer.writeUtf(ready.reason(), 1024);
        return buffer;
    }

    public static Transfer.Ready ready(FriendlyByteBuf buffer) {
        return new Transfer.Ready(buffer.readBoolean(), buffer.readUtf(1024));
    }
    *///?} else {
    private LoginChannels() {
    }
    //?}
}
