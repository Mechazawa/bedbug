package at.ioexception.mcaddon.clientgametest.mixin;

import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stops closing a test world from deadlocking.
 *
 * Fabric runs the client's disconnect in the tick phase after the test asks for it, while the server
 * has already halted itself because its owner logged out and waits in Fabric's tick phaser.
 * Vanilla's guest removal then blocks the render thread for a server thread that never runs it.
 * The removal is queued instead, or run in place on the server thread, or dropped once the server
 * has stopped, since no guest outlives a stopped server.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerHaltMixin {
    @Redirect(method = "halt", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/server/IntegratedServer;executeBlocking(Ljava/lang/Runnable;)V"))
    private void mcaddon$queueGuestRemoval(IntegratedServer server, Runnable task) {
        if (server.isSameThread()) {
            task.run();
        } else if (!server.isStopped()) {
            server.execute(task);
        }
    }
}
