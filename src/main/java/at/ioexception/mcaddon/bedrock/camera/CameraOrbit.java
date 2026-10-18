package at.ioexception.mcaddon.bedrock.camera;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * How Bedrock's orbit cameras, `minecraft:follow_orbit` and `minecraft:fixed_boom`, stand off the
 * entity they follow.
 *
 * The camera stands `radius` blocks back from the point it circles along the way it looks. That point
 * is the middle of the entity's box moved by `entityOffset`, in blocks along the world's axes, and
 * `viewX` and `viewY` then slide the camera across its own screen, right and up, which puts the
 * entity off to one side of it. Bedrock holds the radius between a tenth of a block and a hundred.
 *
 * `startXRot` and `startYRot` are the turn the camera starts at, null where nothing states one, and
 * `alignsFollowed` turns the entity to look the way the camera does when it is set.
 */
public record CameraOrbit(float radius, float viewX, float viewY, Vec3 entityOffset, Float startXRot,
    Float startYRot, boolean alignsFollowed) {

    public static final CameraOrbit DEFAULT = new CameraOrbit(10f, 0f, 0f, Vec3.ZERO, null, null, false);

    private static final float NEAREST = 0.1f;
    private static final float FURTHEST = 100f;

    /** The orbit a preset writes on top of this one: what it states replaces what this one holds. */
    public CameraOrbit inheritedBy(JsonObject body) {
        float[] view = Vectors.pair(body.get("view_offset"));
        Vec3 offset = Vectors.read(body.get("entity_offset"));
        return new CameraOrbit(
            Mth.clamp((float) Json.number(body, "radius", radius), NEAREST, FURTHEST),
            view == null ? viewX : view[0], view == null ? viewY : view[1],
            offset == null ? entityOffset : offset,
            body.has("starting_rot_x") ? Float.valueOf((float) Json.number(body, "starting_rot_x", 0)) : startXRot,
            body.has("starting_rot_y") ? Float.valueOf((float) Json.number(body, "starting_rot_y", 0)) : startYRot,
            Json.bool(body, "align_target_and_camera_forward", alignsFollowed));
    }

    /** This orbit with whatever a command or a script stated over it. */
    public CameraOrbit offsetBy(Float givenViewX, Float givenViewY, Vec3 givenEntityOffset) {
        return new CameraOrbit(radius, givenViewX == null ? viewX : givenViewX, givenViewY == null ? viewY : givenViewY,
            givenEntityOffset == null ? entityOffset : givenEntityOffset, startXRot, startYRot, alignsFollowed);
    }

    /** Where the camera stands circling `pivot` while it looks along `xRot` and `yRot`. */
    public CameraPlacement around(Vec3 pivot, float xRot, float yRot) {
        Vec3 right = Vec3.directionFromRotation(0, yRot + 90);
        Vec3 up = Vec3.directionFromRotation(xRot - 90, yRot);
        Vec3 position = pivot.add(entityOffset)
            .subtract(Vec3.directionFromRotation(xRot, yRot).scale(radius))
            .add(right.scale(viewX))
            .add(up.scale(viewY));
        return new CameraPlacement(position, xRot, yRot, true);
    }

    JsonObject json() {
        JsonObject json = new JsonObject();
        json.addProperty("radius", radius);
        json.add("view_offset", Vectors.pair(viewX, viewY));
        json.add("entity_offset", Vectors.write(entityOffset));
        if (startXRot != null) json.addProperty("starting_rot_x", startXRot);
        if (startYRot != null) json.addProperty("starting_rot_y", startYRot);
        json.addProperty("align_target_and_camera_forward", alignsFollowed);
        return json;
    }

    static CameraOrbit read(JsonElement written) {
        return written == null || !written.isJsonObject() ? DEFAULT : DEFAULT.inheritedBy(written.getAsJsonObject());
    }
}
