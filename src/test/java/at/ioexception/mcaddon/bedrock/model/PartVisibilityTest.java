package at.ioexception.mcaddon.bedrock.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which bones a render controller's `part_visibility` list draws.
 *
 * The rules are a list rather than a map because a later one answers for a bone an earlier one also
 * matched. Mojang's own copper golem turns every bone off and then names the flower it wants back,
 * and its villager writes the wildcard and the bones overriding it inside one rule object, so a
 * reader that treats the rules as a set of things to hide draws neither of them.
 */
class PartVisibilityTest {
    private static PartVisibility.Rule draw(String pattern) {
        return new PartVisibility.Rule(pattern, true);
    }

    private static PartVisibility.Rule hide(String pattern) {
        return new PartVisibility.Rule(pattern, false);
    }

    @Test
    void drawsEveryBoneWhenTheControllerNamesNoRule() {
        assertFalse(PartVisibility.EVERYTHING.hides("head"));
    }

    @Test
    void showsTheOneBoneNamedAfterTheWildcardHidEverything() {
        PartVisibility visibility = new PartVisibility(List.of(hide("*"), draw("flower")));

        assertFalse(visibility.hides("flower"));
        assertTrue(visibility.hides("head"));
        assertTrue(visibility.hides("body"));
    }

    @Test
    void hidesTheOneBoneNamedAfterTheWildcardShowedEverything() {
        PartVisibility visibility = new PartVisibility(List.of(draw("*"), hide("hat")));

        assertTrue(visibility.hides("hat"));
        assertFalse(visibility.hides("head"));
    }

    @Test
    void endsAFamilyOfBonesAtTheStar() {
        PartVisibility visibility = new PartVisibility(List.of(hide("reins*")));

        assertTrue(visibility.hides("reins_left"));
        assertFalse(visibility.hides("rein"));
        assertFalse(visibility.hides("saddle"));
    }

    /** The last rule decides, so a pack may name one bone twice and mean the second answer. */
    @Test
    void takesTheLastRuleThatNamesTheBone() {
        assertFalse(new PartVisibility(List.of(hide("head"), draw("head"))).hides("head"));
        assertTrue(new PartVisibility(List.of(draw("head"), hide("head"))).hides("head"));
    }
}
