package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.registry.AddonItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells the server that an attack met nothing, so that it can play the `attack_miss` sound of the
 * held item's `minecraft:swing_sounds` to everyone near.
 *
 * Java sends nothing for such a swing but the swing itself, which every use of a hand sends as
 * well; only the client knows the attack found no target. The attack strength is reset on that
 * branch alone.
 */
@Mixin(Minecraft.class)
public abstract class AttackMissMixin {
    @Shadow
    public LocalPlayer player;

    @Inject(method = "startAttack",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;resetAttackStrengthTicker()V"))
    private void mcaddon$missed(CallbackInfoReturnable<Boolean> info) {
        if (AddonItems.swingSoundsOf(player.getMainHandItem().getItem()) != null) Channels.sendSwingMissed();
    }
}
