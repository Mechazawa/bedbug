package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;

/** The quad a particle is drawn as, and the patch of the texture it wears. */
public record Billboard(Vector2Expression size, FacingCameraMode facing, Uv uv) {

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

        return new Billboard(
            Vector2Expression.read(billboard, "size", Vector2Expression.ZERO),
            FacingCameraMode.of(Json.text(billboard, "facing_camera_mode", null)),
            readUv(ParticleJson.object(billboard, "uv")));
    }

    private static Uv readUv(JsonObject json) {
        if (json == null) return null;

        return new Uv(
            ParticleJson.number(json, "texture_width", 1),
            ParticleJson.number(json, "texture_height", 1),
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
