package at.ioexception.mcaddon.bedrock.model;

import java.util.List;

/**
 * Which bones one pass of a render controller draws.
 *
 * `part_visibility` is a list rather than a map because the order decides: each rule names a bone or
 * a family of them ending in a star and says whether that bone is drawn, and a later rule answers
 * for a bone an earlier one also matched. A pack turns the whole model off with `"*": false` and
 * then names the one part it wants back, so a rule read on its own gives the opposite answer to the
 * one the pack wrote.
 */
public record PartVisibility(List<Rule> rules) {
    /** One rule of the list: the bones it names, and whether they are drawn. */
    public record Rule(String pattern, boolean visible) {
    }

    /** A pass whose controller named no rule at all, which draws every bone of its geometry. */
    public static final PartVisibility EVERYTHING = new PartVisibility(List.of());

    public boolean hides(String bone) {
        for (int index = rules.size() - 1; index >= 0; index--) {
            Rule rule = rules.get(index);
            if (matches(rule.pattern(), bone)) return !rule.visible();
        }
        return false;
    }

    private static boolean matches(String pattern, String bone) {
        return pattern.endsWith("*")
            ? bone.startsWith(pattern.substring(0, pattern.length() - 1))
            : pattern.equals(bone);
    }
}
