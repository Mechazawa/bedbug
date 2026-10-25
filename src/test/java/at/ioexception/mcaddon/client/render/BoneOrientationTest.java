package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.model.BoneOrientation;
import at.ioexception.mcaddon.bedrock.model.GeometryBaker;
import at.ioexception.mcaddon.bedrock.model.GeometryParser;
import at.ioexception.mcaddon.util.Json;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What `query.bone_orientation_trs` reads back off a drawn model: the posed bone in the space and
 * the degrees the pack wrote its geometry in, whatever the baker turned them into for Java.
 */
class BoneOrientationTest {
    /** Turning a bone goes through `Platform`, which reads the game's registries as it loads. */
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void readsATurnBackAsThePackWroteIt() {
        BoneOrientation head = orientationOf("[0, 0, 0]", "[10, 30, 20]", "head");

        assertNear(new Vector3f(10, 30, 20), head.rotation());
        assertNear(new Vector3f(0, 8, 0), head.translation());
        assertNear(new Vector3f(1, 1, 1), head.scale());
    }

    /** A head on a body leaning forward is carried forward and down with it, and leans as far. */
    @Test
    void carriesABoneWithTheBoneAboveIt() {
        BoneOrientation head = orientationOf("[45, 0, 0]", "[0, 0, 0]", "head");
        float carried = 8 * (float) Math.sqrt(0.5);

        assertNear(new Vector3f(0, carried, -carried), head.translation());
        assertNear(new Vector3f(45, 0, 0), head.rotation());
    }

    private static BoneOrientation orientationOf(String bodyTurn, String headTurn, String bone) {
        String geometry = """
            {
              "description": {"identifier": "geometry.thing"},
              "bones": [
                {"name": "body", "pivot": [0, 0, 0], "rotation": %s},
                {"name": "head", "parent": "body", "pivot": [0, 8, 0], "rotation": %s}
              ]
            }
            """.formatted(bodyTurn, headTurn);
        BakedGeometry baked = GeometryBaker.bake(
            GeometryParser.readGeometry("geometry.thing", Json.parse(geometry).getAsJsonObject()), texture -> null);
        return BoneOrientation.ofDrawn(BedrockModelRenderer.anchorOf(baked, null, bone));
    }

    private static void assertNear(Vector3f expected, Vector3f actual) {
        assertTrue(expected.equals(actual, 1e-3f), () -> "expected " + expected + " but was " + actual);
    }
}
