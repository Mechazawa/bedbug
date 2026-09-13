package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TripWireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells a pack's script that a tripwire was crossed or cleared.
 *
 * The game tells the hooks at each end of the line about it only once the line has changed, so that
 * call is the point the change happened at: the new state is in the world and everything standing in
 * the line is still where it was. 26 moved the body of the check into an overload carrying that
 * list, which is why the injector is written twice rather than once.
 */
@Mixin(TripWireBlock.class)
public abstract class TripWireBlockMixin {
    //? if >=26 {
    @Inject(method = "checkPressed(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
        + "Ljava/util/List;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/TripWireBlock;"
            + "updateSource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void mcaddon$tripped(Level level, BlockPos position, List<? extends Entity> found,
        CallbackInfo info) {

        if (level.isClientSide()) return;

        WorldEvents.tripWireTripped(level, position, mcaddon$powered(level, position),
            mcaddon$tripping(found));
    }
    //?} else {
    /*@Inject(method = "checkPressed(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/TripWireBlock;"
            + "updateSource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)V"))
    private void mcaddon$tripped(Level level, BlockPos position, CallbackInfo info) {
        if (level.isClientSide()) return;

        List<Entity> found = level.getEntities((Entity) null,
            level.getBlockState(position).getShape(level, position).bounds().move(position));
        WorldEvents.tripWireTripped(level, position, mcaddon$powered(level, position),
            mcaddon$tripping(found));
    }
    *///?}

    @Unique
    private static boolean mcaddon$powered(Level level, BlockPos position) {
        return level.getBlockState(position).getValue(TripWireBlock.POWERED);
    }

    /** What the game counts as standing in the line, which is anything a block may notice. */
    @Unique
    private static List<Entity> mcaddon$tripping(List<? extends Entity> found) {
        List<Entity> tripping = new ArrayList<>();
        for (Entity entity : found) {
            if (!entity.isIgnoringBlockTriggers()) tripping.add(entity);
        }
        return tripping;
    }
}
