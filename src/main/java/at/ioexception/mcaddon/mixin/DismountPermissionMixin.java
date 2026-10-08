package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.server.PlayerInputs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a player whose dismount permission is switched off on their vehicle when they sneak.
 *
 * The server decides a dismount from the sneak key it was sent, so the key still reaches it and only
 * the getting off is refused. A vehicle that throws its rider or breaks still puts them down, which
 * Bedrock's permission allows as well.
 */
@Mixin(Player.class)
public abstract class DismountPermissionMixin {
    @Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
    private void mcaddon$refuseDismount(CallbackInfoReturnable<Boolean> info) {
        if ((Object) this instanceof ServerPlayer player && !PlayerInputs.allows(player, InputPermission.DISMOUNT)) {
            info.setReturnValue(false);
        }
    }
}
