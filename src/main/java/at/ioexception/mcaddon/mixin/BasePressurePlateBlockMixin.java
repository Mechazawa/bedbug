package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells a pack's script that a pressure plate went down or came back up.
 *
 * The game hands this method the signal the plate carried and works the new one out from what is
 * standing on it, so both halves of the change are available before anything is written back. An
 * entity is only there for the plate going down: it comes up on its own clock.
 */
@Mixin(BasePressurePlateBlock.class)
public abstract class BasePressurePlateBlockMixin {
    @Shadow
    protected abstract int getSignalStrength(Level level, BlockPos position);

    @Inject(method = "checkPressed", at = @At("HEAD"))
    private void mcaddon$pressed(Entity entity, Level level, BlockPos position, BlockState state,
        int signal, CallbackInfo info) {

        if (level.isClientSide()) return;

        WorldEvents.plateSignalChanged(level, position, entity, signal, getSignalStrength(level, position));
    }
}
