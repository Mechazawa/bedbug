package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WebSockets;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.OutgoingChatMessage;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a player's WebSocket server about each chat message reaching the player: chat, `/say`, `/me`
 * and `/tell` all pass through here, one call per player who receives them.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerChatMixin {
    @Inject(method = "sendChatMessage", at = @At("HEAD"))
    private void mcaddon$heard(OutgoingChatMessage message, boolean filtered, ChatType.Bound bound,
        CallbackInfo info) {
        WebSockets.heard((ServerPlayer) (Object) this, bound, message.content());
    }
}
