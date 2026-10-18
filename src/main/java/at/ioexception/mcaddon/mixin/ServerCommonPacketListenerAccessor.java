package at.ioexception.mcaddon.mixin;

import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The connection a joining player arrived on, from the listener of the phase add-ons are handed
 * over in: configuration from 1.21, the login before it.
 *
 * A world opened to LAN runs its own player through the same phase as a guest, over the one
 * in-memory connection of the process, and that player is never sent the host's own add-ons.
 */
//? if >=1.21 {
@Mixin(net.minecraft.server.network.ServerCommonPacketListenerImpl.class)
//?} else {
/*@Mixin(net.minecraft.server.network.ServerLoginPacketListenerImpl.class)
*///?}
public interface ServerCommonPacketListenerAccessor {
    @Accessor("connection")
    Connection mcaddon$connection();
}
