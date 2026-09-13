package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonItems;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the swap key from putting into the off hand what `minecraft:allow_off_hand` bars from it.
 *
 * Swapping the hands writes both slots outright rather than asking the screen, so the slot's own
 * rule never comes up. The client is left to correct itself the way it does for any refused action.
 *
 * A packet handler's first statement is the one that moves it to the server thread, by throwing and
 * having the packet delivered again, so the head of one runs first on the network thread. The
 * inventory this reads belongs to the server thread, and the pass that matters is the second.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerActionMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void mcaddon$swap(ServerboundPlayerActionPacket packet, CallbackInfo info) {
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) return;

        MinecraftServer server = player.level().getServer();
        if (server == null || !server.isSameThread()) return;

        if (AddonItems.refusedOffHand(player.getItemInHand(InteractionHand.MAIN_HAND))) info.cancel();
    }
}
