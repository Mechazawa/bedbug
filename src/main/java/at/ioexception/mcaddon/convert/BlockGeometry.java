package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.bedrock.model.BedrockBone;
import at.ioexception.mcaddon.bedrock.model.BedrockCube;
import at.ioexception.mcaddon.bedrock.model.BedrockGeometry;
import at.ioexception.mcaddon.bedrock.model.CubeUv;
import at.ioexception.mcaddon.bedrock.model.FaceUv;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

import java.util.Map;

/**
 * A Bedrock block geometry, rewritten as a Java block model.
 *
 * These two formats agree where the entity formats do not: both measure in sixteenths of a block
 * with Y running up, so a cube's corners carry across unchanged. What differs is the texture
 * coordinates, which Bedrock writes in texture pixels and Java writes in sixteenths.
 */
final class BlockGeometry {
    private BlockGeometry() {
    }

    /** Null when the geometry has nothing to draw, which is how a pack writes an invisible block. */
    static JsonArray elements(BedrockGeometry geometry, String textureVariable) {
        JsonArray elements = new JsonArray();
        for (BedrockBone bone : geometry.bones()) {
            for (BedrockCube cube : bone.cubes()) {
                elements.add(element(cube, geometry, textureVariable));
            }
        }
        return elements.isEmpty() ? null : elements;
    }

    private static JsonObject element(BedrockCube cube, BedrockGeometry geometry, String texture) {
        Vector3f origin = cube.origin();
        Vector3f size = cube.size();
        float inflate = cube.inflate();

        JsonObject element = new JsonObject();
        element.add("from", triple(origin.x - inflate, origin.y - inflate, origin.z - inflate));
        element.add("to", triple(origin.x + size.x + inflate, origin.y + size.y + inflate,
            origin.z + size.z + inflate));

        if (cube.isRotated()) {
            JsonObject rotation = new JsonObject();
            rotation.add("origin", triple(cube.pivot().x, cube.pivot().y, cube.pivot().z));
            // A Java model rotates about one axis at a time, so the largest angle is the one kept.
            float x = Math.abs(cube.rotation().x);
            float y = Math.abs(cube.rotation().y);
            float z = Math.abs(cube.rotation().z);
            if (x >= y && x >= z) {
                rotation.addProperty("axis", "x");
                rotation.addProperty("angle", quantise(cube.rotation().x));
            } else if (y >= z) {
                rotation.addProperty("axis", "y");
                rotation.addProperty("angle", quantise(cube.rotation().y));
            } else {
                rotation.addProperty("axis", "z");
                rotation.addProperty("angle", quantise(cube.rotation().z));
            }
            element.add("rotation", rotation);
        }

        JsonObject faces = new JsonObject();
        for (Direction direction : Direction.values()) {
            JsonObject face = face(cube, geometry, direction, texture);
            if (face != null) faces.add(direction.getSerializedName(), face);
        }
        element.add("faces", faces);
        return element;
    }

    private static JsonObject face(BedrockCube cube, BedrockGeometry geometry, Direction direction,
        String texture) {
        float width = geometry.textureWidth() <= 0 ? 16 : geometry.textureWidth();
        float height = geometry.textureHeight() <= 0 ? 16 : geometry.textureHeight();

        float[] rectangle = switch (cube.uv()) {
            case CubeUv.Box box -> boxRectangle(box, cube.size(), direction);
            case CubeUv.PerFace perFace -> perFaceRectangle(perFace.faces(), direction);
        };
        if (rectangle == null) return null;

        JsonObject face = new JsonObject();
        JsonArray uv = new JsonArray();
        uv.add(rectangle[0] * 16 / width);
        uv.add(rectangle[1] * 16 / height);
        uv.add(rectangle[2] * 16 / width);
        uv.add(rectangle[3] * 16 / height);
        face.add("uv", uv);
        face.addProperty("texture", texture);
        return face;
    }

    /** Null for a face a pack gave no area, which Bedrock does not draw. */
    private static float[] perFaceRectangle(Map<Direction, FaceUv> faces, Direction direction) {
        FaceUv face = faces.get(direction);
        if (face == null || face.width() == 0 || face.height() == 0) return null;
        return new float[] {face.u(), face.v(), face.u() + face.width(), face.v() + face.height()};
    }

    /** The classic unwrap, laid out the same way Java's own box models are. */
    private static float[] boxRectangle(CubeUv.Box box, Vector3f size, Direction direction) {
        float u = box.u();
        float v = box.v();
        float width = size.x;
        float height = size.y;
        float depth = size.z;

        return switch (direction) {
            case UP -> new float[] {u + depth, v, u + depth + width, v + depth};
            case DOWN -> new float[] {u + depth + width, v, u + depth + width * 2, v + depth};
            case WEST -> new float[] {u, v + depth, u + depth, v + depth + height};
            case NORTH -> new float[] {u + depth, v + depth, u + depth + width, v + depth + height};
            case EAST -> new float[] {u + depth + width, v + depth, u + depth * 2 + width,
                v + depth + height};
            case SOUTH -> new float[] {u + depth * 2 + width, v + depth, u + depth * 2 + width * 2,
                v + depth + height};
        };
    }

    /** A Java model takes one of five angles, so a pack's own angle is snapped to the nearest. */
    private static float quantise(float degrees) {
        float[] allowed = {-45, -22.5f, 0, 22.5f, 45};
        float best = 0;
        for (float candidate : allowed) {
            if (Math.abs(candidate - degrees) < Math.abs(best - degrees)) best = candidate;
        }
        return best;
    }

    private static JsonArray triple(float x, float y, float z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }
}
