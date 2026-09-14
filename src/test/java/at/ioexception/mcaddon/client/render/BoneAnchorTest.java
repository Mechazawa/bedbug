package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.model.BakedGeometry;
import at.ioexception.mcaddon.bedrock.model.GeometryBaker;
import at.ioexception.mcaddon.bedrock.model.GeometryParser;
import at.ioexception.mcaddon.util.Json;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Where a named bone of a model stands, which is what a hand and a binding both hang off.
 *
 * A Bedrock pivot is absolute in a space measuring upward from the entity's feet, and the baker
 * turns it into an offset from the parent in Java's own space, which measures downward from a point
 * 24 pixels above them. The anchor has to come back out of that as the same place the pack wrote.
 */
class BoneAnchorTest {
    /** A body with the item bone Mojang's own humanoid carries, at the pivot it writes for it. */
    private static final String HUMANOID = """
        {
          "description": {"identifier": "geometry.thing"},
          "bones": [
            {"name": "body", "pivot": [0, 4, 0]},
            {"name": "rightItem", "parent": "body", "pivot": [-6, 15, 1]}
          ]
        }
        """;

    @Test
    void putsTheBoneWhereThePackPutItsPivot() {
        Matrix4f anchor = anchorOf("rightitem");

        assertNotNull(anchor);
        assertEquals(new Vector3f(-6f / 16f, 9f / 16f, 1f / 16f),
            anchor.transformPosition(new Vector3f()));
    }

    /** Bedrock matches a bone name without regard to case, which is why `rightitem` finds `rightItem`. */
    @Test
    void findsABoneWhateverCaseTheNameIsAskedIn() {
        assertNotNull(anchorOf("RIGHTITEM"));
    }

    @Test
    void answersWithNothingForABoneTheModelHasNot() {
        assertNull(anchorOf("leftitem"));
    }

    /**
     * A bound model's own pivot lands on the bone it hangs off rather than where the pivot points.
     *
     * The pivot stops being a place in the wearer and becomes the point the model's animation turns
     * it about. Mojang's own trident settles it: the bone pivots at 24 pixels with boxes running
     * from 27 below that point to 4 above, which is a pole gripped at the pivot rather than one
     * standing over a mob's head.
     */
    @Test
    void landsABoundModelsOwnPivotOnTheBone() {
        Vector3f boundPivot = new Vector3f(0, -24f, 0);
        Matrix4f hang = BedrockModelRenderer.hangFrom(anchorOf("rightitem"), boundPivot);

        assertEquals(new Vector3f(-6f / 16f, 9f / 16f, 1f / 16f),
            hang.transformPosition(new Vector3f(boundPivot).mul(1f / 16f)));
    }

    private static Matrix4f anchorOf(String bone) {
        BakedGeometry baked = GeometryBaker.bake(
            GeometryParser.readGeometry("geometry.thing", Json.parse(HUMANOID).getAsJsonObject()));
        return BedrockModelRenderer.anchorOf(baked, null, bone);
    }
}
