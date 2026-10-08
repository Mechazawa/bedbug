package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.bedrock.model.BedrockBone;
import at.ioexception.mcaddon.bedrock.model.BedrockCube;
import at.ioexception.mcaddon.bedrock.model.BedrockGeometry;
import at.ioexception.mcaddon.bedrock.model.CubeUv;
import at.ioexception.mcaddon.bedrock.model.FaceUv;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * Null when the geometry has nothing to draw, which is how a pack writes an invisible block.
     *
     * Each face wears the texture variable of the material instance it names, then the one named
     * after its own direction, then `all`, which is the order Bedrock looks a face's material up
     * in. `textures` is the set of variables the model declares.
     */
    static JsonArray elements(BedrockGeometry geometry, Set<String> textures) {
        JsonArray elements = new JsonArray();
        for (BedrockBone bone : geometry.bones()) {
            if (!bone.textureMeshes().isEmpty()) AddonEntities.unsupported("block geometry texture_meshes");
            for (BedrockCube cube : bone.cubes()) {
                elements.add(element(cube, geometry, textures));
            }
        }
        return elements.isEmpty() ? null : elements;
    }

    /**
     * What the geometry says about how its block is held, worn and shown as an item, as the
     * `display` block of a Java model, or null where it says nothing.
     *
     * The two editions name the same contexts and measure each the same way, a rotation in degrees
     * and a translation in sixteenths, applied in the same order. Bedrock's `shelf` is Java's
     * `on_shelf`, which only 26 has. What Java holds no place for is reported: the context an
     * embedded visual is drawn in, a pivot to turn or scale about other than the model's middle,
     * and a frame for the item to be fitted to.
     */
    static JsonObject display(BedrockGeometry geometry) {
        JsonObject transforms = geometry.itemDisplayTransforms();
        if (transforms == null) return null;

        JsonObject display = new JsonObject();
        for (Map.Entry<String, JsonElement> context : transforms.entrySet()) {
            String java = javaContext(context.getKey());
            if (java == null || !context.getValue().isJsonObject()) {
                AddonEntities.unsupported("geometry item_display_transforms " + context.getKey());
                continue;
            }

            JsonObject bedrock = context.getValue().getAsJsonObject();
            AddonEntities.unsupportedFields("geometry item_display_transforms", bedrock,
                UNREAD_DISPLAY);
            JsonObject transform = new JsonObject();
            for (String part : List.of("rotation", "translation", "scale")) {
                if (bedrock.has(part)) transform.add(part, bedrock.get(part));
            }
            display.add(java, transform);
        }
        return display.size() == 0 ? null : display;
    }

    /** The parts of a display transform a Java model has no field for. */
    private static final List<String> UNREAD_DISPLAY =
        List.of("rotation_pivot", "scale_pivot", "fit_to_frame");

    /** Java's name for the context Bedrock names, or null where this version has none. */
    private static String javaContext(String bedrock) {
        String named = bedrock.equals("shelf") ? "on_shelf" : bedrock;
        return Arrays.stream(ItemDisplayContext.values())
            .map(ItemDisplayContext::getSerializedName)
            .filter(named::equals)
            .findFirst()
            .orElse(null);
    }

    private static JsonObject element(BedrockCube cube, BedrockGeometry geometry, Set<String> textures) {
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
            JsonObject face = face(cube, geometry, direction, textures);
            if (face != null) faces.add(direction.getSerializedName(), face);
        }
        element.add("faces", faces);
        return element;
    }

    private static JsonObject face(BedrockCube cube, BedrockGeometry geometry, Direction direction,
        Set<String> textures) {
        float width = geometry.textureWidth() <= 0 ? 16 : geometry.textureWidth();
        float height = geometry.textureHeight() <= 0 ? 16 : geometry.textureHeight();

        float[] rectangle = switch (cube.uv()) {
            case CubeUv.Box box -> boxRectangle(box, cube.size(), direction);
            case CubeUv.PerFace perFace -> perFaceRectangle(perFace.faces(), direction);
        };
        if (rectangle == null) return null;

        String instance = cube.uv() instanceof CubeUv.PerFace perFace
            && perFace.faces().get(direction) != null
            ? perFace.faces().get(direction).materialInstance() : null;
        String texture = "#" + (instance != null && textures.contains(instance) ? instance
            : textures.contains(direction.getSerializedName()) ? direction.getSerializedName() : "all");

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
