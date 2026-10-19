package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

//? if >=26.3 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.item.component.SwingAnimation;
*///?} else {
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/**
 * Tells a pack's script that a player swung their arm.
 *
 * Bedrock has no off hand, so only the main one is reported. Up to 26.2 the client reports every
 * swing in a packet of its own. From 26.3 the server starts a player's swing itself, as it handles a
 * punch, a use and an interaction, and reports it to the client instead.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    //? if >=26.3 {
    /*@WrapOperation(method = "handlePunch", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;"
            + "swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z"))
    private boolean mcaddon$punched(ServerPlayer swinging, InteractionHand hand, SwingAnimation animation,
        boolean self, Operation<Boolean> original) {
        boolean started = original.call(swinging, hand, animation, self);
        if (started && hand == InteractionHand.MAIN_HAND) WorldEvents.swung(player);
        return started;
    }

    @WrapOperation(method = {"handleUseItemOn", "handleUseItem", "handleInteract"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;swingAndResetAttackStrength("
            + "Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)V"))
    private void mcaddon$used(ServerPlayer swinging, InteractionHand hand, SwingAnimation animation,
        boolean self, Operation<Void> original) {
        original.call(swinging, hand, animation, self);
        if (hand == InteractionHand.MAIN_HAND) WorldEvents.swung(player);
    }
    *///?} else {
    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void mcaddon$swung(ServerboundSwingPacket packet, CallbackInfo info) {
        if (packet.getHand() == InteractionHand.MAIN_HAND) WorldEvents.swung(player);
    }
    //?}
}
