package at.ioexception.mcaddon.clientgametest.mixin;

import at.ioexception.mcaddon.clientgametest.SlowClose;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionCloseMixin {
    @Shadow
    public abstract PacketFlow getReceiving();

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At(value = "INVOKE",
        target = "Lio/netty/channel/ChannelFuture;awaitUninterruptibly()Lio/netty/channel/ChannelFuture;",
        shift = At.Shift.AFTER))
    private void mcaddon$holdAfterClose(DisconnectionDetails details, CallbackInfo callback) {
        if (getReceiving() == PacketFlow.CLIENTBOUND) SlowClose.hold();
    }
}
