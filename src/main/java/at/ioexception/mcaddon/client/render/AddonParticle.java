package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.bedrock.particle.Billboard;
import at.ioexception.mcaddon.bedrock.particle.ParticleDefinition;
import at.ioexception.mcaddon.bedrock.particle.ParticleEvent;
import at.ioexception.mcaddon.bedrock.particle.ParticleTint;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.BlockPos;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

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

    /** The packed sky and block light of a particle that carries its own brightness. */
    private static final int FULL_BRIGHT = 0xF000F0;

    /** What Bedrock's aiming facing modes mean by upwards. */
    private static final Vector3f UP = new Vector3f(0, 1, 0);

    private final ParticleDefinition definition;
    private final MolangEnvironment environment;

    /** Which way this particle points, which is what the aiming facing modes turn the quad by. */
    private final Vector3f direction = new Vector3f(0, 0, 1);

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
        ParticleDefinition.Particle.Collision collision = particle.collision();
        this.hasPhysics = collision != null && collision.enabled().evaluate(environment).truthy();
        if (hasPhysics) {
            float radius = Math.max(0.01f, collision.radius().asFloat(environment));
            setSize(radius * 2f, radius * 2f);
        }
        this.friction = 1f;
        this.gravity = 0f;

        apply(0f);
        raise(particle.events().creation());
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        oRoll = roll;

        float was = age;
        age += 1f / 20f;
        if (over()) {
            raise(definition.particle().events().expiration());
            remove();
            return;
        }

        ParticleDefinition.Particle particle = definition.particle();
        if (particle.initialization() != null) {
            particle.initialization().perUpdate().evaluate(environment);
        }
        if (particle.dynamic() != null) accelerate(particle.dynamic());
        if (particle.parametric() != null) follow(particle.parametric());
        else move(xd, yd, zd);

        spin += spinRate / 20f;
        roll = (float) Math.toRadians(spin);
        apply(age);
        timeline(was);

        if (grounded()) return;
        if (beyondKillPlane() || inWrongBlock()) remove();
    }

    /** Raises the timeline events this particle passed over during this tick. */
    private void timeline(float was) {
        for (ParticleEvent.Moment moment : definition.particle().events().timeline()) {
            if (moment.at() > was && moment.at() <= age) raise(moment.events());
        }
    }

    private void raise(List<String> events) {
        if (events.isEmpty()) return;
        AddonEmitters.fire(definition, events, environment, new Vec3(x, y, z), null);
    }

    /**
     * Bedrock draws a particle at full brightness unless the effect asks to be lit.
     *
     * Java's own particles take the light where they stand, which is what the lighting component
     * asks for, so the override is the other way round: without it, the particle carries its own.
     */
    @Override
    protected int getLightCoords(float partialTick) {
        return definition.particle().lit() ? super.getLightCoords(partialTick) : FULL_BRIGHT;
    }

    /**
     * What a collision does once the particle has touched something.
     *
     * Bedrock either ends the particle on contact or slows it and bounces it, and Java's own move
     * has already stopped it against the block by the time this runs.
     */
    private boolean grounded() {
        ParticleDefinition.Particle.Collision collision = definition.particle().collision();
        if (collision == null || !onGround) return false;

        float speed = (float) Math.sqrt(xd * xd + yd * yd + zd * zd) * 20f;
        raise(collision.events().stream()
            .filter(impact -> speed >= impact.minimumSpeed())
            .map(ParticleDefinition.Particle.Impact::event)
            .toList());

        if (collision.expireOnContact()) {
            raise(definition.particle().events().expiration());
            remove();
            return true;
        }

        float drag = collision.drag().asFloat(environment);
        float bounce = collision.restitution().asFloat(environment);
        xd *= Math.max(0f, 1f - drag / 20f);
        zd *= Math.max(0f, 1f - drag / 20f);
        yd = Math.abs(yd) * bounce;
        return false;
    }

    /** `particle_kill_plane` is a plane in the emitter's space, and anything past it is over. */
    private boolean beyondKillPlane() {
        ParticleDefinition.Particle.KillPlane plane = definition.particle().killPlane();
        return plane != null
            && plane.a() * x + plane.b() * y + plane.c() * z + plane.d() > 0;
    }

    /** `expire_if_in_blocks` and `expire_not_in_blocks`, which a pack uses to keep smoke indoors. */
    private boolean inWrongBlock() {
        List<String> inside = definition.particle().expireInBlocks();
        List<String> outside = definition.particle().expireNotInBlocks();
        if (inside.isEmpty() && outside.isEmpty()) return false;

        String here = BuiltInRegistries.BLOCK
            .getKey(level.getBlockState(BlockPos.containing(x, y, z)).getBlock()).toString();
        return inside.contains(here) || (!outside.isEmpty() && !outside.contains(here));
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

    /**
     * `particle_motion_dynamic`: an acceleration and a drag, both in blocks and seconds.
     *
     * The acceleration crosses into Java's units twice over, once for the distance and once for the
     * time, while the drag crosses once: the pack's coefficient prices a velocity, so the second
     * crossing cancels against the one that turned the velocity into blocks a tick.
     *
     * The spin keeps Bedrock's own units throughout, because nothing in Java advances it.
     */
    private void accelerate(ParticleDefinition.Particle.Dynamic dynamic) {
        Vector3f acceleration = evaluate(dynamic.linearAcceleration());
        float drag = dynamic.linearDrag().asFloat(environment);

        xd += BedrockUnits.blocksPerTickSquared(acceleration.x()) - drag * xd / BedrockUnits.TICKS_PER_SECOND;
        yd += BedrockUnits.blocksPerTickSquared(acceleration.y()) - drag * yd / BedrockUnits.TICKS_PER_SECOND;
        zd += BedrockUnits.blocksPerTickSquared(acceleration.z()) - drag * zd / BedrockUnits.TICKS_PER_SECOND;

        spinRate += (dynamic.rotationAcceleration().asFloat(environment)
            - dynamic.rotationDrag().asFloat(environment) * spinRate) / BedrockUnits.TICKS_PER_SECOND;
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
        AddonEmitters.evaluateCurves(definition, environment);

        ParticleDefinition.Particle.Initialization start = definition.particle().initialization();
        if (start != null) start.perRender().evaluate(environment);

        Billboard billboard = definition.particle().billboard();
        if (billboard != null) {
            quadSize = Math.max(0.001f, billboard.size().x().asFloat(environment));
            advanceFlipbook(billboard, at);
        }

        aimDirection();
        paint(definition.particle().tint());
    }

    /**
     * The colour the texture is multiplied by, read where the pack says to read it.
     *
     * A gradient is a list of colours at positions, and the pack's own expression decides where
     * along it to look, which is usually the particle's age over its lifetime.
     */
    private void paint(ParticleTint tint) {
        ParticleTint.Solid solid = tint instanceof ParticleTint.Gradient gradient
            ? at(gradient) : tint instanceof ParticleTint.Solid only ? only : null;
        if (solid == null) return;

        setColor(solid.red().asFloat(environment), solid.green().asFloat(environment),
            solid.blue().asFloat(environment));
        setAlpha(solid.alpha().asFloat(environment));
    }

    /** The stop a gradient's interpolant lands on, which is the nearer of the two it sits between. */
    private ParticleTint.Solid at(ParticleTint.Gradient gradient) {
        if (gradient.stops().isEmpty()) return null;

        float where = gradient.interpolant().asFloat(environment);
        ParticleTint.Gradient.Stop chosen = gradient.stops().get(0);
        for (ParticleTint.Gradient.Stop stop : gradient.stops()) {
            if (stop.position() > where) break;
            chosen = stop;
        }
        return chosen.colour();
    }

    private Vector3f evaluate(Vector3Expression expression) {
        return new Vector3f(value(expression.x()), value(expression.y()), value(expression.z()));
    }

    private float value(MolangExpression expression) {
        return expression == null ? 0f : expression.asFloat(environment);
    }

    /**
     * Walks the flipbook to where the particle's age puts it.
     *
     * `stretch_to_lifetime` makes the pack's frame rate a starting point rather than the rate used:
     * the whole animation is fitted to this particle's own lifetime instead.
     */
    private void advanceFlipbook(Billboard billboard, float at) {
        if (billboard.uv() == null || billboard.uv().flipbook() == null) return;

        Billboard.Flipbook flipbook = billboard.uv().flipbook();
        float last = Math.max(0f, value(flipbook.maxFrame()));
        frame = flipbook.stretchToLifetime() && maxLifetime > 0f
            ? last * (at / maxLifetime)
            : at * value(flipbook.framesPerSecond());

        if (last <= 0f) return;
        frame = flipbook.loop() ? frame % (last + 1f) : Math.min(frame, last);
    }

    /**
     * The patch of the sprite this particle wears, as a fraction of the whole texture.
     *
     * A pack writes its coordinates in the pixels of the size it declares, so they are divided by
     * that size rather than by the image's, which is what lets the same pack ship the texture at any
     * resolution. A flipbook steps that patch along by whole frames.
     */
    private float[] window() {
        Billboard billboard = definition.particle().billboard();
        if (billboard == null || billboard.uv() == null) return null;

        Billboard.Uv uv = billboard.uv();
        float width = uv.textureWidth() <= 0 ? 1f : uv.textureWidth();
        float height = uv.textureHeight() <= 0 ? 1f : uv.textureHeight();

        float u = uv.uv().x().asFloat(environment);
        float v = uv.uv().y().asFloat(environment);
        float across = uv.uvSize().x().asFloat(environment);
        float down = uv.uvSize().y().asFloat(environment);

        Billboard.Flipbook flipbook = uv.flipbook();
        if (flipbook != null) {
            int step = (int) frame;
            u = flipbook.baseUv().x().asFloat(environment) + step * flipbook.stepUv().x().asFloat(environment);
            v = flipbook.baseUv().y().asFloat(environment) + step * flipbook.stepUv().y().asFloat(environment);
            across = flipbook.sizeUv().x().asFloat(environment);
            down = flipbook.sizeUv().y().asFloat(environment);
        }
        if (across <= 0f || down <= 0f) return null;

        return new float[] {u / width, v / height, (u + across) / width, (v + down) / height};
    }

    /** Where the sprite sits on the atlas, so a window into it lands in the right place. */
    private float across(float fraction, float low, float high) {
        return low + (high - low) * Math.clamp(fraction, 0f, 1f);
    }

    @Override
    protected float getU0() {
        float[] window = window();
        return window == null ? super.getU0() : across(window[0], super.getU0(), super.getU1());
    }

    @Override
    protected float getU1() {
        float[] window = window();
        return window == null ? super.getU1() : across(window[2], super.getU0(), super.getU1());
    }

    @Override
    protected float getV0() {
        float[] window = window();
        return window == null ? super.getV0() : across(window[1], super.getV0(), super.getV1());
    }

    @Override
    protected float getV1() {
        float[] window = window();
        return window == null ? super.getV1() : across(window[3], super.getV0(), super.getV1());
    }

    /**
     * The direction Bedrock's aiming facing modes are written against.
     *
     * A pack either states it outright or takes it off the velocity, and a particle slower than the
     * threshold keeps whichever direction it last had rather than turning to a velocity of nothing.
     */
    private void aimDirection() {
        Billboard billboard = definition.particle().billboard();
        Billboard.Direction settings = billboard == null ? null : billboard.direction();
        if (settings == null) return;

        if (settings.custom()) {
            Vector3f stated = evaluate(settings.customDirection());
            if (stated.lengthSquared() > 0f) direction.set(stated).normalize();
            return;
        }
        // Bedrock states the threshold in blocks a second and Java carries a velocity in blocks a tick.
        double speed = Math.sqrt(xd * xd + yd * yd + zd * zd) * BedrockUnits.TICKS_PER_SECOND;
        if (speed > settings.minSpeedThreshold()) {
            direction.set((float) xd, (float) yd, (float) zd).normalize();
        }
    }

    //? if >=1.21 {
    /**
     * Which way the quad turns.
     *
     * Java's own two modes are values of an interface rather than the whole vocabulary, so the
     * aiming modes are supplied here instead of being turned into one of them: `direction_x`,
     * `direction_y` and `direction_z` put the named local axis along the particle's direction and
     * `lookat_direction` stands the quad up along it while still turning its face to the camera.
     * The three `emitter_transform_*` modes are reported, because the emitter's own transform is not
     * carried on to the particles it made.
     */
    @Override
    public FacingCameraMode getFacingCameraMode() {
        return facing;
    }

    private final FacingCameraMode facing = this::orient;

    private void orient(Quaternionf store, net.minecraft.client.Camera camera, float partialTick) {
        Billboard billboard = definition.particle().billboard();
        at.ioexception.mcaddon.bedrock.particle.FacingCameraMode mode = billboard == null
            ? at.ioexception.mcaddon.bedrock.particle.FacingCameraMode.ROTATE_XYZ : billboard.facing();

        if (aim(store, mode, partialTick)) return;
        if (mode == at.ioexception.mcaddon.bedrock.particle.FacingCameraMode.ROTATE_Y
            || mode == at.ioexception.mcaddon.bedrock.particle.FacingCameraMode.LOOKAT_Y) {
            FacingCameraMode.LOOKAT_Y.setRotation(store, camera, partialTick);
            return;
        }
        FacingCameraMode.LOOKAT_XYZ.setRotation(store, camera, partialTick);
    }

    /**
     * Puts the quad's own axes where one of Bedrock's aiming modes asks for them.
     *
     * Each mode names the quad axis that lies along the particle's direction and says the remaining
     * pair attempts to aim upwards, which is the world up made perpendicular to the direction;
     * `lookat_direction` aims the same pair at the camera instead. False for a mode that aims
     * nothing, and for a direction parallel to what the other axes are built from, which leaves
     * nothing to build a rotation out of.
     */
    private boolean aim(Quaternionf store, at.ioexception.mcaddon.bedrock.particle.FacingCameraMode mode,
        float partialTick) {
        Vector3f made = switch (mode) {
            case DIRECTION_X, DIRECTION_Y, DIRECTION_Z -> perpendicular(UP, direction);
            case LOOKAT_DIRECTION -> perpendicular(towardsCamera(partialTick), direction);
            default -> null;
        };
        if (made == null) return false;

        return switch (mode) {
            case DIRECTION_X -> set(store, direction, made, new Vector3f(direction).cross(made));
            case DIRECTION_Y -> set(store, made, direction, new Vector3f(made).cross(direction));
            case DIRECTION_Z -> set(store, new Vector3f(made).cross(direction), made, direction);
            case LOOKAT_DIRECTION -> set(store, new Vector3f(direction).cross(made), direction, made);
            default -> false;
        };
    }

    /** A rotation whose local x, y and z axes are the three unit vectors given, in that order. */
    private static boolean set(Quaternionf store, Vector3f x, Vector3f y, Vector3f z) {
        store.setFromNormalized(new Matrix3f().set(x, y, z));
        return true;
    }

    /** The part of one vector at right angles to a unit axis, or null where there is none. */
    private static Vector3f perpendicular(Vector3f vector, Vector3f axis) {
        Vector3f apart = new Vector3f(vector).sub(new Vector3f(axis).mul(vector.dot(axis)));
        return apart.lengthSquared() < 1.0e-6f ? null : apart.normalize();
    }

    private Vector3f towardsCamera(float partialTick) {
        Vec3 eye = Platform.cameraPosition();
        return new Vector3f(
            (float) (eye.x - Mth.lerp(partialTick, xo, x)),
            (float) (eye.y - Mth.lerp(partialTick, yo, y)),
            (float) (eye.z - Mth.lerp(partialTick, zo, z))).normalize();
    }
    //?}

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
