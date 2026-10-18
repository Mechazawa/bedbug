package at.ioexception.mcaddon.mixin;

import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

//? if <1.21 {
/*import at.ioexception.mcaddon.server.AddonSyncLogin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Holds the server's count of login ticks at zero while a client is being handed add-ons.
 *
 * Before 1.21 the hand-over happens inside the login, which the server ends after six hundred ticks
 * whatever the client is doing. A client downloading, converting and reloading is making progress
 * the server can see, and `AddonSyncLogin` ends a transfer that stops making it on a deadline of its
 * own. From 1.21 the hand-over is a configuration task, which has no such count, and this does
 * nothing.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginTimerMixin {
    //? if <1.21 {
    /*@Shadow
    private int tick;

    @Inject(method = "tick", at = @At("HEAD"))
    private void mcaddon$holdWhileHandingOver(CallbackInfo info) {
        if (AddonSyncLogin.holding((ServerLoginPacketListenerImpl) (Object) this)) tick = 0;
    }
    *///?}
}
