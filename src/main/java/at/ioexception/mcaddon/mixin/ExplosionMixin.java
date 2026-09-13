package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.server.WorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Tells a pack's script about an explosion, and lets it call one off.
 *
 * The blocks are reported at the point the game has worked out which ones it will take and before it
 * has touched anything, so a listener sees the world as it still stands and a refusal costs the
 * explosion its damage as well as its blocks. 26 moved the whole of the work out of `Explosion` and
 * into `ServerExplosion`, which is why this carries two bodies.
 */
//? if >=26 {
@Mixin(net.minecraft.world.level.ServerExplosion.class)
public abstract class ExplosionMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private Entity source;

    @Unique
    private boolean mcaddonRefused;

    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
    private void mcaddon$exploding(CallbackInfoReturnable<List<BlockPos>> info) {
        mcaddonRefused = WorldEvents.explosionRefused(level, source, info.getReturnValue());
        if (mcaddonRefused) info.getReturnValue().clear();
    }

    @Inject(method = "hurtEntities", at = @At("HEAD"), cancellable = true)
    private void mcaddon$hurting(CallbackInfo info) {
        if (mcaddonRefused) info.cancel();
    }
}
//?} else {
/*@Mixin(net.minecraft.world.level.Explosion.class)
public abstract class ExplosionMixin {
    @Shadow
    @Final
    private net.minecraft.world.level.Level level;

    @Shadow
    @Final
    private Entity source;

    @Shadow
    public abstract List<BlockPos> getToBlow();

    @Inject(method = "explode",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntities("
            + "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"),
        cancellable = true)
    private void mcaddon$exploding(CallbackInfo info) {
        if (level.isClientSide()) return;

        if (WorldEvents.explosionRefused(level, source, getToBlow())) {
            getToBlow().clear();
            info.cancel();
        }
    }
}
*///?}
