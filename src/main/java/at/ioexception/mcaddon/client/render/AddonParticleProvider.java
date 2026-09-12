package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.particle.EmitterDirection;
import at.ioexception.mcaddon.bedrock.particle.EmitterShape;
import at.ioexception.mcaddon.bedrock.particle.InitialSpeed;
import at.ioexception.mcaddon.bedrock.particle.ParticleDefinition;
import at.ioexception.mcaddon.registry.AddonParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * Makes one particle of a pack's effect, where the pack's shape says it belongs.
 *
 * The game asks for a particle at a point; a Bedrock effect says the point is only the middle of a
 * shape and the particle starts somewhere inside or on it, moving at a speed the pack chose. Working
 * that out here is what makes a sphere look like a sphere rather than like a single point.
 */
public record AddonParticleProvider(String identifier, SpriteSet sprites)
    implements ParticleProvider<SimpleParticleType> {

    //? if >=26 {
    @Override
    public Particle createParticle(SimpleParticleType type, ClientLevel level,
        double x, double y, double z, double dx, double dy, double dz, RandomSource random) {
        return make(level, x, y, z, dx, dy, dz, random);
    }
    //?} else {
    /*@Override
    public Particle createParticle(SimpleParticleType type, ClientLevel level,
        double x, double y, double z, double dx, double dy, double dz) {
        return make(level, x, y, z, dx, dy, dz, level.getRandom());
    }
    *///?}

    /** The particle itself, once the game has said where it wants one. */
    private Particle make(ClientLevel level, double x, double y, double z,
        double dx, double dy, double dz, RandomSource random) {
        ParticleDefinition definition = AddonParticles.definitionOf(identifier);
        if (definition == null) return null;

        MolangEnvironment environment = new MolangEnvironment();
        Vector3f offset = born(definition.emitter().shape(), environment, random);
        Vector3f speed = speed(definition, environment, offset);

        return new AddonParticle(level, x + offset.x(), y + offset.y(), z + offset.z(),
            dx + speed.x(), dy + speed.y(), dz + speed.z(),
            sprites.get(random), definition, environment);
    }

    /** Where inside the emitter's shape this particle appears, measured from the emitter itself. */
    private static Vector3f born(EmitterShape shape, MolangEnvironment environment, RandomSource random) {
        if (shape == null) return new Vector3f();

        return switch (shape) {
            case EmitterShape.Point point -> vector(point.offset(), environment);
            case EmitterShape.Sphere sphere -> vector(sphere.offset(), environment)
                .add(onSphere(random, sphere.radius().asFloat(environment), sphere.surfaceOnly()));
            case EmitterShape.Box box -> vector(box.offset(), environment)
                .add(inBox(random, vector(box.halfDimensions(), environment)));
            case EmitterShape.Disc disc -> vector(disc.offset(), environment)
                .add(onDisc(random, disc.radius().asFloat(environment),
                    vector(disc.planeNormal(), environment), disc.surfaceOnly()));
            case EmitterShape.EntityAabb ignored -> new Vector3f();
        };
    }

    /**
     * How fast the particle leaves, and which way.
     *
     * A shape saying `outwards` or `inwards` sends the particle along the line from the emitter's
     * middle to where it was born, which is what makes a sphere burst or collapse. A shape naming a
     * vector uses that instead, and a particle born exactly in the middle has no direction to take.
     */
    private static Vector3f speed(ParticleDefinition definition, MolangEnvironment environment,
        Vector3f offset) {
        InitialSpeed initial = definition.particle().initialSpeed();
        if (initial instanceof InitialSpeed.Vector vector) return vector(vector.velocity(), environment);

        float scalar = initial instanceof InitialSpeed.Scalar speed
            ? speed.speed().asFloat(environment) : 0f;
        if (scalar == 0f) return new Vector3f();

        EmitterDirection direction = directionOf(definition.emitter().shape());
        if (direction instanceof EmitterDirection.Vector vector) {
            return vector(vector.direction(), environment).normalize(scalar);
        }

        boolean outwards = !(direction instanceof EmitterDirection.Relative relative)
            || relative.outwards();
        if (offset.lengthSquared() == 0f) return new Vector3f();
        return new Vector3f(offset).normalize(outwards ? scalar : -scalar);
    }

    private static EmitterDirection directionOf(EmitterShape shape) {
        return switch (shape) {
            case null -> null;
            case EmitterShape.Point point -> point.direction();
            case EmitterShape.Sphere sphere -> sphere.direction();
            case EmitterShape.Box box -> box.direction();
            case EmitterShape.Disc disc -> disc.direction();
            case EmitterShape.EntityAabb aabb -> aabb.direction();
        };
    }

    private static Vector3f onSphere(RandomSource random, float radius, boolean surfaceOnly) {
        Vector3f point = new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(),
            (float) random.nextGaussian());
        if (point.lengthSquared() == 0f) point.set(0f, 1f, 0f);

        float distance = surfaceOnly ? radius : radius * (float) Math.cbrt(random.nextDouble());
        return point.normalize(distance);
    }

    private static Vector3f inBox(RandomSource random, Vector3f half) {
        return new Vector3f(
            half.x() * (random.nextFloat() * 2f - 1f),
            half.y() * (random.nextFloat() * 2f - 1f),
            half.z() * (random.nextFloat() * 2f - 1f));
    }

    /** A point on the disc lying in the plane the normal describes. */
    private static Vector3f onDisc(RandomSource random, float radius, Vector3f normal,
        boolean surfaceOnly) {
        Vector3f axis = normal.lengthSquared() == 0f ? new Vector3f(0f, 1f, 0f) : normal.normalize();
        Vector3f across = Math.abs(axis.y()) < 0.99f
            ? new Vector3f(0f, 1f, 0f).cross(axis).normalize()
            : new Vector3f(1f, 0f, 0f).cross(axis).normalize();
        Vector3f along = new Vector3f(axis).cross(across).normalize();

        float angle = random.nextFloat() * (float) (Math.PI * 2);
        float distance = surfaceOnly ? radius : radius * (float) Math.sqrt(random.nextDouble());
        return across.mul(distance * (float) Math.cos(angle))
            .add(along.mul(distance * (float) Math.sin(angle)));
    }

    private static Vector3f vector(Vector3Expression expression, MolangEnvironment environment) {
        return expression == null ? new Vector3f()
            : new Vector3f(expression.x().asFloat(environment), expression.y().asFloat(environment),
                expression.z().asFloat(environment));
    }
}
