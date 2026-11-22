package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Offers every game event to the entities whose pack asked to remember one.
 *
 * `minecraft:memory_sensor.game_event` needs a feed of what happens anywhere in the world, and a
 * follower minding vibrations needs the note blocks played, and `ServerLevel.gameEvent` is where the
 * whole game funnels one. The
 * first parameter is a holder from 1.21 and the event itself before it, which is the only
 * difference between the two bodies.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelGameEventMixin {
    //? if >=1.21 {
    @Inject(method = "gameEvent", at = @At("HEAD"))
    private void mcaddon$rememberEvent(net.minecraft.core.Holder<GameEvent> event, Vec3 where,
        GameEvent.Context context, CallbackInfo info) {
        AddonEntity.rememberGameEvent((ServerLevel) (Object) this, where, event.is(GameEvent.NOTE_BLOCK_PLAY));
    }
    //?} else {
    /*@Inject(method = "gameEvent", at = @At("HEAD"))
    private void mcaddon$rememberEvent(GameEvent event, Vec3 where, GameEvent.Context context,
        CallbackInfo info) {
        AddonEntity.rememberGameEvent((ServerLevel) (Object) this, where, event == GameEvent.NOTE_BLOCK_PLAY);
    }
    *///?}
}
