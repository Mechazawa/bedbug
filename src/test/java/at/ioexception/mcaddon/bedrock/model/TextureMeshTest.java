package at.ioexception.mcaddon.bedrock.model;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A texture mesh baked from a grid of texels: an L drawn on a clear eight by eight field, standing
 * one texel wide in column 1 from row 1 to row 5 and running along row 5 to column 3.
 *
 * With nothing placing it, a texel's column is model X, its row is model Z and its layer runs from
 * 0 to 1 along model Y.
 */
class TextureMeshTest {
    private static final int SIZE = 8;
    private static final Set<List<Integer>> L = Set.of(
        List.of(1, 1), List.of(1, 2), List.of(1, 3), List.of(1, 4), List.of(1, 5), List.of(2, 5), List.of(3, 5));
    private static final float TOLERANCE = 1e-4f;

    @Test
    void drawsBothBroadFacesOfEveryDrawnTexelAndNothingOverAClearOne() {
        List<BakedQuad> broad = bake(mesh(new Vector3f(), new Vector3f(), new Vector3f(), true)).stream()
            .filter(quad -> Math.abs(quad.normal().y) > 0.5f).toList();

        assertEquals(2 * L.size(), broad.size());
        Set<List<Object>> faces = new HashSet<>();
        for (BakedQuad quad : broad) {
            Vector3f centre = centre(quad);
            faces.add(List.of(List.of((int) Math.floor(centre.x), (int) Math.floor(centre.z)), quad.normal().y > 0));
        }
        Set<List<Object>> expected = new HashSet<>();
        L.forEach(texel -> {
            expected.add(List.of(texel, true));
            expected.add(List.of(texel, false));
        });
        assertEquals(expected, faces);
    }

    @Test
    void putsASideOnlyWhereADrawnTexelMeetsAClearOne() {
        List<BakedQuad> sides = bake(mesh(new Vector3f(), new Vector3f(), new Vector3f(), true)).stream()
            .filter(quad -> Math.abs(quad.normal().y) < 0.5f).toList();

        int borders = 0;
        for (List<Integer> texel : L) {
            for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                if (!L.contains(List.of(texel.get(0) + step[0], texel.get(1) + step[1]))) borders++;
            }
        }
        assertEquals(borders, sides.size());
        for (BakedQuad quad : sides) {
            Vector3f centre = centre(quad);
            int column = (int) Math.floor(centre.x - quad.normal().x * 0.5f);
            int row = (int) Math.floor(centre.z - quad.normal().z * 0.5f);
            assertTrue(L.contains(List.of(column, row)), "a side faces out of texel " + column + "," + row);
            assertFalse(L.contains(List.of(column + Math.round(quad.normal().x), row + Math.round(quad.normal().z))),
                "a side of texel " + column + "," + row + " faces a drawn texel");
        }
    }

    @Test
    void samplesTheTexelEachFaceStandsFor() {
        for (BakedQuad quad : bake(mesh(new Vector3f(), new Vector3f(), new Vector3f(), true))) {
            Vector3f centre = centre(quad);
            int column = (int) Math.floor(centre.x - quad.normal().x * 0.5f);
            int row = (int) Math.floor(centre.z - quad.normal().z * 0.5f);
            for (BakedVertex vertex : quad.vertices()) {
                float u = vertex.u() * SIZE;
                float v = vertex.v() * SIZE;
                assertTrue(u >= column && u <= column + 1 && v >= row && v <= row + 1,
                    "a face of texel " + column + "," + row + " samples " + u + "," + v);
            }
        }
    }

    @Test
    void isOneUnitDeepOrAsDeepAsATexelIsWide() {
        List<BakedQuad> layer = bake(mesh(new Vector3f(), new Vector3f(), new Vector3f(), true));
        assertEquals(1f, extent(layer).y, TOLERANCE);

        BedrockGeometry halfSized = new BedrockGeometry("geometry.test", SIZE / 2, SIZE / 2, null, List.of(), null);
        List<BakedQuad> cubic = GeometryBaker.bakeTextureMesh(
            mesh(new Vector3f(), new Vector3f(), new Vector3f(), false), mask(), new Vector3f(), halfSized);
        assertEquals(0.5f, extent(cubic).y, TOLERANCE);
        assertEquals(1.5f, extent(cubic).x, TOLERANCE);
    }

    /**
     * The local pivot lands on the position, whose X and Z are measured from the model's origin and
     * whose Y runs down from the bone's pivot. The quads are kept relative to the bone's pivot, as a
     * cube's are, so a bone pivoting one unit along X draws the mesh one unit nearer.
     */
    @Test
    void movesTheLocalPivotOntoThePosition() {
        BedrockTextureMesh mesh = mesh(new Vector3f(2, 3, 4), new Vector3f(1, 0.5f, 5), new Vector3f(), true);
        List<BakedQuad> quads = GeometryBaker.bakeTextureMesh(mesh, mask(), new Vector3f(1, 14, 0),
            new BedrockGeometry("geometry.test", SIZE, SIZE, null, List.of(), null));

        assertEquals(new Vector3f(1, 2.5f, 0), min(quads));
    }

    /**
     * Turned -90 degrees about X the way a cube is, the texture stands up: its columns still run
     * along X, its rows run down the model, which is Java's +Y, and its layer runs along -Z.
     */
    @Test
    void turnsAboutTheLocalPivotTheWayACubeTurns() {
        BedrockTextureMesh mesh = mesh(new Vector3f(), new Vector3f(1, 0.5f, 5), new Vector3f(-90, 0, 0), true);
        List<BakedQuad> quads = bake(mesh);

        Vector3f min = min(quads);
        Vector3f extent = extent(quads);
        assertEquals(0f, min.x, TOLERANCE);
        assertEquals(-4f, min.y, TOLERANCE);
        assertEquals(-0.5f, min.z, TOLERANCE);
        assertEquals(new Vector3f(3, 5, 1).toString(), round(extent).toString());
    }

    private static List<BakedQuad> bake(BedrockTextureMesh mesh) {
        return GeometryBaker.bakeTextureMesh(mesh, mask(), new Vector3f(),
            new BedrockGeometry("geometry.test", SIZE, SIZE, null, List.of(), null));
    }

    private static BedrockTextureMesh mesh(Vector3f position, Vector3f localPivot, Vector3f rotation,
        boolean pixelDepth) {
        return new BedrockTextureMesh("default", position, localPivot, rotation, new Vector3f(1), pixelDepth);
    }

    private static TexelMask mask() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        L.forEach(texel -> image.setRGB(texel.get(0), texel.get(1), 0xFF336699));
        return TexelMask.of(image);
    }

    private static Vector3f centre(BakedQuad quad) {
        Vector3f centre = new Vector3f();
        for (BakedVertex vertex : quad.vertices()) centre.add(vertex.x() / 4, vertex.y() / 4, vertex.z() / 4);
        return centre;
    }

    private static Vector3f min(List<BakedQuad> quads) {
        Vector3f min = new Vector3f(Float.MAX_VALUE);
        quads.forEach(quad -> {
            for (BakedVertex vertex : quad.vertices()) min.min(new Vector3f(vertex.x(), vertex.y(), vertex.z()));
        });
        return round(min);
    }

    private static Vector3f extent(List<BakedQuad> quads) {
        Vector3f max = new Vector3f(-Float.MAX_VALUE);
        quads.forEach(quad -> {
            for (BakedVertex vertex : quad.vertices()) max.max(new Vector3f(vertex.x(), vertex.y(), vertex.z()));
        });
        return max.sub(min(quads));
    }

    /** Rounded to a thousandth, so a turn that leaves -0 or a stray digit compares as the value it is. */
    private static Vector3f round(Vector3f vector) {
        return new Vector3f(Math.round(vector.x * 1000) / 1000f + 0f, Math.round(vector.y * 1000) / 1000f + 0f,
            Math.round(vector.z * 1000) / 1000f + 0f);
    }
}
