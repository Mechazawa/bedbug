package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.util.Json;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which way round the circle a bone travels while one controller state crosses into the next.
 *
 * Halfway through a transition a bone holds the weighted sum of both states, and the sum of 350 and
 * 10 degrees is 180: a head that should not have moved is pointing backwards for the length of the
 * crossing.
 */
class BoneBlendingTest {
    @Test
    void sumsTheAnglesAsTheyAreWrittenWhenTheStateAsksForNothing() {
        BonePose pose = new BonePose();
        pose.addRotation(new Vector3f(0, 350, 0), 0.5f, false);
        pose.addRotation(new Vector3f(0, 10, 0), 0.5f, false);

        assertEquals(180f, pose.rotation.y);
    }

    @Test
    void turnsTheLaterAngleTheShortWayRoundToMeetTheFirst() {
        BonePose pose = new BonePose();
        pose.addRotation(new Vector3f(0, 350, 0), 0.5f, true);
        pose.addRotation(new Vector3f(0, 10, 0), 0.5f, true);

        // 350 and 370 rather than 350 and 10, whose average is the full circle the pack meant.
        assertEquals(360f, pose.rotation.y);
    }

    @Test
    void takesTheShortWayOnEveryAxisAtOnce() {
        BonePose pose = new BonePose();
        pose.addRotation(new Vector3f(-170, 350, 5), 0.5f, true);
        pose.addRotation(new Vector3f(170, 10, -355), 0.5f, true);

        assertEquals(-180f, pose.rotation.x);
        assertEquals(360f, pose.rotation.y);
        assertEquals(5f, pose.rotation.z);
    }

    @Test
    void leavesAnAngleAlreadyWithinHalfACircleWhereItIs() {
        BonePose pose = new BonePose();
        pose.addRotation(new Vector3f(0, 30, 0), 0.5f, true);
        pose.addRotation(new Vector3f(0, 90, 0), 0.5f, true);

        assertEquals(60f, pose.rotation.y);
    }

    @Test
    void forgetsWhichAngleCameFirstOnceTheFrameIsOver() {
        BonePose pose = new BonePose();
        pose.addRotation(new Vector3f(0, 350, 0), 1f, true);
        pose.reset();
        pose.addRotation(new Vector3f(0, 10, 0), 0.5f, true);
        pose.addRotation(new Vector3f(0, 350, 0), 0.5f, true);

        assertEquals(0f, pose.rotation.y);
    }

    @Test
    void readsTheFlagOffTheStateThatDeclaredIt() {
        AnimationController controller = AnimationControllerParser.readController("controller.test",
            Json.parse("""
                {
                  "states": {
                    "default": {"blend_via_shortest_path": true, "animations": ["spin"]},
                    "still": {"animations": ["idle"]}
                  }
                }
                """).getAsJsonObject());

        assertTrue(controller.states().get("default").blendViaShortestPath());
        assertFalse(controller.states().get("still").blendViaShortestPath());
    }
}
