package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens an add-on mount's slots to the inventory key of the player riding it.
 *
 * Java opens a vehicle's screen only for a `HasCustomInventoryScreen`, a fact of its class, while an
 * add-on mount opens to its rider only while its live inventory is a horse's. The head runs first on
 * the network thread, which only moves the packet to the server thread, so only that pass acts.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RiderInventoryMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePlayerCommand", at = @At("HEAD"), cancellable = true)
    private void mcaddon$openMount(ServerboundPlayerCommandPacket packet, CallbackInfo info) {
        if (packet.getAction() != ServerboundPlayerCommandPacket.Action.OPEN_INVENTORY) return;

        MinecraftServer server = player.level().getServer();
        if (server == null || !server.isSameThread()) return;

        if (player.getVehicle() instanceof AddonEntity mount && mount.openedByRider(player)) info.cancel();
    }
}
