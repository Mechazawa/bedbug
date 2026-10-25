package at.ioexception.mcaddon.bedrock.model;

import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Map;

/**
 * Where a posed bone stands in its model, the way `query.bone_orientation_trs` reports it.
 *
 * The translation is the bone's pivot in pixels and the rotation its whole turn in degrees, both in
 * the space the pack wrote its geometry in, and the scale is every scale above it multiplied in.
 */
public record BoneOrientation(Vector3f translation, Vector3f rotation, Vector3f scale) {
    /** What Bedrock answers for a bone the queryable geometry has not got. */
    public static final BoneOrientation IDENTITY =
        new BoneOrientation(new Vector3f(), new Vector3f(), new Vector3f(1, 1, 1));

    private static final float PIXELS_PER_BLOCK = 16f;

    /** Read off a bone's matrix in the frame a baked model is drawn in, whose lengths are in blocks. */
    public static BoneOrientation ofDrawn(Matrix4f pose) {
        Vector3f scale = pose.getScale(new Vector3f());
        Vector3f turn = new Matrix4f(pose).normalize3x3().getEulerAnglesZYX(new Vector3f());
        Vector3f place = pose.getTranslation(new Vector3f()).mul(PIXELS_PER_BLOCK);
        return new BoneOrientation(GeometryBaker.toBedrockSpace(place), GeometryBaker.toBedrockRotation(turn), scale);
    }

    /** The struct a pack reads `.t`, `.r` and `.s` off, each with `.x`, `.y` and `.z`. */
    public MolangValue asStruct() {
        return MolangValue.struct(Map.of(
            "t", MolangValue.xyz(translation.x, translation.y, translation.z),
            "r", MolangValue.xyz(rotation.x, rotation.y, rotation.z),
            "s", MolangValue.xyz(scale.x, scale.y, scale.z)));
    }
}
