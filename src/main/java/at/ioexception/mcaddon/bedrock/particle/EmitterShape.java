package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * Where inside the emitter a particle appears.
 *
 * `surfaceOnly` spawns on the shape's shell instead of throughout its volume, and means nothing to
 * the point shape, which has no volume to fill.
 */
public sealed interface EmitterShape {
    record Point(Vector3Expression offset, EmitterDirection direction) implements EmitterShape {
    }

    record Sphere(Vector3Expression offset, MolangExpression radius, EmitterDirection direction,
        boolean surfaceOnly) implements EmitterShape {
    }

    record Box(Vector3Expression offset, Vector3Expression halfDimensions, EmitterDirection direction,
        boolean surfaceOnly) implements EmitterShape {
    }

    record Disc(Vector3Expression planeNormal, Vector3Expression offset, MolangExpression radius,
        EmitterDirection direction, boolean surfaceOnly) implements EmitterShape {
    }

    /** The box the emitter's own entity occupies, whatever size that entity happens to be. */
    record EntityAabb(EmitterDirection direction, boolean surfaceOnly) implements EmitterShape {
    }

    static EmitterShape read(JsonObject components) {
        JsonObject point = ParticleJson.object(components, "minecraft:emitter_shape_point");
        if (point != null) {
            return new Point(ParticleJson.vector(point, "offset"), EmitterDirection.read(point));
        }

        JsonObject sphere = ParticleJson.object(components, "minecraft:emitter_shape_sphere");
        if (sphere != null) {
            return new Sphere(ParticleJson.vector(sphere, "offset"),
                ParticleJson.expression(sphere, "radius", 1), EmitterDirection.read(sphere),
                ParticleJson.flag(sphere, "surface_only", false));
        }

        JsonObject box = ParticleJson.object(components, "minecraft:emitter_shape_box");
        if (box != null) {
            return new Box(ParticleJson.vector(box, "offset"),
                ParticleJson.vector(box, "half_dimensions"), EmitterDirection.read(box),
                ParticleJson.flag(box, "surface_only", false));
        }

        JsonObject disc = ParticleJson.object(components, "minecraft:emitter_shape_disc");
        if (disc != null) {
            return new Disc(planeNormal(disc), ParticleJson.vector(disc, "offset"),
                ParticleJson.expression(disc, "radius", 1), EmitterDirection.read(disc),
                ParticleJson.flag(disc, "surface_only", false));
        }

        JsonObject aabb = ParticleJson.object(components, "minecraft:emitter_shape_entity_aabb");
        if (aabb != null) {
            return new EntityAabb(EmitterDirection.read(aabb),
                ParticleJson.flag(aabb, "surface_only", false));
        }
        return null;
    }

    /**
     * A disc lies in the plane this vector stands on.
     *
     * `x`, `y` and `z` are a spelling of the unit vector along that axis and carry nothing a vector
     * cannot, so both forms are kept as one. A disc that names no plane lies flat.
     */
    private static Vector3Expression planeNormal(JsonObject disc) {
        JsonElement value = disc.get("plane_normal");
        if (value != null && value.isJsonArray()) return ParticleJson.vector(value);

        String axis = value != null && value.isJsonPrimitive() ? value.getAsString() : "y";
        MolangExpression one = MolangExpression.constant(1);
        return switch (axis.toLowerCase(Locale.ROOT)) {
            case "x" -> new Vector3Expression(one, MolangExpression.ZERO, MolangExpression.ZERO);
            case "z" -> new Vector3Expression(MolangExpression.ZERO, MolangExpression.ZERO, one);
            default -> new Vector3Expression(MolangExpression.ZERO, one, MolangExpression.ZERO);
        };
    }
}
