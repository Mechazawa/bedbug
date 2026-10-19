package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.entity.Travelled;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts how far an entity went across the ground, from where it stands against where it stood a
 * tick before.
 *
 * Java counts the same in `moveDist`, but only inside `Entity.move`, which neither an entity the
 * server teleports nor a client's copy of another entity ever runs. The level asks every entity to
 * remember where it stood once a tick, just before it ticks, so the step taken since the last such
 * moment is counted there.
 */
@Mixin(Entity.class)
public abstract class EntityTravelMixin implements Travelled {
    /** Java's own measure for `moveDist`: six tenths of a block for each block walked. */
    @Unique
    private static final float STRIDE = 0.6f;

    @Shadow
    public double xOld;

    @Shadow
    public double zOld;

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getZ();

    @Unique
    private float mcaddonTravelled;

    @Shadow
    public int tickCount;

    /**
     * Nothing is counted before the entity's first tick, because its old position is not a place it
     * stood until then. An entity is made at the origin and placed afterwards: the server's
     * `snapTo` counts that placement as a step, and a client's copy is placed with `absSnapTo`,
     * which leaves the old position at the origin unless a frame drew it before its first tick.
     */
    @Inject(method = "setOldPosAndRot()V", at = @At("HEAD"))
    private void mcaddon$countStep(CallbackInfo info) {
        if (tickCount == 0) return;

        mcaddonTravelled += (float) Math.hypot(getX() - xOld, getZ() - zOld) * STRIDE;
    }

    @Override
    public float mcaddon$travelled() {
        return mcaddonTravelled;
    }
}
