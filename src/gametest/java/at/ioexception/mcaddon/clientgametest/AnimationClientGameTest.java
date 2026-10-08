package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.animation.BonePose;
import at.ioexception.mcaddon.client.render.ScriptClock;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.util.ArrayList;
import java.util.List;

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
 */
public final class AnimationClientGameTest implements FabricClientGameTest {
    private static final float TOLERANCE = 0.01f;

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
        }
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

    private static Poses at(List<Poses> poses, int tick) {
        return poses.get(tick - 1);
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
