package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.PlayerLimit;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
//? if >=26 {
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else {
/*import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
*///?}

/**
 * The player limit, set for one session by Bedrock's `setmaxplayers`.
 *
 * Before 26 the player list holds the limit in a field it reads when a player logs in, which the
 * server's properties fill as it starts, so writing the field lasts until the server stops. From 26
 * the limit is a server property the list asks for, and writing that property writes the file, so
 * the list answers with a limit of its own instead while one is set.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListLimitMixin implements PlayerLimit {
    //? if >=26 {
    @Unique
    private Integer sessionLimit;

    @Override
    public void mcaddon$limitPlayers(int limit) {
        sessionLimit = limit;
    }

    @Inject(method = "getMaxPlayers", at = @At("HEAD"), cancellable = true)
    private void mcaddon$limited(CallbackInfoReturnable<Integer> info) {
        if (sessionLimit != null) info.setReturnValue(sessionLimit);
    }
    //?} else {
    /*@Shadow
    @Mutable
    @Final
    protected int maxPlayers;

    @Override
    public void mcaddon$limitPlayers(int limit) {
        maxPlayers = limit;
    }
    *///?}
}
