package at.ioexception.mcaddon.bedrock.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads one geometry out of a pack's `models/entity` JSON. */
public final class GeometryParser {

    private GeometryParser() {
    }

    public static BedrockGeometry readGeometry(String identifier, JsonObject json) {
        JsonObject description = json.getAsJsonObject("description");
        List<BedrockBone> bones = new ArrayList<>();
        if (json.has("bones")) {
            for (JsonElement bone : json.getAsJsonArray("bones")) bones.add(readBone(bone.getAsJsonObject()));
        }
        return new BedrockGeometry(
            identifier,
            optionalInt(description, "texture_width", 64),
            optionalInt(description, "texture_height", 64),
            List.copyOf(bones));
    }

    private static BedrockBone readBone(JsonObject json) {
        List<BedrockCube> cubes = new ArrayList<>();
        if (json.has("cubes")) {
            for (JsonElement cube : json.getAsJsonArray("cubes")) cubes.add(readCube(cube.getAsJsonObject()));
        }

        Map<String, Vector3f> locators = new LinkedHashMap<>();
        if (json.has("locators")) {
            for (Map.Entry<String, JsonElement> locator : json.getAsJsonObject("locators").entrySet()) {
                // A locator is normally a point, but may be an object carrying a rotation as well.
                JsonElement value = locator.getValue();
                JsonArray offset = value.isJsonArray() ? value.getAsJsonArray()
                    : value.getAsJsonObject().getAsJsonArray("offset");
                if (offset != null) locators.put(locator.getKey(), vector(offset));
            }
        }

        return new BedrockBone(
            json.get("name").getAsString(),
            json.has("parent") ? json.get("parent").getAsString() : null,
            optionalVector(json, "pivot"),
            optionalVector(json, "rotation"),
            json.has("mirror") && json.get("mirror").getAsBoolean(),
            List.copyOf(cubes),
            Map.copyOf(locators));
    }

    private static BedrockCube readCube(JsonObject json) {
        return new BedrockCube(
            optionalVector(json, "origin"),
            optionalVector(json, "size"),
            json.has("inflate") ? json.get("inflate").getAsFloat() : 0f,
            json.has("mirror") && json.get("mirror").getAsBoolean(),
            optionalVector(json, "pivot"),
            optionalVector(json, "rotation"),
            readUv(json.get("uv")));
    }

    private static CubeUv readUv(JsonElement json) {
        if (json == null || json.isJsonNull()) return new CubeUv.Box(0, 0);

        if (json.isJsonArray()) {
            JsonArray array = json.getAsJsonArray();
            return new CubeUv.Box(array.get(0).getAsFloat(), array.get(1).getAsFloat());
        }

        Map<Direction, FaceUv> faces = new EnumMap<>(Direction.class);
        for (Map.Entry<String, JsonElement> face : json.getAsJsonObject().entrySet()) {
            Direction direction = directionOf(face.getKey());
            if (direction == null) continue;

            JsonObject value = face.getValue().getAsJsonObject();
            JsonArray uv = value.getAsJsonArray("uv");
            JsonArray size = value.getAsJsonArray("uv_size");
            faces.put(direction, new FaceUv(
                uv.get(0).getAsFloat(), uv.get(1).getAsFloat(),
                size == null ? 0 : size.get(0).getAsFloat(),
                size == null ? 0 : size.get(1).getAsFloat(),
                optionalInt(value, "uv_rotation", 0)));
        }
        return new CubeUv.PerFace(Map.copyOf(faces));
    }

    /**
     * Bedrock names faces by compass direction with the model facing north, which is how Minecraft
     * names them too, so the mapping is one to one.
     */
    private static Direction directionOf(String name) {
        return switch (name) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            case "up" -> Direction.UP;
            case "down" -> Direction.DOWN;
            default -> null;
        };
    }

    private static Vector3f optionalVector(JsonObject json, String field) {
        return json.has(field) ? vector(json.getAsJsonArray(field)) : new Vector3f();
    }

    private static Vector3f vector(JsonArray array) {
        return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }

    private static int optionalInt(JsonObject json, String field, int fallback) {
        return json.has(field) ? json.get(field).getAsInt() : fallback;
    }

}
