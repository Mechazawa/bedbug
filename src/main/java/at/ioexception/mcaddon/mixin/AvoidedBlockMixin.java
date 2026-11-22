package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.entity.AddonEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Closes a node to an add-on entity whose navigation lists a block there in `blocks_to_avoid`.
 *
 * The level caches a block's path type for every mob alike, so the list is asked where the type
 * is worked out for one mob.
 */
@Mixin({WalkNodeEvaluator.class, SwimNodeEvaluator.class})
public abstract class AvoidedBlockMixin {
    @Inject(method = "getPathTypeOfMob", at = @At("RETURN"), cancellable = true)
    private void mcaddon$avoidBlocks(PathfindingContext context, int x, int y, int z, Mob mob,
        CallbackInfoReturnable<PathType> answer) {
        if (mob instanceof AddonEntity entity && entity.avoidsNode(context.level(), x, y, z)) {
            answer.setReturnValue(PathType.BLOCKED);
        }
    }
}
