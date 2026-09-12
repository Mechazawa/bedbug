package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.bedrock.particle.Billboard;
import at.ioexception.mcaddon.bedrock.particle.ParticleDefinition;
import at.ioexception.mcaddon.bedrock.particle.ParticleTint;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.joml.Vector3f;

/**
 * One particle of a pack's own effect, moving and drawn the way the pack described it.
 *
 * Everything a Bedrock particle does is an expression rather than a number, so each one carries the
 * variables it was born with and re-evaluates them as it goes. `q.particle_age` and
 * `q.particle_lifetime` are what a pack's own expressions are usually written against.
 */
//? if >=26 {
public class AddonParticle extends SingleQuadParticle {
//?} else {
/*public class AddonParticle extends net.minecraft.client.particle.TextureSheetParticle {
*///?}

    private final ParticleDefinition definition;
    private final MolangEnvironment environment;

    /** The moment the pack says this one ends, which is not always when its clock runs out. */
    private final float maxLifetime;
    private float age;

    private float spin;
    private float spinRate;

    /** Where in the flipbook the particle is, in whole frames from the first. */
    private float frame;

    protected AddonParticle(ClientLevel level, double x, double y, double z,
        double dx, double dy, double dz, TextureAtlasSprite sprite, ParticleDefinition definition,
        MolangEnvironment environment) {
        //? if >=26 {
        super(level, x, y, z, sprite);
        //?} else {
        /*super(level, x, y, z);
        setSprite(sprite);
        *///?}
        this.definition = definition;
        this.environment = environment;

        ParticleDefinition.Particle particle = definition.particle();
        this.maxLifetime = particle.lifetime() == null ? 1f
            : particle.lifetime().maxLifetime().asFloat(environment);
        this.lifetime = Math.max(1, Math.round(maxLifetime * 20f));

        if (particle.spin() != null) {
            this.spin = particle.spin().rotation().asFloat(environment);
            this.spinRate = particle.spin().rotationRate().asFloat(environment);
        }
        this.roll = (float) Math.toRadians(spin);
        this.oRoll = this.roll;

        setParticleSpeed(dx, dy, dz);
        this.hasPhysics = particle.collision() != null
            && particle.collision().enabled().evaluate(environment).truthy();
        this.friction = 1f;
        this.gravity = 0f;

        apply(0f);
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        oRoll = roll;

        age += 1f / 20f;
        if (over()) {
            remove();
            return;
        }

        ParticleDefinition.Particle particle = definition.particle();
        if (particle.dynamic() != null) accelerate(particle.dynamic());
        if (particle.parametric() != null) follow(particle.parametric());
        else move(xd, yd, zd);

        spin += spinRate / 20f;
        roll = (float) Math.toRadians(spin);
        apply(age);
    }

    /** Whether the pack says this one is finished, by its clock or by its own expression. */
    private boolean over() {
        ParticleDefinition.Particle particle = definition.particle();
        if (particle.lifetime() != null
            && particle.lifetime().expiration().evaluate(environment).truthy()) {
            return true;
        }
        return age >= maxLifetime;
    }

    /** `particle_motion_dynamic`: an acceleration and a drag, both in blocks and seconds. */
    private void accelerate(ParticleDefinition.Particle.Dynamic dynamic) {
        Vector3f acceleration = evaluate(dynamic.linearAcceleration());
        float drag = dynamic.linearDrag().asFloat(environment);

        xd += (acceleration.x() - drag * xd) / 20f;
        yd += (acceleration.y() - drag * yd) / 20f;
        zd += (acceleration.z() - drag * zd) / 20f;

        spinRate += (dynamic.rotationAcceleration().asFloat(environment)
            - dynamic.rotationDrag().asFloat(environment) * spinRate) / 20f;
    }

    /** `particle_motion_parametric`: the pack says where the particle is rather than how it moves. */
    private void follow(ParticleDefinition.Particle.Parametric parametric) {
        Vector3f offset = evaluate(parametric.relativePosition());
        setPos(xo + offset.x(), yo + offset.y(), zo + offset.z());
    }

    /**
     * Reads the size and the colour the pack's expressions give at this moment.
     *
     * A Bedrock size is a half extent in blocks and Java's quad size is the same measure, so the
     * width is taken as the one Java draws with. A flipbook advances on its own clock.
     */
    private void apply(float at) {
        environment.setVariable("particle_age", MolangValue.of(at));
        environment.setVariable("particle_lifetime", MolangValue.of(maxLifetime));

        Billboard billboard = definition.particle().billboard();
        if (billboard != null) {
            quadSize = Math.max(0.001f, billboard.size().x().asFloat(environment));
            if (billboard.uv() != null && billboard.uv().flipbook() != null) {
                frame = at * value(billboard.uv().flipbook().framesPerSecond());
            }
        }

        ParticleTint tint = definition.particle().tint();
        if (tint instanceof ParticleTint.Solid solid) {
            setColor(solid.red().asFloat(environment), solid.green().asFloat(environment),
                solid.blue().asFloat(environment));
            setAlpha(solid.alpha().asFloat(environment));
        }
    }

    private Vector3f evaluate(Vector3Expression expression) {
        return new Vector3f(value(expression.x()), value(expression.y()), value(expression.z()));
    }

    private float value(MolangExpression expression) {
        return expression == null ? 0f : expression.asFloat(environment);
    }

    /** Where in the flipbook this particle is, for a caller working out its corner coordinates. */
    protected float frame() {
        return frame;
    }

    //? if >=26 {
    @Override
    protected Layer getLayer() {
        return Layer.TRANSLUCENT;
    }
    //?} else {
    /*@Override
    public net.minecraft.client.particle.ParticleRenderType getRenderType() {
        return net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }
    *///?}
}
