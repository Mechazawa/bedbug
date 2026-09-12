package at.ioexception.mcaddon.bedrock.particle;

import at.ioexception.mcaddon.bedrock.animation.Vector3Expression;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.pack.BedrockPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is read out of an effect a pack defines itself, and what a file that cannot be read costs.
 *
 * Two things decide most of this. Bedrock takes a Molang string anywhere it takes a number, so a
 * reader that called `getAsFloat` would lose every pack that writes one. And a shape's direction is
 * three values or one of two words, where the words name a direction that differs per particle and
 * cannot be folded into a vector.
 */
class ParticleDefinitionTest {
    private final MolangEnvironment environment = new MolangEnvironment();

    @TempDir
    Path directory;

    @Test
    void readsTheMaterialAndTextureTheEffectIsDrawnWith() throws IOException {
        ParticleDefinition effect = effect("{}");

        assertEquals("vn:sparkle", effect.identifier());
        assertEquals("particles_alpha", effect.material());
        assertEquals("textures/particle/sparkle", effect.texture());
    }

    @Test
    void leavesEveryComponentThePackDeclaresNoneOfEmpty() throws IOException {
        ParticleDefinition effect = effect("{}");

        assertNull(effect.emitter().rate());
        assertNull(effect.emitter().shape());
        assertNull(effect.emitter().lifetime());
        assertNull(effect.emitter().localSpace());
        assertNull(effect.emitter().initialization());
        assertNull(effect.particle().billboard());
        assertNull(effect.particle().tint());
        assertNull(effect.particle().killPlane());
        assertEquals(List.of(), effect.particle().expireInBlocks());
    }

    /** Every number in a particle file may arrive as Molang, and a rate is the one read every tick. */
    @Test
    void readsANumberWrittenAsMolang() throws IOException {
        EmitterRate.Steady rate = (EmitterRate.Steady) effect("""
            {"minecraft:emitter_rate_steady": {"spawn_rate": "1 + 2", "max_particles": 7}}
            """).emitter().rate();

        assertEquals(3, value(rate.spawnRate()));
        assertEquals(7, value(rate.maxParticles()));
    }

    @Test
    void fallsBackToBedrocksOwnValueForAFieldTheComponentLeavesOut() throws IOException {
        EmitterRate.Steady rate = (EmitterRate.Steady) effect("""
            {"minecraft:emitter_rate_steady": {}}
            """).emitter().rate();

        assertEquals(1, value(rate.spawnRate()));
        assertEquals(50, value(rate.maxParticles()));
    }

    @Test
    void readsTheInstantAndManualRates() throws IOException {
        EmitterRate.Instant instant = (EmitterRate.Instant) effect("""
            {"minecraft:emitter_rate_instant": {"num_particles": 4}}
            """).emitter().rate();
        assertEquals(4, value(instant.numParticles()));

        EmitterRate.Manual manual = (EmitterRate.Manual) effect("""
            {"minecraft:emitter_rate_manual": {"max_particles": 9}}
            """).emitter().rate();
        assertEquals(9, value(manual.maxParticles()));
    }

    @Test
    void readsEachLifetimeForm() throws IOException {
        EmitterLifetime.Looping looping = (EmitterLifetime.Looping) effect("""
            {"minecraft:emitter_lifetime_looping": {"active_time": 2, "sleep_time": 3}}
            """).emitter().lifetime();
        assertEquals(2, value(looping.activeTime()));
        assertEquals(3, value(looping.sleepTime()));

        EmitterLifetime.Once once = (EmitterLifetime.Once) effect("""
            {"minecraft:emitter_lifetime_once": {"active_time": 5}}
            """).emitter().lifetime();
        assertEquals(5, value(once.activeTime()));

        EmitterLifetime.Expression expression = (EmitterLifetime.Expression) effect("""
            {"minecraft:emitter_lifetime_expression":
                {"activation_expression": "0", "expiration_expression": "1"}}
            """).emitter().lifetime();
        assertEquals(0, value(expression.activation()));
        assertEquals(1, value(expression.expiration()));
    }

    @Test
    void readsWhichOfTheEmittersMovementsTheParticlesFollow() throws IOException {
        ParticleDefinition.Emitter.LocalSpace space = effect("""
            {"minecraft:emitter_local_space": {"position": true, "velocity": true}}
            """).emitter().localSpace();

        assertTrue(space.position());
        assertFalse(space.rotation());
        assertTrue(space.velocity());
    }

    @Test
    void readsTheEmittersOwnMolang() throws IOException {
        ParticleDefinition.Emitter.Initialization initialization = effect("""
            {"minecraft:emitter_initialization":
                {"creation_expression": "2", "per_update_expression": "3"}}
            """).emitter().initialization();

        assertEquals(2, value(initialization.creation()));
        assertEquals(3, value(initialization.perUpdate()));
    }

    /** Outwards and inwards are directions relative to the shape, so neither becomes a vector. */
    @ParameterizedTest
    @CsvSource({"'\"outwards\"', true", "'\"inwards\"', false"})
    void readsADirectionWrittenAsAWord(String direction, boolean outwards) throws IOException {
        EmitterShape.Sphere sphere = (EmitterShape.Sphere) effect("""
            {"minecraft:emitter_shape_sphere": {"direction": %s}}
            """.formatted(direction)).emitter().shape();

        assertEquals(outwards, ((EmitterDirection.Relative) sphere.direction()).outwards());
    }

    @Test
    void readsADirectionWrittenAsAVector() throws IOException {
        EmitterShape.Sphere sphere = (EmitterShape.Sphere) effect("""
            {"minecraft:emitter_shape_sphere": {"direction": [0, "1 + 1", -3]}}
            """).emitter().shape();

        assertVector(0, 2, -3, ((EmitterDirection.Vector) sphere.direction()).direction());
    }

    /** A shape that says nothing about direction throws its particles away from its centre. */
    @Test
    void throwsParticlesOutwardsWhenTheShapeNamesNoDirection() throws IOException {
        EmitterShape.Point point = (EmitterShape.Point) effect("""
            {"minecraft:emitter_shape_point": {"offset": [1, 2, 3]}}
            """).emitter().shape();

        assertTrue(((EmitterDirection.Relative) point.direction()).outwards());
        assertVector(1, 2, 3, point.offset());
    }

    /** `x`, `y` and `z` name the unit vector along that axis, which is all the word carries. */
    @ParameterizedTest
    @CsvSource({"x, 1, 0, 0", "y, 0, 1, 0", "z, 0, 0, 1"})
    void readsAPlaneNormalWrittenAsAnAxis(String axis, float x, float y, float z) throws IOException {
        EmitterShape.Disc disc = (EmitterShape.Disc) effect("""
            {"minecraft:emitter_shape_disc": {"plane_normal": "%s"}}
            """.formatted(axis)).emitter().shape();

        assertVector(x, y, z, disc.planeNormal());
    }

    @Test
    void readsAPlaneNormalWrittenAsAVector() throws IOException {
        EmitterShape.Disc disc = (EmitterShape.Disc) effect("""
            {"minecraft:emitter_shape_disc": {"plane_normal": [1, 0, 1], "radius": 4}}
            """).emitter().shape();

        assertVector(1, 0, 1, disc.planeNormal());
        assertEquals(4, value(disc.radius()));
    }

    @Test
    void laysADiscFlatWhenItNamesNoPlane() throws IOException {
        EmitterShape.Disc disc = (EmitterShape.Disc) effect("""
            {"minecraft:emitter_shape_disc": {"radius": 1}}
            """).emitter().shape();

        assertVector(0, 1, 0, disc.planeNormal());
    }

    @Test
    void readsTheBoxAndEntityShapes() throws IOException {
        EmitterShape.Box box = (EmitterShape.Box) effect("""
            {"minecraft:emitter_shape_box":
                {"offset": [0, 1, 0], "half_dimensions": [2, 2, 2], "surface_only": true}}
            """).emitter().shape();
        assertVector(2, 2, 2, box.halfDimensions());
        assertTrue(box.surfaceOnly());

        EmitterShape.EntityAabb aabb = (EmitterShape.EntityAabb) effect("""
            {"minecraft:emitter_shape_entity_aabb": {"surface_only": false, "direction": "inwards"}}
            """).emitter().shape();
        assertFalse(aabb.surfaceOnly());
        assertFalse(((EmitterDirection.Relative) aabb.direction()).outwards());
    }

    /**
     * One value is a speed along the direction the shape gave the particle, three are a velocity.
     *
     * A reader that answered with three components either way would turn a speed into a movement
     * along one axis, which is a different effect and not a slower one.
     */
    @Test
    void tellsASpeedApartFromAVelocity() throws IOException {
        InitialSpeed scalar = effect("{\"minecraft:particle_initial_speed\": 3}").particle().initialSpeed();
        assertEquals(3, value(((InitialSpeed.Scalar) scalar).speed()));

        InitialSpeed vector = effect("""
            {"minecraft:particle_initial_speed": [0, "2 * 2", 0]}
            """).particle().initialSpeed();
        assertVector(0, 4, 0, ((InitialSpeed.Vector) vector).velocity());
    }

    @Test
    void readsTheMotionComponents() throws IOException {
        ParticleDefinition.Particle particle = effect("""
            {
              "minecraft:particle_motion_dynamic": {
                "linear_acceleration": [0, -9.8, 0], "linear_drag_coefficient": 1,
                "rotation_acceleration": 2, "rotation_drag_coefficient": 3
              },
              "minecraft:particle_motion_collision": {
                "enabled": "1", "collision_drag": 4, "coefficient_of_restitution": 0.5,
                "collision_radius": 0.1, "expire_on_contact": true
              },
              "minecraft:particle_initial_spin": {"rotation": 45, "rotation_rate": 6}
            }
            """).particle();

        assertVector(0, -9.8f, 0, particle.dynamic().linearAcceleration());
        assertEquals(2, value(particle.dynamic().rotationAcceleration()));
        assertEquals(0.5f, value(particle.collision().restitution()));
        assertTrue(particle.collision().expireOnContact());
        assertEquals(45, value(particle.spin().rotation()));
    }

    @Test
    void readsParametricMotionAndLifetime() throws IOException {
        ParticleDefinition.Particle particle = effect("""
            {
              "minecraft:particle_motion_parametric": {
                "relative_position": [1, 1, 1], "direction": [0, 0, 1], "rotation": 90
              },
              "minecraft:particle_lifetime_expression": {"max_lifetime": 2, "expiration_expression": "0"}
            }
            """).particle();

        assertVector(0, 0, 1, particle.parametric().direction());
        assertEquals(90, value(particle.parametric().rotation()));
        assertEquals(2, value(particle.lifetime().maxLifetime()));
    }

    @Test
    void readsTheBlocksAParticleDiesInOrOutOf() throws IOException {
        ParticleDefinition.Particle particle = effect("""
            {
              "minecraft:particle_expire_if_in_blocks": ["minecraft:water", "minecraft:lava"],
              "minecraft:particle_expire_not_in_blocks": ["minecraft:air"]
            }
            """).particle();

        assertEquals(List.of("minecraft:water", "minecraft:lava"), particle.expireInBlocks());
        assertEquals(List.of("minecraft:air"), particle.expireNotInBlocks());
    }

    /** A plane is the four coefficients of its equation, and anything else is not a plane. */
    @Test
    void readsAKillPlaneOnlyWhenItHasFourCoefficients() throws IOException {
        ParticleDefinition.Particle.KillPlane plane = effect("""
            {"minecraft:particle_kill_plane": [0, 1, 0, -2]}
            """).particle().killPlane();

        assertEquals(1, plane.b());
        assertEquals(-2, plane.d());
        assertNull(effect("{\"minecraft:particle_kill_plane\": [0, 1, 0]}").particle().killPlane());
    }

    /** A pack that writes a component as something other than an object loses that component. */
    @Test
    void skipsAComponentWrittenInAShapeItCannotHave() throws IOException {
        ParticleDefinition effect = effect("""
            {"minecraft:emitter_rate_steady": "every now and then"}
            """);

        assertNull(effect.emitter().rate());
    }

    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "{\"other\": {}} -> not an effect",
        "{\"particle_effect\": {}} -> no description",
        "{\"particle_effect\": {\"description\": {}}} -> no identifier",
        "{\"particle_effect\": \"sparkle\"} -> an effect that is not an object",
        "this is not json at all -> unreadable",
    })
    void answersNullForAFileItCannotRead(String body, String reason) throws IOException {
        assertNull(ParticleDefinition.tryFrom(write("particles/broken.json", body)), reason);
    }

    /**
     * A particle file arrives with a byte order mark, comments and trailing commas in it.
     *
     * Strict JSON has room for none of the three, and a pack tool writes all of them. A trailing
     * comma leaves an empty entry behind where the list held names, which is why a block list is
     * read by what is in it rather than by how long it is.
     */
    @Test
    void readsTheJsonDialectPacksAreWrittenIn() throws IOException {
        ParticleDefinition effect = ParticleDefinition.tryFrom(write("particles/sparkle.json",
            "\ufeff" + """
            {
              // the effect the sign post gives off
              "particle_effect": {
                "description": {"identifier": "vn:sparkle"},
                "components": {
                  "minecraft:emitter_rate_instant": {"num_particles": 2},
                  "minecraft:particle_expire_if_in_blocks": ["minecraft:water",]
                }
              }
            }
            """));

        assertEquals("vn:sparkle", effect.identifier());
        assertEquals(2, value(((EmitterRate.Instant) effect.emitter().rate()).numParticles()));
        assertEquals(List.of("minecraft:water"), effect.particle().expireInBlocks());
    }

    /** One unreadable file costs that effect. The pack keeps the rest of what it ships. */
    @Test
    void readsEveryEffectAPackShipsAndKeepsGoingPastABrokenOne() throws IOException {
        write("particles/sparkle.json", effectSource("vn:sparkle", "{}"));
        write("particles/smoke.json", effectSource("vn:smoke", "{}"));
        write("particles/broken.json", "{\"particle_effect\": {}}");

        Map<String, ParticleDefinition> effects =
            ParticleDefinition.readAll(new BedrockPack(directory, null, "test.mcaddon"));

        assertEquals(List.of("vn:smoke", "vn:sparkle"), effects.keySet().stream().sorted().toList());
        assertEquals("particles_alpha", effects.get("vn:sparkle").material());
    }

    private ParticleDefinition effect(String components) throws IOException {
        return ParticleDefinition.tryFrom(
            write("particles/sparkle.json", effectSource("vn:sparkle", components)));
    }

    private static String effectSource(String identifier, String components) {
        return """
            {
              "format_version": "1.10.0",
              "particle_effect": {
                "description": {
                  "identifier": "%s",
                  "basic_render_parameters": {
                    "material": "particles_alpha",
                    "texture": "textures/particle/sparkle"
                  }
                },
                "components": %s
              }
            }
            """.formatted(identifier, components);
    }

    private Path write(String path, String body) throws IOException {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }

    private float value(MolangExpression expression) {
        return expression.asFloat(environment);
    }

    private void assertVector(float x, float y, float z, Vector3Expression vector) {
        assertEquals(x, value(vector.x()), 1e-5);
        assertEquals(y, value(vector.y()), 1e-5);
        assertEquals(z, value(vector.z()), 1e-5);
    }
}
