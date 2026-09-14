package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * What a jump costs the food bar, at the two prices a pack can put on one.
 *
 * Both charges are in the same method and the amount is what tells them apart, so one injector
 * serves both. 26 moved the charge out of `Player.jumpFromGround` and into the override in
 * `ServerPlayer`, which is why this carries two bodies.
 */
//? if >=26 {
@Mixin(net.minecraft.server.level.ServerPlayer.class)
public abstract class PlayerJumpExhaustionMixin {
    @ModifyArg(method = "jumpFromGround",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;causeFoodExhaustion(F)V"))
    private float mcaddon$jumpCost(float amount) {
        return PlayerDefinition.jumpCost(amount);
    }
}
//?} else {
/*@Mixin(net.minecraft.world.entity.player.Player.class)
public abstract class PlayerJumpExhaustionMixin {
    @ModifyArg(method = "jumpFromGround",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;causeFoodExhaustion(F)V"))
    private float mcaddon$jumpCost(float amount) {
        return PlayerDefinition.jumpCost(amount);
    }
}
*///?}
