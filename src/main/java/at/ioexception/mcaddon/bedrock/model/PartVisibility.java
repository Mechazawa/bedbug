package at.ioexception.mcaddon.bedrock.model;

import java.util.List;

/**
 * Which bones one pass of a render controller draws.
 *
 * `part_visibility` is a list rather than a map because the order decides: each rule names a bone or
 * a family of them, with a star standing for any run of characters (`Bag*`, `*leg`, `*saddle*`), and
 * says whether that bone is drawn, and a later rule answers for a bone an earlier one also matched.
 * A pack turns the whole model off with `"*": false` and then names the one part it wants back, so a
 * rule read on its own gives the opposite answer to the one the pack wrote. A controller's
 * `materials` list is matched against bones the same way. Bedrock compares bone names without
 * regard to case.
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

    /** A wildcard match walked by hand, because every bone is asked about on every frame. */
    private static boolean matches(String pattern, String bone) {
        int at = 0;
        int from = 0;
        int star = -1;
        int resumeAt = 0;
        while (at < bone.length()) {
            if (from < pattern.length() && pattern.charAt(from) == '*') {
                star = from++;
                resumeAt = at;
            } else if (from < pattern.length() && sameLetter(pattern.charAt(from), bone.charAt(at))) {
                from++;
                at++;
            } else if (star >= 0) {
                from = star + 1;
                at = ++resumeAt;
            } else {
                return false;
            }
        }
        while (from < pattern.length() && pattern.charAt(from) == '*') from++;
        return from == pattern.length();
    }

    private static boolean sameLetter(char one, char other) {
        return Character.toLowerCase(one) == Character.toLowerCase(other);
    }
}
