package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A bone built from a texture is drawn as the texels that texture draws, placed where the mesh and
 * its bone put it.
 *
 * `tp:vane`'s one bone pivots four pixels up and carries nothing but a texture mesh over an eight by
 * eight texture drawing an L: column 1 from row 1 to row 5, then row 5 to column 3. The mesh's
 * local pivot is the left edge of texel (1, 5) halfway through its layer, its position is two
 * pixels below the bone's pivot, and its rotation of -90 degrees about X stands it upright, so the
 * L reads the right way up with its layer across Z. The vane's animation then turns the bone half a
 * circle about Y and lifts it three pixels.
 *
 * In Bedrock's axes and in pixels from the feet, texel (u, v) is therefore drawn across X from -u to
 * 1 - u, across Y from 9 - v to 10 - v, and across Z from -0.5 to 0.5.
 */
public final class TextureMeshClientGameTest implements FabricClientGameTest {
    private static final Set<Texel> L = Set.of(new Texel(1, 1), new Texel(1, 2), new Texel(1, 3),
        new Texel(1, 4), new Texel(1, 5), new Texel(2, 5), new Texel(3, 5));
    private static final int TEXTURE_SIZE = 8;
    private static final float TOLERANCE = 0.01f;

    /** How far above the feet the renderer puts the model's origin, past the 24 pixels Bedrock's is at. */
    private static final float ORIGIN_LIFT = 0.001f;

    private record Texel(int u, int v) {
        boolean drawn() {
            return L.contains(this);
        }

        Texel step(int du, int dv) {
            return new Texel(u + du, v + dv);
        }
    }

    /** Which side of which texel a face covers: a broad face carries its Z, a side the axis it faces along. */
    private record Face(Texel texel, String side) {
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("summon tp:vane ~ ~ ~3 {NoAI:1b}");
            AddonEntity vane = Entities.waitForClient(context, "tp:vane");

            Submitted frame = Frames.waitFor(context, vane, drawn -> drawn.box() != null,
                "tp:vane's only bone is built from a texture mesh, and nothing was drawn for it.");
            float yaw = context.computeOnClient(client -> vane.yBodyRotO);
            List<Submitted.Vertex> vertices = frame.geometry().get(0).vertices();
            Assert.that(vertices.size() % 4 == 0, "tp:vane's mesh was drawn as " + vertices.size()
                + " vertices, which is not a whole number of quads.");

            Set<Face> expected = expectedFaces();
            Set<Face> found = new HashSet<>();
            for (int quad = 0; quad < vertices.size(); quad += 4) {
                List<Submitted.Vertex> corners = vertices.subList(quad, quad + 4);
                Face face = faceOf(corners.stream().map(vertex -> bedrock(vertex, yaw)).toList());
                Assert.that(found.add(face), "tp:vane's mesh drew " + face + " twice.");
                Assert.that(expected.contains(face), "tp:vane's mesh drew " + face
                    + ", which is not a face of its L: a broad face over a clear texel, a side between two"
                    + " drawn ones, or one out of place.");
                samples(face, corners);
            }
            Assert.that(found.equals(expected), () -> "tp:vane's mesh left out " + missing(expected, found) + ".");
        }
    }

    /** Both broad faces of every texel of the L, and a side wherever the texel beside it is clear. */
    private static Set<Face> expectedFaces() {
        Set<Face> faces = new HashSet<>();
        for (Texel texel : L) {
            faces.add(new Face(texel, "front"));
            faces.add(new Face(texel, "back"));
            if (!texel.step(-1, 0).drawn()) faces.add(new Face(texel, "left"));
            if (!texel.step(1, 0).drawn()) faces.add(new Face(texel, "right"));
            if (!texel.step(0, -1).drawn()) faces.add(new Face(texel, "top"));
            if (!texel.step(0, 1).drawn()) faces.add(new Face(texel, "bottom"));
        }
        return faces;
    }

    /**
     * The face four corners in Bedrock's pixels make, read back into the texel it belongs to.
     *
     * The bone's half turn puts a texel's left edge, as the texture is painted, at the larger X, and
     * its top edge is the higher one. A face lying across Z is a broad one, half a pixel to either
     * side of the layer's middle; one lying across X or Y is a side between two texels, named after
     * the edge of the drawn one it covers.
     */
    private static Face faceOf(List<Vector3f> corners) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        corners.forEach(corner -> {
            min.min(corner);
            max.max(corner);
        });
        Vector3f size = new Vector3f(max).sub(min);
        Vector3f centre = new Vector3f(min).add(max).mul(0.5f);

        if (size.z < TOLERANCE) {
            Assert.near(size.x, 1, TOLERANCE, "the width of a broad face of tp:vane's mesh, in pixels");
            Assert.near(size.y, 1, TOLERANCE, "the height of a broad face of tp:vane's mesh, in pixels");
            Assert.near(Math.abs(centre.z), 0.5, TOLERANCE,
                "how far a broad face of tp:vane's mesh is from the middle of its one pixel layer");
            return new Face(new Texel(Math.round(0.5f - centre.x), Math.round(9.5f - centre.y)),
                centre.z < 0 ? "front" : "back");
        }
        Assert.near(size.z, 1, TOLERANCE, "the depth of a side of tp:vane's mesh, which is its thickness in pixels");
        Assert.near(centre.z, 0, TOLERANCE, "the middle of a side of tp:vane's mesh along Z");
        if (size.x < TOLERANCE) {
            Texel before = new Texel(Math.round(-centre.x), Math.round(9.5f - centre.y));
            return before.drawn() ? new Face(before, "right") : new Face(before.step(1, 0), "left");
        }
        Texel above = new Texel(Math.round(0.5f - centre.x), Math.round(9 - centre.y));
        return above.drawn() ? new Face(above, "bottom") : new Face(above.step(0, 1), "top");
    }

    /** Every corner of a face samples the texel the face belongs to. */
    private static void samples(Face face, List<Submitted.Vertex> corners) {
        for (Submitted.Vertex corner : corners) {
            float u = corner.u() * TEXTURE_SIZE;
            float v = corner.v() * TEXTURE_SIZE;
            Assert.that(u >= face.texel().u() - TOLERANCE && u <= face.texel().u() + 1 + TOLERANCE
                    && v >= face.texel().v() - TOLERANCE && v <= face.texel().v() + 1 + TOLERANCE,
                () -> "tp:vane's " + face + " samples texel position " + u + ", " + v + " of its texture.");
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

    private static List<Face> missing(Set<Face> expected, Set<Face> found) {
        List<Face> missing = new ArrayList<>(expected);
        missing.removeAll(found);
        return missing;
    }
}
