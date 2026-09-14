package at.ioexception.mcaddon.bedrock.animation;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.util.Json;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How long an animation runs and where its keyframes land.
 *
 * Most published animations write no `animation_length` at all: the engine works it out from the
 * last keyframe or event, and a length of zero would hold every one of them on its first frame.
 */
class AnimationReadingTest {
    @Test
    void runsAsFarAsItsLastKeyframeWhenItDeclaresNoLength() {
        BedrockAnimation animation = read("""
            {
              "loop": true,
              "bones": {
                "head": {"rotation": {"0.0": [0, 0, 0], "1.5": [0, 90, 0]}},
                "arm": {"position": {"0.0": [0, 0, 0], "0.5": [1, 0, 0]}}
              }
            }
            """);

        assertEquals(1.5f, animation.length());
    }

    @Test
    void runsAsFarAsItsLastEventWhenItHasNoBones() {
        BedrockAnimation animation = read("""
            {
              "timeline": {"0.2": "variable.talking = 1;"},
              "sound_effects": {"2.5": {"effect": "greet"}}
            }
            """);

        assertEquals(2.5f, animation.length());
    }

    @Test
    void keepsADeclaredLengthOverTheOneItsKeyframesWouldGive() {
        assertEquals(4f, read("""
            {"animation_length": 4.0, "bones": {"head": {"rotation": {"0.0": [0, 0, 0]}}}}
            """).length());
    }

    /** A keyframe past the declared length is held on the end rather than never reached. */
    @Test
    void pullsAKeyframePastTheEndBackOntoIt() {
        AnimationChannel channel = read("""
            {
              "animation_length": 1.0,
              "bones": {"head": {"rotation": {"0.0": [0, 0, 0], "2.0": [0, 90, 0]}}}
            }
            """).bones().get("head").rotation();

        assertEquals(1f, channel.end());
        assertEquals(90f, channel.evaluate(1f, new MolangEnvironment(),
            new Vector3f(), new Vector3f()).y);
    }

    /** The delay before each loop is an expression, so a pack can make each repeat wait differently. */
    @Test
    void keepsTheLoopDelayAsSomethingToEvaluatePerLoop() {
        BedrockAnimation animation = read("""
            {"loop": true, "animation_length": 1.0, "loop_delay": "variable.gap"}
            """);

        assertNotNull(animation.loopDelay());

        MolangEnvironment environment = new MolangEnvironment();
        environment.setVariable("gap", MolangValue.of(3));
        assertEquals(3f, animation.loopDelaySeconds(environment));
        environment.setVariable("gap", MolangValue.of(7));
        assertEquals(7f, animation.loopDelaySeconds(environment));
    }

    @Test
    void holdsAClipAtItsStartForAsLongAsItsStartDelay() {
        BedrockAnimation animation = read("""
            {"loop": true, "animation_length": 1.0, "start_delay": 2.0}
            """);

        assertEquals(2f, animation.startDelaySeconds(new MolangEnvironment()));
    }

    @Test
    void readsTheLerpModeOffTheKeyframeThatCarriesIt() {
        AnimationChannel channel = read("""
            {
              "animation_length": 2.0,
              "bones": {"head": {"rotation": {
                "0.0": {"post": [0, 0, 0], "lerp_mode": "catmullrom"},
                "1.0": [0, 90, 0]
              }}}
            }
            """).bones().get("head").rotation();

        assertEquals(LerpMode.CATMULLROM, channel.keyframes().get(0).lerpMode());
        assertEquals(LerpMode.LINEAR, channel.keyframes().get(1).lerpMode());
    }

    @Test
    void marksABoneWhoseRotationIsGivenInTheEntitysFrame() {
        BedrockAnimation animation = read("""
            {
              "animation_length": 1.0,
              "bones": {"head": {
                "relative_to": {"rotation": "entity"},
                "rotation": {"0.0": [0, 0, 0]}
              }}
            }
            """);

        assertTrue(animation.bones().get("head").rotationRelativeToEntity());
    }

    private static BedrockAnimation read(String body) {
        return AnimationParser.readAnimation("animation.test", Json.parse(body).getAsJsonObject());
    }
}
