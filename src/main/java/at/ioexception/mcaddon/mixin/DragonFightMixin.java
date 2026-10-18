package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.MobEvents;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves the End without a dragon while Bedrock's `minecraft:ender_dragon_event` is off.
 *
 * The fight makes a dragon here whenever it finds none, which is how the End gets its first one. A
 * dragon already there is still found and followed, and one a player summons back with end crystals
 * is made elsewhere, so only the event's own spawn is held back. 26 renamed the fight.
 */
//? if >=26 {
@Mixin(net.minecraft.world.level.dimension.end.EnderDragonFight.class)
//?} else {
/*@Mixin(net.minecraft.world.level.dimension.end.EndDragonFight.class)
*///?}
public abstract class DragonFightMixin {
    @Shadow
    private ServerLevel level;

    @Inject(method = "findOrCreateDragon", at = @At("HEAD"), cancellable = true)
    private void mcaddon$withoutTheEvent(CallbackInfo info) {
        if (!MobEvents.dragonSpawns() && level.getDragons().isEmpty()) info.cancel();
    }
}
