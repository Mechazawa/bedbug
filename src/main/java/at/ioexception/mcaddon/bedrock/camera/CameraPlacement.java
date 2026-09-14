package at.ioexception.mcaddon.bedrock.camera;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where one frame's camera sits and which way it looks.
 *
 * `detached` is the game's own word for a camera that is away from the player's eyes, which is what
 * decides whether the player's own body is drawn.
 */
public record CameraPlacement(Vec3 position, float xRot, float yRot, boolean detached) {
    /**
     * Part of the way from here to another placement.
     *
     * The yaw takes the short way round, so a turn from 350 to 10 passes through zero rather than
     * spinning the long way. A blend counts as detached whenever either end is, because the camera
     * is away from the eyes for every frame in between.
     */
    public CameraPlacement blend(CameraPlacement to, float progress) {
        return new CameraPlacement(
            new Vec3(position.x + (to.position().x - position.x) * progress,
                position.y + (to.position().y - position.y) * progress,
                position.z + (to.position().z - position.z) * progress),
            xRot + Mth.wrapDegrees(to.xRot() - xRot) * progress,
            yRot + Mth.wrapDegrees(to.yRot() - yRot) * progress,
            detached || to.detached());
    }
}
