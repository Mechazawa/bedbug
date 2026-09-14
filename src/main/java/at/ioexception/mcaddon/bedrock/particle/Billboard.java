package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** The quad a particle is drawn as, and the patch of the texture it wears. */
public record Billboard(Vector2Expression size, FacingCameraMode facing, Direction direction, Uv uv) {

    /**
     * Where the direction the aiming facing modes are written against comes from.
     *
     * An effect writing no `direction` block means the velocity with a threshold of a hundredth,
     * which is the default Mojang publishes, so one is built for every billboard.
     */
    public record Direction(boolean custom, Vector3Expression customDirection, float minSpeedThreshold) {
        static final Direction DEFAULT = new Direction(false, Vector3Expression.ZERO, 0.01f);
    }

    /**
     * Where on the texture the quad is cut from.
     *
     * The coordinates are in texture pixels, which is what `textureWidth` and `textureHeight` are
     * for: they are the size the pack wrote its coordinates against, and a texture shipped at any
     * other resolution still lands on the same part of the image.
     */
    public record Uv(float textureWidth, float textureHeight, Vector2Expression uv,
        Vector2Expression uvSize, Flipbook flipbook) {
    }

    /**
     * An animation walked across the texture, one step per frame.
     *
     * `stretchToLifetime` makes the pack's frame rate a starting point rather than the rate used:
     * the animation is fitted to the particle's own lifetime instead.
     */
    public record Flipbook(Vector2Expression baseUv, Vector2Expression sizeUv, Vector2Expression stepUv,
        MolangExpression framesPerSecond, MolangExpression maxFrame, boolean stretchToLifetime,
        boolean loop) {
    }

    static Billboard read(JsonObject components) {
        JsonObject billboard =
            ParticleJson.object(components, "minecraft:particle_appearance_billboard");
        if (billboard == null) return null;

        FacingCameraMode facing = FacingCameraMode.of(Json.text(billboard, "facing_camera_mode", null));
        if (unaimed().contains(facing)) {
            AddonEntities.unsupported("particle facing_camera_mode "
                + facing.name().toLowerCase(Locale.ROOT));
        }

        return new Billboard(
            Vector2Expression.read(billboard, "size", Vector2Expression.ZERO),
            facing,
            readDirection(ParticleJson.object(billboard, "direction")),
            readUv(ParticleJson.object(billboard, "uv")));
    }

    /**
     * The facing modes nothing here turns a quad to.
     *
     * An emitter's own transform is not carried on to the particles it made, so the three modes
     * matching a plane of it have nothing to match. 1.21 made Java's two modes values of an
     * interface a mod may add to, which is what carries the four that aim a quad along the
     * particle's own direction; before that a quad's rotation is the camera's and nothing else.
     */
    private static Set<FacingCameraMode> unaimed() {
        //? if >=1.21 {
        return EnumSet.of(FacingCameraMode.EMITTER_TRANSFORM_XY, FacingCameraMode.EMITTER_TRANSFORM_XZ,
            FacingCameraMode.EMITTER_TRANSFORM_YZ);
        //?} else {
        /*return EnumSet.of(FacingCameraMode.EMITTER_TRANSFORM_XY, FacingCameraMode.EMITTER_TRANSFORM_XZ,
            FacingCameraMode.EMITTER_TRANSFORM_YZ, FacingCameraMode.LOOKAT_DIRECTION,
            FacingCameraMode.DIRECTION_X, FacingCameraMode.DIRECTION_Y, FacingCameraMode.DIRECTION_Z);
        *///?}
    }

    /**
     * The `direction` block, or the default Mojang publishes for a billboard that writes none.
     *
     * The schema spells the second mode `custom` and the documentation spells it `custom_direction`,
     * so anything but the velocity mode is read as the custom one.
     */
    private static Direction readDirection(JsonObject json) {
        if (json == null) return Direction.DEFAULT;

        String mode = Json.text(json, "mode", "derive_from_velocity");
        return new Direction(
            !"derive_from_velocity".equals(mode),
            ParticleJson.vector(json, "custom_direction"),
            ParticleJson.number(json, "min_speed_threshold", Direction.DEFAULT.minSpeedThreshold()));
    }

    private static Uv readUv(JsonObject json) {
        if (json == null) return null;

        // Packs are written with either the name the schema gives these or the older run-together one.
        return new Uv(
            ParticleJson.number(json, "texture_width", ParticleJson.number(json, "texturewidth", 1)),
            ParticleJson.number(json, "texture_height", ParticleJson.number(json, "textureheight", 1)),
            Vector2Expression.read(json, "uv", Vector2Expression.ZERO),
            Vector2Expression.read(json, "uv_size", Vector2Expression.ONE),
            readFlipbook(ParticleJson.object(json, "flipbook")));
    }

    private static Flipbook readFlipbook(JsonObject json) {
        if (json == null) return null;

        return new Flipbook(
            Vector2Expression.read(json, "base_UV", Vector2Expression.ZERO),
            Vector2Expression.read(json, "size_UV", Vector2Expression.ZERO),
            Vector2Expression.read(json, "step_UV", Vector2Expression.ZERO),
            ParticleJson.expression(json, "frames_per_second", 0),
            ParticleJson.expression(json, "max_frame", 0),
            ParticleJson.flag(json, "stretch_to_lifetime", false),
            ParticleJson.flag(json, "loop", false));
    }
}
