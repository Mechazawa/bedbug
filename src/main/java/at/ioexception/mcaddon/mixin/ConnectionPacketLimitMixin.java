package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.server.PacketLimits;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.SocketAddress;

/** Drops a packet a client sends past Bedrock's packet rate limits, on the network thread. */
@Mixin(Connection.class)
public abstract class ConnectionPacketLimitMixin {
    @Unique
    private PacketLimits.Limiter mcaddonLimiter;

    @Shadow
    public abstract PacketFlow getReceiving();

    @Shadow
    public abstract PacketListener getPacketListener();

    @Shadow
    public abstract SocketAddress getRemoteAddress();

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At("HEAD"), cancellable = true)
    private void mcaddon$limitRate(ChannelHandlerContext context, Packet<?> packet, CallbackInfo callback) {
        PacketLimits limits = PacketLimits.active();
        if (limits == null || getReceiving() != PacketFlow.SERVERBOUND) return;
        mcaddonLimiter = limits.limiterFor(mcaddonLimiter);
        if (!mcaddonLimiter.admits(packet, getPacketListener() instanceof ServerGamePacketListener)) {
            McAddon.LOGGER.warn("Packet Limit Violation on packet {} for connection {}",
                packet.getClass().getSimpleName(), getRemoteAddress());
            callback.cancel();
        }
    }
}
