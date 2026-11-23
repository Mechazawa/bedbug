package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.AddonEmitters;
import at.ioexception.mcaddon.client.render.AddonParticle;
import at.ioexception.mcaddon.clientgametest.mixin.ParticleMotionAccessor;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
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
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(ui -> ui.setAllowCommands(true)).create()) {
            Worlds.waitForChunks(world);
            burst(context, world);
            typed(context, world);
            javas(context, world);
            trail(context, world);
            curves(context, world);
            scoped(context, world);
            looped(context, world);
            capped(context, world);
            settling(context, world);
            flare(context, world);
            thud(context, world);
            bounce(context, world);
            skid(context, world);
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
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:fade ~ ~1 ~3");
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

    /**
     * `tp:sized` sets `variable.size` to a half in its emitter's `creation_expression` and sizes its
     * billboard by it, so its particle reads the emitter's variables.
     */
    private static void scoped(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:sized ~ ~1 ~3");
        Worlds.waitForClient(context, client -> !Born.of("tp:sized").isEmpty(), PATIENCE,
            () -> "tp:sized emits one particle at once and none was made.");
        Born.Particle sized = Born.of("tp:sized").get(0);
        float size = context.computeOnClient(client -> Born.drawn(sized.particle(), Worlds.camera(client)).size());
        Assert.near(size, 0.5f, 1e-4, "the size tp:sized's particle reads from the variable its emitter set");
    }

    /**
     * `tp:looped` loops for a random 0.4 to 0.6 seconds at a time and never rests. Its particles are
     * sized by `variable.emitter_age` and reddened by `variable.emitter_random_1` as they are born,
     * so a particle of a later loop stays small and the loops do not all share one red.
     *
     * `tp:pulse` bursts two particles at the start of every quarter second loop, with no rest
     * between loops.
     */
    private static void looped(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:looped ~ ~1 ~3");
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:pulse ~1 ~1 ~3");
        Worlds.waitForClient(context, client -> Born.of("tp:looped").size() >= 20, PATIENCE,
            () -> "tp:looped makes ten particles a second and made " + Born.of("tp:looped").size() + ".");
        List<Born.Quad> quads = context.computeOnClient(client -> Born.of("tp:looped").stream()
            .map(born -> Born.drawn(born.particle(), Worlds.camera(client))).toList());
        for (Born.Quad quad : quads) {
            float emitterAge = (quad.size() - 0.01f) / 0.1f;
            Assert.that(emitterAge <= 0.65f, "tp:looped's particle was born at an emitter age of " + emitterAge
                + ", past the longest loop of 0.6 seconds.");
        }
        long reds = quads.stream().map(quad -> quad.colour() >>> 16 & 0xFF).distinct().count();
        Assert.that(reds >= 2, "tp:looped rolls its emitter randoms again every loop, and its particles"
            + " over three loops carry " + reds + " red.");

        int pulses = context.computeOnClient(client -> Born.of("tp:pulse").size());
        Assert.that(pulses >= 6, "tp:pulse bursts two particles every quarter second loop, and made " + pulses
            + " in two seconds.");
        context.runOnClient(client -> AddonEmitters.clear());
    }

    /**
     * `tp:capped` makes twenty particles a second, each living a tenth of a second, and allows five
     * alive at once, so it keeps making them for as long as it runs.
     */
    private static void capped(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:capped ~ ~1 ~3");
        Worlds.waitForClient(context, client -> Born.of("tp:capped").size() >= 30, PATIENCE,
            () -> "tp:capped keeps at most five particles alive at once, each living a tenth of a second,"
                + " and stopped after " + Born.of("tp:capped").size() + ".");
        context.runOnClient(client -> AddonEmitters.clear());
    }

    /** `tp:settle`'s particle collides only once `variable.particle_age` is past a fifth of a second. */
    private static void settling(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:settle ~ ~1 ~3");
        Worlds.waitForClient(context, client -> !Born.of("tp:settle").isEmpty(), PATIENCE,
            () -> "tp:settle emits one particle at once and none was made.");
        Born.Particle settle = Born.of("tp:settle").get(0);
        boolean early = context.computeOnClient(client -> settle.age() < 0.2f && !settle.collides());
        Assert.that(early, "tp:settle's particle collided before its collision turned on at 0.2 seconds.");
        Worlds.waitForClient(context, client -> settle.age() >= 0.3f, PATIENCE,
            () -> "tp:settle's particle lives a second and never reached 0.3 of it.");
        Assert.that(context.computeOnClient(client -> settle.collides()),
            "tp:settle's collision is evaluated every frame and was still off at 0.3 seconds.");
    }

    /**
     * `tp:flare`'s clip starts three effects. `tp:puff` has a `pre_effect_script` setting
     * `variable.burst` to three, which the effect's instant rate reads, so the script runs in the
     * emitter. `tp:wake` and `tp:wake_loose` both start at the `tip` locator while the clip lifts the
     * body two blocks over a second: the wake is bound and rises with the locator and follows the
     * flare when it is moved, and the loose one is not bound and stays where it started. The wake
     * starts `tp:wake_child` as it is created through an `emitter_bound` event, which binds the child to
     * the same locator. `tp:fling` takes on the rising locator's velocity, and `tp:tether`'s particles
     * move in the flare's space, so they follow it when it is moved.
     */
    private static void flare(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("execute as @p at @s run summon tp:flare ~ ~ ~3");
        Worlds.waitForClient(context, client -> Born.of("tp:puff").size() >= 3, PATIENCE,
            () -> "tp:flare's clip starts tp:puff with a pre_effect_script asking for three particles, and "
                + Born.of("tp:puff").size() + " were made.");
        context.waitTicks(5);
        int puffs = context.computeOnClient(client -> Born.of("tp:puff").size());
        Assert.that(puffs == 3, "tp:puff bursts the three particles its pre_effect_script asked for once, and made "
            + puffs + ".");

        Worlds.waitForClient(context, client -> Born.of("tp:wake").size() >= 12, PATIENCE,
            () -> "tp:wake makes ten particles a second and made " + Born.of("tp:wake").size() + ".");
        double rise = context.computeOnClient(client -> spread(Born.of("tp:wake")));
        Assert.that(rise > 1.5, "tp:wake follows its locator as the clip lifts it two blocks, and its particles"
            + " were born " + rise + " blocks apart in height.");
        double childRise = context.computeOnClient(client -> spread(Born.of("tp:wake_child")));
        Assert.that(childRise > 1.5, "tp:wake_child is started by tp:wake as emitter_bound, so it follows the"
            + " same locator as the clip lifts it two blocks, and its particles were born " + childRise
            + " blocks apart in height.");
        double flung = context.computeOnClient(client -> Born.of("tp:fling").stream()
            .mapToDouble(born -> born.velocity().y).max().orElse(0));
        Assert.that(flung > 0.05, "tp:fling takes on its emitter's velocity as the clip lifts the tip two blocks a"
            + " second, and its fastest particle rose at " + flung * 20 + " blocks a second.");

        AddonEntity flare = Entities.waitForClient(context, "tp:flare");
        double from = context.computeOnClient(client -> flare.getX());
        int tethers = context.computeOnClient(client -> Born.of("tp:tether").size());
        world.getServer().runCommand("execute as @e[type=tp:flare] at @s run tp @s ~6 ~ ~");
        Worlds.waitForClient(context, client -> flare.getX() > from + 5.9, PATIENCE,
            () -> "tp:flare was moved six blocks and the client never saw it arrive.");
        context.waitTicks(3);
        int wakes = context.computeOnClient(client -> Born.of("tp:wake").size());
        int looses = context.computeOnClient(client -> Born.of("tp:wake_loose").size());
        context.waitTicks(6);
        double to = context.computeOnClient(client -> flare.getX());
        List<Born.Particle> moved = context.computeOnClient(client ->
            Born.of("tp:wake").subList(wakes, Born.of("tp:wake").size()));
        List<Born.Particle> stayed = context.computeOnClient(client ->
            Born.of("tp:wake_loose").subList(looses, Born.of("tp:wake_loose").size()));
        Assert.that(!moved.isEmpty() && moved.stream().allMatch(born -> Math.abs(born.at().x - to) < 1),
            "tp:wake is bound to the flare and was born away from it after it moved.");
        Assert.that(!stayed.isEmpty() && stayed.stream().allMatch(born -> Math.abs(born.at().x - from) < 1),
            "tp:wake_loose is not bound to the flare and followed it when it moved.");
        List<Born.Particle> carried = context.computeOnClient(client -> Born.of("tp:tether").subList(0, tethers));
        Assert.that(!carried.isEmpty() && carried.stream().allMatch(born ->
                Math.abs(born.particle().getBoundingBox().getCenter().x - to) < 1.5),
            "tp:tether moves in the flare's space and its particles were left behind when the flare moved.");
        turned(context, world, flare);
        world.getServer().runCommand("kill @e[type=tp:flare]");
        context.runOnClient(client -> AddonEmitters.clear());
    }

    /**
     * `tp:tether`'s particles move in the flare's space, rotation included, so one standing beside the
     * flare is carried round it by as far as the flare's body turns.
     */
    private static void turned(ClientGameTestContext context, TestSingleplayerContext world, AddonEntity flare) {
        AddonParticle held = context.computeOnClient(client -> Born.of("tp:tether").getLast().particle());
        Vec3 before = context.computeOnClient(client -> held.getBoundingBox().getCenter().subtract(flare.position()));
        float yawBefore = context.computeOnClient(client -> flare.yBodyRot);
        world.getServer().runCommand("execute as @e[type=tp:flare] at @s run tp @s ~ ~ ~ ~90 ~");
        Worlds.waitForClient(context, client -> Math.abs(flare.getYRot() - yawBefore) > 89, PATIENCE,
            () -> "tp:flare was turned a quarter and the client never saw it turn.");
        // A teleport turns the entity and leaves its head and body to follow later, so they are turned with it.
        context.runOnClient(client -> {
            flare.setYHeadRot(flare.getYRot());
            flare.setYBodyRot(flare.getYRot());
        });
        context.waitTicks(10);
        Vec3 after = context.computeOnClient(client -> held.getBoundingBox().getCenter().subtract(flare.position()));
        float turn = context.computeOnClient(client -> flare.yBodyRot) - yawBefore;
        Assert.that(Math.abs(turn) > 30, "tp:flare was turned a quarter, and its body turned " + turn + " degrees.");

        double cos = Math.cos(Math.toRadians(turn));
        double sin = Math.sin(Math.toRadians(turn));
        Vec3 wanted = new Vec3(before.x * cos - before.z * sin, after.y, before.x * sin + before.z * cos);
        Assert.that(after.distanceTo(wanted) < 0.15, "tp:tether turns with the flare's body by " + turn
            + " degrees, from " + before + " to " + wanted + ", and its particle stood at " + after + ".");
    }

    /**
     * `tp:thud_soft` lands at about a block a second and `tp:thud_hard` at about ten. Both raise an event
     * starting `tp:thud_mark` on landing, with no `min_speed`, which is two blocks a second.
     */
    private static void thud(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:thud_soft ~ ~ ~3");
        Worlds.waitForClient(context, client -> !Born.of("tp:thud_soft").isEmpty(), PATIENCE,
            () -> "tp:thud_soft emits one particle at once and none was made.");
        context.waitTicks(20);
        int soft = context.computeOnClient(client -> Born.of("tp:thud_mark").size());
        Assert.that(soft == 0, "tp:thud_soft lands slower than two blocks a second and still raised its"
            + " collision event " + soft + " times.");

        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:thud_hard ~ ~ ~3");
        Worlds.waitForClient(context, client -> !Born.of("tp:thud_mark").isEmpty(), PATIENCE,
            () -> "tp:thud_hard lands at about ten blocks a second and never raised its collision event.");
    }

    /**
     * `tp:bounce` falls a block onto the ground with a `coefficient_of_restitution` of 0.8, so it
     * comes back up after every landing and lands again. A rebound keeps 0.8 of the landing speed,
     * so each apex stands at 0.64 of the one before it. Java stops a particle for good at its first
     * landing unless its `stoppedByCollision` is cleared, so three apexes in a row say it rebounds
     * by that share. Once the rebounds are too small to see the particle rests on the floor, and
     * Java would stop it there for good, so its slide along x in the fifth second says the clearing
     * runs.
     */
    private static void bounce(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:bounce ~ ~ ~3");
        Worlds.waitForClient(context, client -> !Born.of("tp:bounce").isEmpty(), PATIENCE,
            () -> "tp:bounce emits one particle at once and none was made.");
        Born.Particle bounce = context.computeOnClient(client -> Born.of("tp:bounce").get(0));

        List<Double> heights = new ArrayList<>();
        double lastSecondX = 0;
        for (int tick = 0; tick < 100; tick++) {
            context.waitTick();
            heights.add(context.computeOnClient(client -> bounce.particle().getBoundingBox().minY));
            if (tick == 79) {
                lastSecondX = context.computeOnClient(client -> bounce.particle().getBoundingBox().getCenter().x);
            }
        }
        double travelled = context.computeOnClient(client -> bounce.particle().getBoundingBox().getCenter().x)
            - lastSecondX;
        Assert.that(travelled > 1.5, "tp:bounce moves two blocks a second along x, and after the rebounds have"
            + " died away it still slides, and moved " + travelled + " blocks in its fifth second.");
        double floor = heights.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        List<Double> apexes = new ArrayList<>();
        apexes.add(heights.get(0) - floor);
        for (int tick = 1; tick < heights.size() - 1; tick++) {
            double height = heights.get(tick);
            if (height > heights.get(tick - 1) && height >= heights.get(tick + 1)) apexes.add(height - floor);
        }
        Assert.that(apexes.size() >= 3, "tp:bounce lands and rebounds again and again, and its apexes above the"
            + " floor were " + apexes + ".");
        for (int i = 1; i < 3; i++) {
            double ratio = apexes.get(i) / apexes.get(i - 1);
            Assert.that(ratio > 0.5 && ratio < 0.8, "tp:bounce rebounds to 0.64 of its last apex and apex " + i
                + " stood at " + ratio + " of apex " + (i - 1) + " in " + apexes + ".");
        }
    }

    /**
     * `tp:skid` drops a fifth of a block moving two blocks a second along x, and its `collision_drag`
     * of four takes four blocks a second off that speed for every second on the ground, so it slides
     * half a block further and stops dead.
     */
    private static void skid(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> Born.clear());
        world.getServer().runCommand("bedrock execute as @p at @s run particle tp:skid ~ ~ ~3");
        Worlds.waitForClient(context, client -> !Born.of("tp:skid").isEmpty(), PATIENCE,
            () -> "tp:skid emits one particle at once and none was made.");
        Born.Particle skid = context.computeOnClient(client -> Born.of("tp:skid").get(0));
        Worlds.waitForClient(context, client -> ((ParticleMotionAccessor) skid.particle()).mcaddon$xd() == 0,
            40, () -> "tp:skid's collision_drag slows it by a fixed four blocks a second each second and it"
                + " was still moving at " + ((ParticleMotionAccessor) skid.particle()).mcaddon$xd() * 20
                + " blocks a second.");
        double slid = context.computeOnClient(client -> skid.particle().getBoundingBox().getCenter().x - skid.at().x);
        Assert.that(slid > 0.6, "tp:skid falls a third of a block along x and slides half a block more,"
            + " and moved " + slid + " blocks.");
    }

    /** How far apart in height the particles were born. */
    private static double spread(List<Born.Particle> particles) {
        double lowest = particles.stream().mapToDouble(born -> born.at().y).min().orElse(0);
        double highest = particles.stream().mapToDouble(born -> born.at().y).max().orElse(0);
        return highest - lowest;
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
