package at.ioexception.mcaddon.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stores why a connection ends before closing it.
 *
 * Java closes the channel first and stores the reason after, on the thread that closed it. A tick
 * of the client or the server that sees the closed channel in between reports the connection as
 * ended for no reason, so a player refused with a message is shown a bare "Disconnected" instead.
 */
@Mixin(Connection.class)
public abstract class ConnectionReasonMixin {
    @Shadow
    private DisconnectionDetails disconnectionDetails;

    @Shadow
    public abstract boolean isConnected();

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void mcaddon$storeReasonFirst(DisconnectionDetails details, CallbackInfo callback) {
        if (isConnected()) disconnectionDetails = details;
    }
}
