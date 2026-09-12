package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells a pack's script that a button went down, and who pushed it. */
@Mixin(ButtonBlock.class)
public abstract class ButtonBlockMixin {
    @Inject(method = "press", at = @At("HEAD"), require = 0)
    private void mcaddon$pushed(BlockState state, Level level, BlockPos position, Player player,
        CallbackInfo info) {
        if (level.isClientSide()) return;

        WorldEvents.buttonPushed(level, position, player);
    }
}
