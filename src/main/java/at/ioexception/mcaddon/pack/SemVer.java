package at.ioexception.mcaddon.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * A pack version.
 *
 * Bedrock writes these two ways, `"1.0.4"` and `[1, 0, 4]`, sometimes with a trailing label the
 * engine ignores, so both forms and the label are accepted here.
 */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {
    public static final SemVer ZERO = new SemVer(0, 0, 0);

    public static SemVer tryFrom(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonArray()) {
            JsonArray parts = value.getAsJsonArray();
            return new SemVer(intAt(parts, 0), intAt(parts, 1), intAt(parts, 2));
        }
        if (!value.isJsonPrimitive()) return null;

        String[] parts = value.getAsString().split("[.+-]");
        int[] numbers = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        return new SemVer(numbers[0], numbers[1], numbers[2]);
    }

    private static int intAt(JsonArray parts, int index) {
        return index < parts.size() && parts.get(index).isJsonPrimitive() ? parts.get(index).getAsInt() : 0;
    }

    @Override
    public int compareTo(SemVer other) {
        if (major != other.major) return Integer.compare(major, other.major);
        if (minor != other.minor) return Integer.compare(minor, other.minor);
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
