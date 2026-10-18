package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.bedrock.camera.CameraPlacement;
import at.ioexception.mcaddon.client.render.PlayerCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets `/camera` and `/camerashake` move the camera the game has just set up for a frame.
 *
 * The camera works out where it belongs once a frame and the last thing it does is write the
 * position and the rotation, so the end of that call is where a view the server set can be applied
 * without fighting the game for it. 26 renamed that call from `setup` to `update` and dropped the
 * arguments it used to take, which is why the injector carries two bodies.
 *
 * The setters are the game's own, so the forward vectors, the rotation quaternion and the matrices
 * derived from them all follow, which writing the fields would leave stale. A camera placed this way
 * counts as set up, which is what lets a spare one stand in for the player's ears.
 */
@Mixin(Camera.class)
public abstract class CameraMixin implements PlayerCamera.Adjustable {
    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    private boolean detached;

    @Shadow
    private boolean initialized;

    //? if >=26 {
    @Inject(method = "update", at = @At("RETURN"))
    private void mcaddon$applyAddonView(net.minecraft.client.DeltaTracker delta, CallbackInfo info) {
        PlayerCamera.apply(this, delta.getGameTimeDeltaPartialTick(true));
    }
    //?} else {
    /*@Inject(method = "setup", at = @At("RETURN"))
    private void mcaddon$applyAddonView(net.minecraft.world.level.BlockGetter level,
        net.minecraft.world.entity.Entity entity, boolean thirdPerson, boolean reversed,
        float partialTick, CallbackInfo info) {
        PlayerCamera.apply(this, partialTick);
    }
    *///?}

    @Override
    public CameraPlacement mcaddon$placement() {
        Camera camera = (Camera) (Object) this;
        //? if >=26 {
        return new CameraPlacement(camera.position(), camera.xRot(), camera.yRot(), detached);
        //?} else {
        /*return new CameraPlacement(camera.getPosition(), camera.getXRot(), camera.getYRot(), detached);
        *///?}
    }

    @Override
    public void mcaddon$placeAt(CameraPlacement placement) {
        setPosition(placement.position());
        setRotation(placement.yRot(), placement.xRot());
        detached = placement.detached();
        initialized = true;
    }
}
