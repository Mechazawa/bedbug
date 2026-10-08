package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Quaternionf;

import java.util.List;

/**
 * A pack's particle effect started by `/particle` makes particles the way its file describes.
 *
 * The effect is quoted, which is how a pack's own command reaches the server once it is translated:
 * unquoted, the colon ends the mod's own argument and Java's `/particle` answers instead, with one
 * particle and no emitter.
 *
 * `tp:burst` emits all 24 of its particles at once from a point a fifth of a block above where it
 * was started, each leaving at three blocks a second, living 0.9 seconds, a tenth of a block across,
 * facing the camera and tinted `#FFB4E2FF`, which Bedrock writes alpha first.
 *
 * `tp:ember_trail` lives 1.2 seconds, turns only about the vertical to face the camera, and is
 * coloured from a gradient read at its age over its lifetime: a warm white at birth and `#FFE06010`
 * at 0.4, blended in between. Its emitter starts `tp:burst` as it is created and again from its own
 * timeline two seconds in, which is an effect starting another while the running ones are advanced.
 *
 * `tp:fade` is painted and sized by curves, one of each of the four kinds.
 */
public final class ParticleClientGameTest implements FabricClientGameTest {
    private static final double SPEED_TOLERANCE = 1e-4;
    private static final int PATIENCE = 100;

    private static final int BURST_COUNT = 24;
    /** `tp:burst`'s three blocks a second, in the blocks a tick Java moves a particle by. */
    private static final double BURST_SPEED = 3.0 / 20;
    private static final int BURST_LIFETIME = 18;
    private static final double BURST_OFFSET = 0.2;
    private static final float BURST_SIZE = 0.1f;
    private static final int BURST_TINT = 0xFFB4E2FF;

    private static final int TRAIL_LIFETIME = 24;

    /** `tp:ember_trail`'s first two gradient stops, red, green, blue and alpha, and where the second is. */
    private static final float[] TRAIL_BIRTH = {1.0f, 0.85f, 0.35f, 1.0f};
    private static final float[] TRAIL_LATER = {0xE0 / 255f, 0x60 / 255f, 0x10 / 255f, 1.0f};
    private static final float TRAIL_LATER_AT = 0.4f;

    private static final float FADE_LIFETIME = 2.0f;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            burst(context, world);
            unquoted(context, world);
            trail(context, world);
            curves(context, world);
        }
    }

    /**
     * `tp:fade` makes one particle that stands still for two seconds while four curves read off
     * `variable.particle_age` paint it: a linear curve its alpha, a bezier its red, a catmull-rom its
     * green and a bezier chain its size. The chain carries a `horizontal_range` of two, which
     * Bedrock ignores on a chain, so its nodes are read at the age itself.
     *
     * What the particle hands the frame is compared at two ages with each curve worked out here from
     * its own definition.
     */
    private static void curves(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("execute as @p at @s run particle \"tp:fade\" ~ ~1 ~3");
        Worlds.waitForClient(context, client -> !Born.of("tp:fade").isEmpty(), PATIENCE,
            () -> "tp:fade emits one particle at once and none was made.");
        Born.Particle fade = Born.of("tp:fade").get(0);

        for (float age : new float[] {0.5f, 1.4f}) {
            Worlds.waitForClient(context, client -> fade.age() >= age, PATIENCE,
                () -> "tp:fade's particle lives two seconds and never reached " + age + " of them.");
            Faded faded = context.computeOnClient(client ->
                new Faded(fade.age(), Born.drawn(fade.particle(), Worlds.camera(client))));
            float at = faded.age();
            float along = at / FADE_LIFETIME;

            Assert.near(faded.quad().size(), chain(at), 1e-4,
                "tp:fade's size at " + at + " seconds, its bezier chain read at the age itself");
            int wanted = Math.round(bezier(at / 2) * 255) << 16
                | Math.round(catmullRom(along) * 255) << 8
                | 0xFF
                | Math.round(linear(along) * 255) << 24;
            assertColour(faded.quad().colour(), wanted, "tp:fade's colour at " + at + " seconds, its red a"
                + " bezier, its green a catmull-rom and its alpha a linear curve");
        }
    }

    private record Faded(float age, Born.Quad quad) {
    }

    /** Three nodes evenly spread from the start of the particle's life to its end. */
    private static float linear(float along) {
        float[] nodes = {1.0f, 0.8f, 0.2f};
        float step = Math.min(along, 1f) * (nodes.length - 1);
        int from = Math.min((int) step, nodes.length - 2);
        return nodes[from] + (nodes[from + 1] - nodes[from]) * (step - from);
    }

    private static float bezier(float along) {
        float rest = 1 - along;
        return rest * rest * rest * 0.1f + 3 * rest * rest * along * 0.9f + 3 * rest * along * along * 0.2f
            + along * along * along * 1.0f;
    }

    /** Five nodes, so two segments run through the middle three with the outer two setting their slopes. */
    private static float catmullRom(float along) {
        float[] nodes = {0.0f, 0.2f, 0.6f, 0.8f, 1.0f};
        float step = along * 2;
        int segment = Math.min((int) step, 1);
        float t = step - segment;
        float before = nodes[segment];
        float from = nodes[segment + 1];
        float to = nodes[segment + 2];
        float after = nodes[segment + 3];
        return 0.5f * (2 * from + (to - before) * t + (2 * before - 5 * from + 4 * to - after) * t * t
            + (3 * from - before - 3 * to + after) * t * t * t);
    }

    /** Knots at 0, 1 and 2 seconds with values 0.05, 0.2 and 0.3 and slopes 0, 0.1 and -0.1 a second. */
    private static float chain(float seconds) {
        float[][] knots = {{0.05f, 0f}, {0.2f, 0.1f}, {0.3f, -0.1f}};
        int segment = Math.min((int) seconds, 1);
        float t = seconds - segment;
        float[] from = knots[segment];
        float[] to = knots[segment + 1];
        return (2 * t * t * t - 3 * t * t + 1) * from[0] + (t * t * t - 2 * t * t + t) * from[1]
            + (-2 * t * t * t + 3 * t * t) * to[0] + (t * t * t - t * t) * to[1];
    }

    /**
     * The same effect typed the way an operator types it, unquoted, which Java's own `/particle`
     * reads as a particle type: what comes of it is the whole emitter rather than one particle.
     */
    private static void unquoted(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("execute as @p at @s run particle tp:burst ~ ~1 ~3");
        Worlds.waitForClient(context, client -> Born.of("tp:burst").size() >= BURST_COUNT, PATIENCE,
            () -> "particle tp:burst typed unquoted starts the pack's emitter of " + BURST_COUNT
                + " particles, and " + Born.of("tp:burst").size() + " were made.");
    }

    private static void burst(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("execute as @p at @s run particle \"tp:burst\" ~ ~1 ~3");
        Worlds.waitForClient(context, client -> Born.of("tp:burst").size() >= BURST_COUNT, PATIENCE,
            () -> "tp:burst emits " + BURST_COUNT + " particles at once, and " + Born.of("tp:burst").size()
                + " were made.");
        context.waitTicks(2);
        List<Born.Particle> burst = Born.of("tp:burst");
        Assert.that(burst.size() == BURST_COUNT, "tp:burst emits its particles once, and " + burst.size()
            + " were made.");

        double floor = burst.stream().mapToDouble(born -> born.at().y).min().orElseThrow();
        for (Born.Particle born : burst) {
            Assert.near(born.velocity().length(), BURST_SPEED, SPEED_TOLERANCE,
                "the speed tp:burst's particle_initial_speed of 3 starts a particle at, in blocks a tick");
            Assert.that(born.lifetime() == BURST_LIFETIME, "tp:burst's particles live 0.9 seconds, and one was"
                + " given " + born.lifetime() + " ticks.");
            Assert.near(born.at().y, floor, 1e-6, "the height every tp:burst particle starts at, a point emitter's");
        }
        Assert.near(floor - Math.floor(floor), BURST_OFFSET, 1e-3,
            "how far above the block it was started at tp:burst puts its particles");

        Born.Particle first = burst.get(0);
        Born.Quad quad = context.computeOnClient(client ->
            Born.drawn(first.particle(), Worlds.camera(client)));
        Quaternionf facing = context.computeOnClient(client -> new Quaternionf(Worlds.camera(client).rotation()));
        Assert.near(quad.size(), BURST_SIZE, 1e-4, "the size a tp:burst particle is drawn at");
        Assert.that(quad.rotation().equals(facing, 1e-4f), "tp:burst faces the camera with lookat_xyz, and was"
            + " turned " + quad.rotation() + " against the camera's " + facing + ".");
        assertColour(quad.colour(), BURST_TINT, "tp:burst's tint");
    }

    private static void trail(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("execute as @p at @s run particle \"tp:ember_trail\" ~ ~1 ~3");
        Worlds.waitForClient(context, client -> Born.of("tp:ember_trail").size() >= 3, PATIENCE,
            () -> "tp:ember_trail emits twenty particles a second and none were made.");

        Born.Particle first = Born.of("tp:ember_trail").get(0);
        Assert.that(first.lifetime() == TRAIL_LIFETIME, "tp:ember_trail's particles live 1.2 seconds, and one was"
            + " given " + first.lifetime() + " ticks.");

        context.waitTicks(4);
        float progress = context.computeOnClient(client -> first.progress());
        Assert.that(progress > 0 && progress < TRAIL_LATER_AT, "the trail particle was asked about at "
            + progress + " of its life, outside its gradient's first span.");
        Born.Quad quad = context.computeOnClient(client ->
            Born.drawn(first.particle(), Worlds.camera(client)));
        Assert.near(quad.rotation().x, 0, 1e-4, "the pitch of a tp:ember_trail particle, which rotate_y keeps upright");
        Assert.near(quad.rotation().z, 0, 1e-4, "the roll of a tp:ember_trail particle, which rotate_y keeps upright");

        float share = progress / TRAIL_LATER_AT;
        int expected = 0;
        for (int channel = 0; channel < 4; channel++) {
            float value = TRAIL_BIRTH[channel] + (TRAIL_LATER[channel] - TRAIL_BIRTH[channel]) * share;
            int shift = channel == 3 ? 24 : 16 - channel * 8;
            expected |= Math.round(value * 255) << shift;
        }
        assertColour(quad.colour(), expected, "tp:ember_trail's colour at " + progress + " of its life,"
            + " between its gradient's first two stops");

        Worlds.waitForClient(context, client -> Born.of("tp:burst").size() >= 2 * BURST_COUNT, PATIENCE,
            () -> "tp:ember_trail starts tp:burst as it is created and again two seconds in, from its own"
                + " timeline, and " + Born.of("tp:burst").size() + " burst particles were made.");
    }

    /** Each of the four channels within one step of the colour asked for. */
    private static void assertColour(int found, int wanted, String what) {
        for (int shift = 0; shift < 32; shift += 8) {
            if (Math.abs((found >>> shift & 0xFF) - (wanted >>> shift & 0xFF)) > 1) {
                throw new AssertionError("Expected " + what + " to be " + Integer.toHexString(wanted)
                    + " and found " + Integer.toHexString(found) + ".");
            }
        }
    }
}
