package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a pack's script that a player started breaking a block, and that they let go of it.
 *
 * Java reports the three moments of a break through one method and one action word, which is where
 * Bedrock's two events come from. A break that runs its course is already reported as a broken
 * block, so only the start and the giving up are raised here.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"))
    private void mcaddon$breaking(BlockPos position, ServerboundPlayerActionPacket.Action action,
        Direction face, int height, int sequence, CallbackInfo info) {

        if (player.level().isClientSide()) return;

        if (action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
            WorldEvents.startedBreaking(player, position, face);
            return;
        }
        if (action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
            WorldEvents.cancelledBreaking(player);
            return;
        }
        if (action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK) {
            WorldEvents.finishedBreaking(player);
        }
    }
}
