package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What moving costs the food bar, at the price the pack put on each way of moving.
 *
 * The same method charges for swimming, sprinting, crouching and walking, one branch per tick, and
 * every charge is the branch's own price times the distance. Swimming and sprinting arrive with the
 * distance already in them, so they are scaled. Walking and crouching arrive as nothing, because
 * Java's price for both is zero and no scaling can raise it, which is why the distance is kept from
 * the head of the call and charged against the walking price instead.
 *
 * 1.21 moved the method from `Player` to `ServerPlayer`, so this carries two bodies.
 */
//? if >=1.21.1 {
@Mixin(net.minecraft.server.level.ServerPlayer.class)
public abstract class PlayerMovementExhaustionMixin {
    @Unique
    private double mcaddonFlatDistance;

    @Inject(method = "checkMovementStatistics", at = @At("HEAD"))
    private void mcaddon$moving(double x, double y, double z, CallbackInfo info) {
        mcaddonFlatDistance = Math.sqrt(x * x + z * z);
    }

    @ModifyArg(method = "checkMovementStatistics",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;causeFoodExhaustion(F)V"))
    private float mcaddon$movementCost(float amount) {
        return PlayerDefinition.movementCost((Player) (Object) this, amount,
            mcaddonFlatDistance);
    }
}
//?} else {
/*@Mixin(net.minecraft.world.entity.player.Player.class)
public abstract class PlayerMovementExhaustionMixin {
    @Unique
    private double mcaddonFlatDistance;

    @Inject(method = "checkMovementStatistics", at = @At("HEAD"))
    private void mcaddon$moving(double x, double y, double z, CallbackInfo info) {
        mcaddonFlatDistance = Math.sqrt(x * x + z * z);
    }

    @ModifyArg(method = "checkMovementStatistics",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float mcaddon$movementCost(float amount) {
        return PlayerDefinition.movementCost((Player) (Object) this, amount,
            mcaddonFlatDistance);
    }
}
*///?}
