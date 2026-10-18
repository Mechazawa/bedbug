package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Vector3f;

import java.util.List;
import java.util.TreeSet;

/**
 * A bone whose animation says `relative_to: {rotation: entity}` is turned in the entity's frame,
 * while the bones above it still carry it along.
 *
 * `tp:gaze` is a four pixel wide body eight pixels tall with a four pixel head on top, pivoting where
 * the two meet. Its one clip leans the body 45 degrees forward about X and turns the head 30 degrees
 * about Y relative to the entity. The head therefore sits where the leaning body put its pivot, eight
 * pixels out along the body at 45 degrees, but stands level, its top and bottom faces flat and its
 * sides turned 30 degrees about the vertical. A head turned in its parent's frame instead leans with
 * the body and has no two faces level.
 */
public final class RelativeRotationClientGameTest implements FabricClientGameTest {
    private static final float TOLERANCE = 0.01f;

    /** How far above the feet the renderer puts the model's origin, past the 24 pixels Bedrock's is at. */
    private static final float ORIGIN_LIFT = 0.001f;

    /** Six faces of four corners: the body's are submitted first and the head's after them. */
    private static final int CUBE_VERTICES = 24;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("summon tp:gaze ~ ~ ~3");
            AddonEntity gaze = Entities.waitForClient(context, "tp:gaze");
            world.getServer().runCommand("tick freeze");

            Submitted frame = Frames.waitFor(context, gaze, drawn -> !drawn.geometry().isEmpty()
                    && drawn.geometry().get(0).vertices().size() == 2 * CUBE_VERTICES,
                "tp:gaze was never drawn as its two cubes.");
            float yaw = context.computeOnClient(client -> gaze.yBodyRotO);
            List<Vector3f> head = frame.geometry().get(0).vertices()
                .subList(CUBE_VERTICES, 2 * CUBE_VERTICES).stream()
                .map(vertex -> bedrock(vertex, yaw)).toList();

            TreeSet<Float> heights = new TreeSet<>();
            head.forEach(corner -> heights.add(Math.round(corner.y / TOLERANCE) * TOLERANCE));
            Assert.that(heights.size() == 2, () -> "tp:gaze's head turned relative to the entity has corners at "
                + heights + " pixels up, so it leans with the body under it instead of standing level.");

            float pivotHeight = (float) (8 * Math.cos(Math.toRadians(45)));
            Assert.near(heights.first(), pivotHeight, 0.05,
                "the height of tp:gaze's head, in pixels, which the leaning body carries its pivot to");
            Assert.near(heights.last() - heights.first(), 4, 0.05, "the height of tp:gaze's head, in pixels");

            Vector3f edge = new Vector3f(head.get(1)).sub(head.get(0));
            if (Math.abs(edge.y) > TOLERANCE) edge = new Vector3f(head.get(2)).sub(head.get(1));
            double turn = Math.floorMod(Math.round(Math.toDegrees(Math.atan2(edge.z, edge.x))), 90);
            Assert.that(Math.abs(turn - 30) < 1 || Math.abs(turn - 60) < 1, () -> "tp:gaze's head is turned "
                + turn + " degrees off the entity's axes, where its clip turns it 30.");
            world.getServer().runCommand("tick unfreeze");
        }
    }

    /**
     * A drawn vertex in Bedrock's axes and pixels from the entity's feet.
     *
     * The renderer turns the model by 180 degrees less the body's rotation and flips X and Y, so
     * undoing the turn leaves X running the other way to Bedrock's and Y carrying the renderer's own
     * lift above the feet.
     */
    private static Vector3f bedrock(Submitted.Vertex vertex, float yaw) {
        Vector3f facing = new Vector3f(vertex.x(), vertex.y(), vertex.z())
            .rotateY((float) Math.toRadians(-(180 - yaw)));
        return new Vector3f(-facing.x * 16, (facing.y - ORIGIN_LIFT) * 16, facing.z * 16);
    }
}
