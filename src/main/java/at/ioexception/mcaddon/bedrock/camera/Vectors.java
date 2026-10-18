package at.ioexception.mcaddon.bedrock.camera;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

/**
 * Three numbers as Bedrock writes them in a camera file: an array of three in JSON, or an object with
 * an `x`, a `y` and a `z` as a script states one. A number left out is zero.
 */
final class Vectors {
    private Vectors() {
    }

    static JsonArray write(Vec3 vector) {
        JsonArray array = new JsonArray();
        array.add(vector.x);
        array.add(vector.y);
        array.add(vector.z);
        return array;
    }

    /** Null for anything that is neither shape, which a reader turns into its own default. */
    static Vec3 read(JsonElement written) {
        if (written == null) return null;
        if (written.isJsonArray()) {
            JsonArray array = written.getAsJsonArray();
            return new Vec3(at(array, 0), at(array, 1), at(array, 2));
        }
        if (!written.isJsonObject()) return null;

        JsonObject object = written.getAsJsonObject();
        return new Vec3(number(object, "x"), number(object, "y"), number(object, "z"));
    }

    /** A `[x, y]` pair, or null for anything else. */
    static float[] pair(JsonElement written) {
        if (written == null || !written.isJsonArray() || written.getAsJsonArray().size() < 2) return null;
        JsonArray array = written.getAsJsonArray();
        return new float[] {(float) at(array, 0), (float) at(array, 1)};
    }

    static JsonArray pair(float first, float second) {
        JsonArray array = new JsonArray();
        array.add(first);
        array.add(second);
        return array;
    }

    private static double at(JsonArray array, int index) {
        return index < array.size() && array.get(index).isJsonPrimitive() ? array.get(index).getAsDouble() : 0;
    }

    private static double number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsDouble() : 0;
    }
}
