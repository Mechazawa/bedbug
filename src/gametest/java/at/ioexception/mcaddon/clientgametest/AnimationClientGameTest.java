package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.client.render.ScriptClock;
import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * An entity's animation script poses its bones the way its pack's clips and controllers say, and
 * the effects its clips name reach the client.
 *
 * `tp:prism`'s script is run on a clock the test moves a tick at a time, from the pack the game
 * loaded and against the prism the server summoned, so each bone is asked for at an exact moment:
 *
 * - `pulse` loops over four seconds while its keyframes stop at two, so the scale it reaches is held
 *   until the fourth second and only then starts again.
 * - `spin` drives its own clock at twice the speed of time through `anim_time_update`.
 * - `settle` holds its last frame.
 * - `lean` is in the animate list with a blend expression of 0.25, and `tilt` sits in a controller
 *   state with a blend value of 0.5, so each moves its bone by that share of its rotation.
 * - `turn` starts in a state turning `dial` to 350 degrees, which crosses over a second into one
 *   turning it to 10, and the state being left takes the short way: the dial passes through 0
 *   rather than through 180.
 * - `wave` plays `blink` once and moves on once the clips of its state have all finished, so the
 *   bone `blink` scales comes back to rest and stays there.
 *
 * `tp:sprite`'s `chime` clip is run by the renderer itself: at half a second it plays its pack's
 * sound and at two seconds it starts `tp:burst` at the `tip` locator, which sits seventeen pixels up
 * the young sprite. The burst's point emitter puts its particles a fifth of a block above that.
 *
 * The sprite's `idle` clip writes its body's channels as Molang rather than keyframes: a turn of
 * `q.life_time * 45` degrees and a lift of `math.sin(q.life_time * 180) * 0.5` pixels, so the posed
 * bone and the drawn body are both a function of the sprite's age. `chime`'s timeline sets
 * `v.chimed` at two and a half seconds, the `ring` controller leaves its first state once that
 * variable holds, writing the moment from `on_exit` and counting from `on_entry`, and the cap's render
 * controller halves its green from then on. The cap is a colour mask over a partly clear texture, so
 * that green is read off the tint layer drawn over it, the sprite's third pass.
 *
 * `tp:gaze`'s `wake` controller starts in a state whose `on_entry` counts `v.woke`, whose one clip
 * counts `v.stirred` off its timeline at nought seconds, and which leaves on `1` for a state counting
 * `v.awake`. Entering the first state is the first frame's move, so that frame counts a wake and a
 * stir and no awakening, and the next frame counts the awakening alone.
 */
public final class AnimationClientGameTest implements FabricClientGameTest {
    private static final float TOLERANCE = 0.01f;

    /** The sprite's turn, in degrees a second, and the height of its bob, in pixels. */
    private static final float SPIN = 45;
    private static final float BOB = 0.5f;

    /** The tick on which `chime`'s timeline reaches two and a half seconds. */
    private static final int CHIMED = 50;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int RUNG = 0xFFFF80FF;

    /** Five seconds of the prism's script, a tick at a time. */
    private static final int TICKS = 100;

    private static final int PATIENCE = 200;

    /** Where `tp:burst` is born, above the sprite's feet: the tip locator, then the emitter's offset. */
    private static final double BURST_HEIGHT = 17 / 16.0 + 0.2;

    /** How far the sprite's idle bob and its spark's swelling may move the tip, in blocks. */
    private static final double TIP_SWAY = 0.1;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            prism(context, world);
            chime(context, world);
            molangChannels(context, world);
            timeline(context, world);
            firstState(context, world);
        }
    }

    private static void firstState(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon tp:gaze ~-3 ~ ~3");
        AddonEntity gaze = Entities.waitForClient(context, "tp:gaze");
        List<String> counts = context.computeOnClient(client -> {
            ScriptClock clock = ScriptClock.of(gaze);
            List<String> taken = new ArrayList<>();
            for (int tick = 0; tick < 2; tick++) {
                clock.tick();
                taken.add("woke " + clock.variable("woke") + ", stirred " + clock.variable("stirred")
                    + ", awake " + clock.variable("awake"));
            }
            return taken;
        });
        List<String> wanted = List.of("woke 1.0, stirred 1.0, awake 0.0", "woke 1.0, stirred 1.0, awake 1.0");
        Assert.that(counts.equals(wanted), "tp:gaze's wake controller should enter its first state, run its"
            + " on_entry and play its clip before leaving on its next frame, " + wanted + ", and counted "
            + counts + ".");
    }

    /** The idle clip's two Molang channels, on the bone at two moments and on the drawn body at two more. */
    private static void molangChannels(ClientGameTestContext context, TestSingleplayerContext world) {
        AddonEntity sprite = freshSprite(context, world);

        List<BonePose> body = context.computeOnClient(client -> {
            ScriptClock clock = ScriptClock.of(sprite);
            List<BonePose> taken = new ArrayList<>();
            for (int tick = 1; tick <= 30; tick++) {
                clock.tick();
                taken.add(Poses.copy(clock.pose("body")));
            }
            return taken;
        });
        for (int tick : new int[] {10, 30}) {
            float seconds = tick / 20f;
            BonePose pose = body.get(tick - 1);
            Assert.near(pose.rotation.y, seconds * SPIN, TOLERANCE,
                "the body's yaw " + seconds + " seconds in under q.life_time * 45, in degrees");
            Assert.near(pose.position.y, lift(seconds), TOLERANCE,
                "the body's lift " + seconds + " seconds in under math.sin(q.life_time * 180) * 0.5, in pixels");
        }

        drawnBody(context, sprite, 10);
        drawnBody(context, sprite, 30);
    }

    /**
     * The drawn body at an age where its bob is at one end, which is where a lift the renderer read in
     * the wrong unit shows most.
     *
     * The lowest corners are the body's own bottom face, which stands the lift above the feet in
     * sixteenths of a block. Seen from above they are a square turned by the body's yaw; a square
     * looks the same every quarter turn and mirrored, so the turn is compared with the sprite's
     * own facing taken off and folded into a quarter, either way round.
     */
    private static void drawnBody(ClientGameTestContext context, AddonEntity sprite, int phase) {
        for (int wait = 0; wait < PATIENCE; wait++) {
            DrawnBody drawn = context.computeOnClient(client -> sprite.tickCount % 40 == phase
                ? new DrawnBody(sprite.tickCount, sprite.yBodyRot, Submitted.entity(sprite).geometry().get(0))
                : null);
            if (drawn == null) {
                context.waitTick();
                continue;
            }

            float seconds = drawn.age() / 20f;
            double lowest = drawn.body().box().minY;
            // The renderer stands every model a thousandth of a block clear of the ground.
            Assert.near(lowest, lift(seconds) / 16, 0.002,
                "the bottom of tp:sprite's body above its feet at " + seconds + " seconds, in blocks");

            double turned = drawn.body().vertices().stream()
                .filter(vertex -> Math.abs(vertex.y() - lowest) < 1e-4)
                .mapToDouble(vertex -> quarter(Math.toDegrees(Math.atan2(vertex.z(), vertex.x())) - 45))
                .findFirst().orElseThrow();
            double yaw = seconds * SPIN;
            double facing = 180 - drawn.facing();
            List<Double> wanted = List.of(quarter(yaw + facing), quarter(yaw - facing),
                quarter(-yaw + facing), quarter(-yaw - facing));
            Assert.that(wanted.stream().anyMatch(angle -> Math.abs(angle - turned) < 0.1),
                "tp:sprite's body is turned " + yaw + " degrees at " + seconds + " seconds on a body facing "
                    + drawn.facing() + ", which folds to one of " + wanted + ", and its bottom corners fold to "
                    + turned + ".");
            return;
        }
        throw new AssertionError("tp:sprite never reached tick " + phase + " of a two second round.");
    }

    private record DrawnBody(int age, float facing, Submitted.Geometry body) {
    }

    private static double lift(float seconds) {
        return Math.sin(Math.toRadians(seconds * 180)) * BOB;
    }

    /** An angle folded into the first quarter turn, which is all a square's corners can say about it. */
    private static double quarter(double degrees) {
        return ((degrees % 90) + 90) % 90;
    }

    /** `chime`'s timeline, the controller reading what it wrote, and the render controller reading that. */
    private static void timeline(ClientGameTestContext context, TestSingleplayerContext world) {
        AddonEntity sprite = freshSprite(context, world);

        List<Rung> moments = context.computeOnClient(client -> {
            ScriptClock clock = ScriptClock.of(sprite);
            List<Rung> taken = new ArrayList<>();
            for (int tick = 1; tick <= TICKS; tick++) {
                clock.tick();
                taken.add(new Rung(clock.variable("chimed"), clock.variable("rung"),
                    clock.variable("quiet_until"), clock.passes().get(2).colour()));
            }
            return taken;
        });

        int chimed = first(moments, moment -> moment.chimed() == 1);
        Assert.that(chimed == CHIMED || chimed == CHIMED + 1, "chime's timeline sets v.chimed at 2.5 seconds,"
            + " tick " + CHIMED + ", and it was first set on tick " + chimed + ".");
        int entered = first(moments, moment -> moment.rung() > 0);
        Assert.that(entered == chimed || entered == chimed + 1, "ring leaves its quiet state once v.chimed holds,"
            + " which it did from tick " + chimed + ", and its ringing state's on_entry first ran on tick "
            + entered + ".");
        Rung last = at(moments, TICKS);
        Assert.near(last.rung(), 1, TOLERANCE, "v.rung, which on_entry adds one to as ringing is entered once");
        Assert.near(last.quietUntil(), entered / 20f, 1e-4,
            "v.quiet_until, the q.life_time quiet's on_exit wrote as the controller left it");
        for (int tick = 1; tick <= TICKS; tick++) {
            int wanted = tick < entered ? WHITE : RUNG;
            int colour = at(moments, tick).cap();
            Assert.that(colour == wanted, "the cap's colour on tick " + tick + ", whose controller halves its"
                + " green once v.rung is set on tick " + entered + ", is " + Integer.toHexString(colour)
                + " where " + Integer.toHexString(wanted) + " was expected.");
        }

        Submitted early = context.computeOnClient(client -> Submitted.entity(sprite));
        int earlyAge = context.computeOnClient(client -> sprite.tickCount);
        Assert.that(earlyAge < CHIMED && early.geometry().get(2).vertices().get(0).colour() == WHITE,
            "the drawn cap of a tp:sprite " + earlyAge + " ticks old, before its chime, was "
                + Integer.toHexString(early.geometry().get(2).vertices().get(0).colour()) + " and not white.");
        Frames.waitFor(context, sprite, frame -> frame.geometry().get(2).vertices().get(0).colour() == RUNG,
            drawn -> "tp:sprite's drawn cap is " + Integer.toHexString(RUNG) + " once its chime set v.chimed, and"
                + " it was drawn in " + Integer.toHexString(drawn.geometry().get(2).vertices().get(0).colour()) + ".");
        // The frames drawn during the tick before the chime may already be most of the way to it.
        int rungAge = context.computeOnClient(client -> sprite.tickCount);
        Assert.that(rungAge >= CHIMED - 1, "tp:sprite's drawn cap had its green halved " + rungAge
            + " ticks into its life, before its chime at " + CHIMED + ".");
    }

    private record Rung(float chimed, float rung, float quietUntil, int cap) {
    }

    private static <T> int first(List<T> moments, Predicate<T> holds) {
        for (int tick = 1; tick <= moments.size(); tick++) {
            if (holds.test(moments.get(tick - 1))) return tick;
        }
        return -1;
    }

    /** A sprite of nobody else's, so its age starts at the summon. */
    private static AddonEntity freshSprite(ClientGameTestContext context, TestSingleplayerContext world) {
        Entities.discardAll(context, world.getServer(), "tp:sprite");
        world.getServer().runCommand("summon tp:sprite ~ ~ ~3 {NoAI:1b,Rotation:[0f,0f]}");
        return Entities.waitForClient(context, "tp:sprite");
    }

    private static void prism(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon tp:prism ~ ~ ~3 {NoAI:1b}");
        AddonEntity prism = Entities.waitForClient(context, "tp:prism");

        List<Poses> poses = context.computeOnClient(client -> {
            ScriptClock clock = ScriptClock.of(prism);
            List<Poses> taken = new ArrayList<>();
            for (int tick = 1; tick <= TICKS; tick++) {
                clock.tick();
                taken.add(Poses.of(clock));
            }
            return taken;
        });

        Assert.near(at(poses, 5).gauge().rotation.x, 45, TOLERANCE,
            "the gauge's pitch a quarter of a second into spin, whose own clock runs at twice the speed");
        Assert.near(at(poses, 5).gauge().rotation.z, 10, TOLERANCE,
            "the gauge's roll under lean, a 40 degree clip weighed by its blend expression of 0.25");

        Assert.near(at(poses, 20).dial().scale.x, 1.5, TOLERANCE, "the dial's scale a second into pulse");
        Assert.near(at(poses, 40).dial().scale.x, 2, TOLERANCE, "the dial's scale at the end of pulse's keyframes");
        Assert.near(at(poses, 60).dial().scale.x, 2, TOLERANCE,
            "the dial's scale three seconds into pulse, whose animation_length of four holds its last keyframe");
        Assert.near(at(poses, 90).dial().scale.x, 1.25, TOLERANCE,
            "the dial's scale half a second into pulse's second round");

        Assert.near(at(poses, 10).dial().position.y, 2, TOLERANCE, "the dial's lift halfway through settle");
        Assert.near(at(poses, TICKS).dial().position.y, 4, TOLERANCE,
            "the dial's lift four seconds after settle, which holds its last frame, ended");

        turn(poses);

        Assert.that(at(poses, 5).needle().scale.x > 1.5f, "blink was never played on the needle.");
        for (int tick = 20; tick <= TICKS; tick++) {
            Assert.near(at(poses, tick).needle().scale.x, 1, TOLERANCE, "the needle's scale " + tick
                + " ticks in, once wave's state played blink through once and moved on");
        }
    }

    /** The dial while `turn` crosses from 350 degrees to 10, and the needle once it has. */
    private static void turn(List<Poses> poses) {
        int crossing = 0;
        for (int tick = 1; tick <= TICKS; tick++) {
            float yaw = normalised(at(poses, tick).dial().rotation.y);
            Assert.that(Math.abs(yaw) <= 10 + TOLERANCE, "turn's first state takes the short way from 350 degrees"
                + " to 10, and " + tick + " ticks in the dial was turned to " + yaw + ".");
            if (Math.abs(yaw) < 10 - TOLERANCE) crossing++;
        }
        Assert.that(crossing >= 15 && crossing <= 21, "turn's first state crosses over a second, and the dial"
            + " was between its two angles for " + crossing + " ticks.");

        Assert.near(normalised(at(poses, 19).dial().rotation.y), -10, TOLERANCE, "the dial before turn moves on");
        Assert.near(normalised(at(poses, TICKS).dial().rotation.y), 10, TOLERANCE, "the dial once turn has moved on");
        Assert.near(at(poses, TICKS).needle().rotation.x, 30, TOLERANCE,
            "the needle's pitch under tilt, a 60 degree clip at the blend value 0.5 its state gives it");
    }

    private static <T> T at(List<T> moments, int tick) {
        return moments.get(tick - 1);
    }

    private static float normalised(float degrees) {
        return degrees - 360 * Math.round(degrees / 360);
    }

    /** The three bones the prism's clips move, copied out at one moment. */
    private record Poses(BonePose dial, BonePose gauge, BonePose needle) {
        static Poses of(ScriptClock clock) {
            return new Poses(copy(clock.pose("dial")), copy(clock.pose("gauge")), copy(clock.pose("needle")));
        }

        private static BonePose copy(BonePose pose) {
            BonePose copy = new BonePose();
            copy.rotation.set(pose.rotation);
            copy.position.set(pose.position);
            copy.scale.set(pose.scale);
            return copy;
        }
    }

    private static void chime(ClientGameTestContext context, TestSingleplayerContext world) {
        context.runOnClient(client -> {
            Heard.clear();
            Born.clear();
        });
        world.getServer().runCommand("summon tp:sprite ~ ~ ~3 {NoAI:1b}");
        AddonEntity sprite = Entities.waitForClient(context, "tp:sprite");

        Worlds.waitForClient(context, client -> !Heard.of("tp:sprite.chime").isEmpty(), PATIENCE,
            () -> "tp:sprite's chime clip plays tp:sprite.chime half a second in, and the client was never asked"
                + " to play it.");
        Heard.Sound ping = Heard.of("tp:sprite.chime").get(0);
        Assert.that(ping.at().distanceTo(sprite.position()) < 1,
            "tp:sprite.chime was played at " + ping.at() + ", away from the sprite at " + sprite.position() + ".");

        Worlds.waitForClient(context, client -> !Born.of("tp:burst").isEmpty(), PATIENCE,
            () -> "tp:sprite's chime clip starts tp:burst two seconds in, and no particle of it was made.");
        Born.Particle burst = Born.of("tp:burst").get(0);
        Assert.near(burst.at().y - sprite.getY(), BURST_HEIGHT, TIP_SWAY,
            "the height above tp:sprite's feet that tp:burst was born at, started at the tip locator");
        Assert.that(Math.hypot(burst.at().x - sprite.getX(), burst.at().z - sprite.getZ()) < TIP_SWAY,
            "tp:burst was born at " + burst.at() + ", beside the tip locator over " + sprite.position() + ".");
    }
}
