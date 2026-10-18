package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.block.BorderColumns;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forgets a chunk's border columns when a border block is set or cleared in it, on either side. */
@Mixin(LevelChunk.class)
public abstract class BorderChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void mcaddon$forgetBorderColumns(CallbackInfoReturnable<BlockState> info,
        @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) BlockState state) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (BorderColumns.is(state) || BorderColumns.is(chunk.getBlockState(pos))) BorderColumns.forget(chunk);
    }
}
