package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.AddonEntityRenderer;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * A model reaching past its entity's box stays drawn while the part outside the box is in view.
 *
 * `tp:sprite`'s geometry declares a visible box two blocks wide about its middle, and its entity box
 * is 0.6 wide, which Java widens by half a block on each side before asking the frustum. A camera
 * five blocks off looking along a line 0.95 blocks to the side of the sprite, through a view one
 * degree wide, sees part of the declared box and none of the widened entity box. `tp:wisp` declares
 * no visible box, so the same view of it draws nothing.
 */
public final class CullingClientGameTest implements FabricClientGameTest {
    private static final double SIDEWAYS = 0.95;
    private static final double UP = 0.5;
    private static final double BACK = 5;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("summon tp:sprite ~ ~ ~3 {NoAI:1b}");
            world.getServer().runCommand("summon tp:wisp ~6 ~ ~3 {NoAI:1b}");
            AddonEntity sprite = Entities.waitForClient(context, "tp:sprite");
            AddonEntity wisp = Entities.waitForClient(context, "tp:wisp");

            Assert.that(!context.computeOnClient(client -> boxInView(sprite)), "The view meant to miss tp:sprite's"
                + " entity box sees it, so it cannot say anything about the declared one.");
            Assert.that(context.computeOnClient(client -> drawn(sprite)), "tp:sprite's geometry reaches into view"
                + " past its entity box, and its renderer culled it.");
            Assert.that(!context.computeOnClient(client -> drawn(wisp)), "tp:wisp's geometry declares no visible"
                + " box and its entity box is out of view, and its renderer drew it anyway.");
        }
    }

    private static boolean drawn(AddonEntity entity) {
        Vec3 camera = cameraFor(entity);
        AddonEntityRenderer renderer = (AddonEntityRenderer) Minecraft.getInstance()
            .getEntityRenderDispatcher().getRenderer(entity);
        return renderer.shouldRender(entity, frustum(camera), camera.x, camera.y, camera.z);
    }

    private static boolean boxInView(AddonEntity entity) {
        return frustum(cameraFor(entity)).isVisible(entity.getBoundingBox().inflate(0.5));
    }

    private static Vec3 cameraFor(AddonEntity entity) {
        return entity.position().add(SIDEWAYS, UP, -BACK);
    }

    /** A view one degree wide from the camera, looking along the world's positive z. */
    private static Frustum frustum(Vec3 camera) {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(1), 1f, 0.05f, 100f);
        Frustum frustum = new Frustum(new Matrix4f().rotateY((float) Math.PI), projection);
        frustum.prepare(camera.x, camera.y, camera.z);
        return frustum;
    }
}
