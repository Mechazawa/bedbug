package at.ioexception.mcaddon.bedrock.model;

import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Bedrock model into quads in Java Edition model space.
 *
 * The two formats differ in exactly one way that matters: Bedrock measures Y upward from the
 * entity's feet, Java measures it downward from a point 24 pixels above them. Everything else,
 * including the box texture layout, is the same, which is why a vanilla villager and this pack's
 * villager come out identical when converted.
 */
public final class GeometryBaker {
    /** Java model space puts its origin this far above the feet, with Y increasing downward. */
    private static final float MODEL_ORIGIN_HEIGHT = 24f;

    /** The six faces of a cube, named by which end of an axis they sit on. */
    private enum Face {
        MIN_X(new Vector3f(-1, 0, 0)),
        MAX_X(new Vector3f(1, 0, 0)),
        MIN_Y(new Vector3f(0, -1, 0)),
        MAX_Y(new Vector3f(0, 1, 0)),
        MIN_Z(new Vector3f(0, 0, -1)),
        MAX_Z(new Vector3f(0, 0, 1));

        final Vector3f normal;

        Face(Vector3f normal) {
            this.normal = normal;
        }
    }

    private GeometryBaker() {
    }

    /**
     * Which geometric face a Bedrock face name textures.
     *
     * Bedrock names a face by the axis direction it points along: east is +X, up is +Y, north is
     * -Z. Only the Y pair swaps on the way over, because that is the axis this conversion flips.
     */
    private static Face faceOf(Direction bedrockName) {
        return switch (bedrockName) {
            case UP -> Face.MIN_Y;
            case DOWN -> Face.MAX_Y;
            case NORTH -> Face.MIN_Z;
            case SOUTH -> Face.MAX_Z;
            case EAST -> Face.MAX_X;
            case WEST -> Face.MIN_X;
        };
    }

    public static BakedGeometry bake(BedrockGeometry geometry) {
        Map<String, List<BedrockBone>> childrenOf = new HashMap<>();
        List<BedrockBone> roots = new ArrayList<>();

        for (BedrockBone bone : geometry.bones()) {
            if (bone.parent() == null) roots.add(bone);
            else childrenOf.computeIfAbsent(bone.parent(), name -> new ArrayList<>()).add(bone);
        }

        Map<String, BakedBone> byName = new HashMap<>();
        List<BakedBone> bakedRoots = new ArrayList<>();
        for (BedrockBone root : roots) {
            bakedRoots.add(bakeBone(root, geometry, childrenOf, byName, new Vector3f()));
        }

        return new BakedGeometry(List.copyOf(bakedRoots), Map.copyOf(byName));
    }

    private static BakedBone bakeBone(
        BedrockBone bone,
        BedrockGeometry geometry,
        Map<String, List<BedrockBone>> childrenOf,
        Map<String, BakedBone> byName,
        Vector3f parentPivot
    ) {
        Vector3f pivot = toModelSpace(bone.pivot());

        List<BakedQuad> quads = new ArrayList<>();
        if (!bone.neverRender()) {
            for (BedrockCube cube : bone.cubes()) quads.addAll(bakeCube(cube, pivot, geometry));
        }

        List<BakedBone> children = new ArrayList<>();
        for (BedrockBone child : childrenOf.getOrDefault(bone.name(), List.of())) {
            children.add(bakeBone(child, geometry, childrenOf, byName, pivot));
        }

        // A Java model part is positioned relative to its parent, so the absolute pivot becomes an offset.
        BakedBone baked = new BakedBone(
            bone.name(),
            new Vector3f(pivot).sub(parentPivot),
            toModelRotation(bone.rotation()),
            bone.binding(),
            List.copyOf(quads),
            List.copyOf(children));
        byName.put(bone.name(), baked);
        return baked;
    }

    private static List<BakedQuad> bakeCube(BedrockCube cube, Vector3f bonePivot, BedrockGeometry geometry) {
        float inflate = cube.inflate();
        float minX = cube.origin().x - inflate;
        float maxX = cube.origin().x + cube.size().x + inflate;
        float minZ = cube.origin().z - inflate;
        float maxZ = cube.origin().z + cube.size().z + inflate;

        // The Y axis flips, so the Bedrock top edge becomes the Java minimum.
        float minY = MODEL_ORIGIN_HEIGHT - (cube.origin().y + cube.size().y + inflate);
        float maxY = MODEL_ORIGIN_HEIGHT - (cube.origin().y - inflate);

        boolean mirror = cube.mirror();
        if (mirror) {
            float swap = minX;
            minX = maxX;
            maxX = swap;
        }

        Vector3f[] corners = corners(minX, minY, minZ, maxX, maxY, maxZ);
        Quaternionf cubeRotation = null;
        if (cube.isRotated()) {
            Vector3f cubePivot = toModelSpace(cube.pivot());
            Vector3f angles = toModelRotation(cube.rotation());
            cubeRotation = new Quaternionf().rotationZYX(angles.z, angles.y, angles.x);
            for (Vector3f corner : corners) {
                corner.sub(cubePivot).rotate(cubeRotation).add(cubePivot);
            }
        }
        for (Vector3f corner : corners) corner.sub(bonePivot);

        Map<Face, float[]> rectangles = rectangles(cube, geometry);
        List<BakedQuad> quads = new ArrayList<>();
        for (Map.Entry<Face, float[]> entry : rectangles.entrySet()) {
            quads.add(quad(entry.getKey(), entry.getValue(), corners, geometry, mirror, cubeRotation));
        }
        return quads;
    }

    /**
     * The eight corners, ordered so an index reads as a bit pattern: bit 0 is X, bit 1 is Y,
     * bit 2 is Z, each set meaning the maximum end of that axis.
     */
    private static Vector3f[] corners(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        Vector3f[] corners = new Vector3f[8];
        for (int index = 0; index < 8; index++) {
            corners[index] = new Vector3f(
                (index & 1) == 0 ? minX : maxX,
                (index & 2) == 0 ? minY : maxY,
                (index & 4) == 0 ? minZ : maxZ);
        }
        return corners;
    }

    /**
     * The four corners of a face, wound so the texture's top left lands on the first vertex, which
     * is the order the UV rectangle is then applied in.
     */
    private static int[] cornerIndices(Face face) {
        return switch (face) {
            case MIN_Y -> new int[]{5, 4, 0, 1};
            case MAX_Y -> new int[]{3, 2, 6, 7};
            case MAX_X -> new int[]{5, 1, 3, 7};
            case MIN_X -> new int[]{0, 4, 6, 2};
            case MIN_Z -> new int[]{1, 0, 2, 3};
            case MAX_Z -> new int[]{4, 5, 7, 6};
        };
    }

    private static BakedQuad quad(
        Face face, float[] rectangle, Vector3f[] corners, BedrockGeometry geometry,
        boolean mirror, Quaternionf cubeRotation
    ) {
        int[] indices = cornerIndices(face);
        float texWidth = geometry.textureWidth();
        float texHeight = geometry.textureHeight();

        float uMin = rectangle[0] / texWidth;
        float vMin = rectangle[1] / texHeight;
        float uMax = rectangle[2] / texWidth;
        float vMax = rectangle[3] / texHeight;

        // The winding above puts the texture's top right on the first vertex, as vanilla does.
        float[][] coordinates = {{uMax, vMin}, {uMin, vMin}, {uMin, vMax}, {uMax, vMax}};
        rotateCoordinates(coordinates, (int) rectangle[4]);

        BakedVertex[] vertices = new BakedVertex[4];
        for (int corner = 0; corner < 4; corner++) {
            Vector3f position = corners[indices[corner]];
            vertices[corner] = new BakedVertex(
                position.x, position.y, position.z, coordinates[corner][0], coordinates[corner][1]);
        }
        if (mirror) reverse(vertices);

        Vector3f normal = new Vector3f(face.normal);
        if (mirror) normal.mul(face == Face.MIN_X || face == Face.MAX_X ? -1 : 1);
        if (cubeRotation != null) normal.rotate(cubeRotation);

        return new BakedQuad(vertices, normal);
    }

    /** A face may declare its texture turned in ninety degree steps. */
    private static void rotateCoordinates(float[][] coordinates, int degrees) {
        int steps = Math.floorMod(degrees / 90, 4);
        for (int step = 0; step < steps; step++) {
            float[] first = coordinates[0];
            coordinates[0] = coordinates[1];
            coordinates[1] = coordinates[2];
            coordinates[2] = coordinates[3];
            coordinates[3] = first;
        }
    }

    private static void reverse(BakedVertex[] vertices) {
        for (int index = 0; index < vertices.length / 2; index++) {
            BakedVertex swap = vertices[index];
            vertices[index] = vertices[vertices.length - 1 - index];
            vertices[vertices.length - 1 - index] = swap;
        }
    }

    /** Each face's texture rectangle as {uMin, vMin, uMax, vMax, rotation}. */
    private static Map<Face, float[]> rectangles(BedrockCube cube, BedrockGeometry geometry) {
        Map<Face, float[]> rectangles = new HashMap<>();

        if (cube.uv() instanceof CubeUv.PerFace perFace) {
            for (Map.Entry<Direction, FaceUv> entry : perFace.faces().entrySet()) {
                FaceUv uv = entry.getValue();
                // Bedrock leaves out a face whose patch has no area, which is how a plane is drawn.
                if (uv.width() == 0 || uv.height() == 0) continue;
                rectangles.put(faceOf(entry.getKey()), new float[]{
                    uv.u(), uv.v(), uv.u() + uv.width(), uv.v() + uv.height(), uv.rotation()});
            }
            return rectangles;
        }

        CubeUv.Box box = (CubeUv.Box) cube.uv();
        float width = cube.size().x;
        float height = cube.size().y;
        float depth = cube.size().z;
        float u = box.u();
        float v = box.v();

        rectangles.put(Face.MIN_Y, new float[]{u + depth, v, u + depth + width, v + depth, 0});
        // The bottom face is laid out upside down, which is a quirk of the vanilla box unwrap.
        rectangles.put(Face.MAX_Y, new float[]{u + depth + width, v + depth, u + depth + width * 2, v, 0});
        rectangles.put(Face.MIN_X, new float[]{u, v + depth, u + depth, v + depth + height, 0});
        rectangles.put(Face.MIN_Z, new float[]{u + depth, v + depth, u + depth + width, v + depth + height, 0});
        rectangles.put(Face.MAX_X,
            new float[]{u + depth + width, v + depth, u + depth * 2 + width, v + depth + height, 0});
        rectangles.put(Face.MAX_Z, new float[]{
            u + depth * 2 + width, v + depth, u + depth * 2 + width * 2, v + depth + height, 0});
        return rectangles;
    }

    private static Vector3f toModelSpace(Vector3f bedrock) {
        return new Vector3f(bedrock.x, MODEL_ORIGIN_HEIGHT - bedrock.y, bedrock.z);
    }

    /**
     * Bedrock states rotations in degrees with the opposite sign convention to Java. Combined with
     * the flipped Y axis, X and Z come through unchanged and only Y turns the other way.
     */
    private static Vector3f toModelRotation(Vector3f degrees) {
        return new Vector3f(
            (float) Math.toRadians(degrees.x),
            (float) Math.toRadians(-degrees.y),
            (float) Math.toRadians(degrees.z));
    }
}
