package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.joml.Quaternionf;

import java.util.List;

/**
 * A pack's particle effect started by Bedrock's `/particle` makes particles the way its file
 * describes, and Java's own `/particle` stays Java's.
 *
 * `tp:burst` emits all 24 of its particles at once from a point a fifth of a block above where it
 * was started, each leaving at three blocks a second, living 0.9 seconds, a tenth of a block across,
 * facing the camera and tinted `#FFB4E2FF`, which Bedrock writes alpha first.
 *
 * `tp:ember_trail` lives 1.2 seconds, turns only about the vertical to face the camera, and is
 * coloured from a gradient read at its age over its lifetime: a warm white at birth and `#FFE06010`
 * at 0.4, blended in between. Its emitter starts `tp:burst` as it is created and again from its own
 * timeline two seconds in, which is an effect starting another while the running ones are advanced.
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

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(ui -> ui.setAllowCommands(true)).create()) {
            Worlds.waitForChunks(world);
            burst(context, world);
            typed(context, world);
            javas(context, world);
            trail(context, world);
        }
    }

    /** The same effect typed by an operator, in Bedrock's words, starts the whole emitter. */
    private static void typed(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        context.runOnClient(client -> client.player.connection.sendCommand("bedrock particle tp:burst ~ ~1 ~3"));
        Worlds.waitForClient(context, client -> Born.of("tp:burst").size() >= BURST_COUNT, PATIENCE,
            () -> "/bedrock particle tp:burst typed by the player starts the pack's emitter of " + BURST_COUNT
                + " particles, and " + Born.of("tp:burst").size() + " were made.");
    }

    /**
     * Java's own `/particle` reads the effect as the particle type it is registered as, and makes the
     * one particle Java makes of any type rather than the effect's emitter.
     */
    private static void javas(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("execute as @p at @s run particle tp:burst ~ ~1 ~3");
        Worlds.waitForClient(context, client -> !Born.of("tp:burst").isEmpty(), PATIENCE,
            () -> "Java's /particle tp:burst made no particle at all.");
        context.waitTicks(5);
        int made = context.computeOnClient(client -> Born.of("tp:burst").size());
        Assert.that(made == 1, "Java's /particle tp:burst made " + made + " particles rather than Java's one.");
    }

    private static void burst(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:burst ~ ~1 ~3");
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
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:ember_trail ~ ~1 ~3");
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
