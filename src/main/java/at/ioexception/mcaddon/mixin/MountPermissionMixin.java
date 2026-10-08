package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.server.PlayerInputs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses a player whose mount permission is switched off the vehicle they asked to get on.
 *
 * Every vehicle a player climbs onto by pressing a key asks this without forcing it; a command
 * putting the player on one forces it, and Bedrock's permission governs only the player's own input.
 */
@Mixin(Entity.class)
public abstract class MountPermissionMixin {
    //? if >=26 {
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
    private void mcaddon$refuseMount(Entity vehicle, boolean force, boolean sendEventAndTriggers,
        CallbackInfoReturnable<Boolean> info) {
        if (!force && (Object) this instanceof ServerPlayer player
            && !PlayerInputs.allows(player, InputPermission.MOUNT)) {
            info.setReturnValue(false);
        }
    }
    //?} else {
    /*@Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At("HEAD"), cancellable = true)
    private void mcaddon$refuseMount(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> info) {
        if (!force && (Object) this instanceof ServerPlayer player
            && !PlayerInputs.allows(player, InputPermission.MOUNT)) {
            info.setReturnValue(false);
        }
    }
    *///?}
}
