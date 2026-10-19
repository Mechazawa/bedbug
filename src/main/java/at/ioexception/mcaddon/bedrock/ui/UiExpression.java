package at.ioexception.mcaddon.bedrock.ui;

import java.util.Locale;
import java.util.function.Function;

/**
 * A JSON UI operator expression: `(not ((#title_text - 'chest') = #title_text))`.
 *
 * A value is a string, a number or a boolean. Taking a string from a string removes every place it
 * occurs, which is how a pack asks whether a title holds a marker and how it shows the title with
 * the marker cut out. Adding a number to a string writes the number, whole numbers without a
 * point. Any other arithmetic reads a string as a number, and one that is not a number as nothing.
 * `#name` is a binding and `$name` a variable, both answered by the lookup; a name it does not
 * know is an empty string, which is what Bedrock shows for a binding nothing set.
 *
 * Mojang publishes the operators and not their precedence, and every pack writes its expressions
 * in parentheses, so this binds `or` loosest, then `and`, then the comparisons, then `+` and `-`,
 * then `*`, `/` and `%`, then `not` and a minus sign.
 */
public final class UiExpression {
    private final String source;
    private final Function<String, Object> lookup;
    private int at;

    private UiExpression(String source, Function<String, Object> lookup) {
        this.source = source;
        this.lookup = lookup;
    }

    public static Object evaluate(String source, Function<String, Object> lookup) {
        UiExpression expression = new UiExpression(source, lookup);
        Object value = expression.or();
        expression.space();
        if (expression.at < source.length()) {
            throw new IllegalArgumentException("Unexpected '" + source.substring(expression.at) + "' in " + source);
        }
        return value;
    }

    /** Whether a value counts as true where a property asks for a boolean. */
    public static boolean truthy(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Double number) return number != 0;
        if (value instanceof String text) return !text.isEmpty() && !text.equals("false") && !text.equals("0");
        return false;
    }

    /** How a value reads as text: a label's own text, or a string a `+` builds. */
    public static String text(Object value) {
        if (value instanceof Double number) {
            return number == Math.rint(number) && !Double.isInfinite(number)
                ? String.valueOf(number.longValue()) : number.toString();
        }
        return value == null ? "" : value.toString();
    }

    private static double number(Object value) {
        if (value instanceof Double number) return number;
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        try {
            return Double.parseDouble(text(value).trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private Object or() {
        Object left = and();
        while (word("or")) {
            boolean right = truthy(and());
            left = truthy(left) || right;
        }
        return left;
    }

    private Object and() {
        Object left = comparison();
        while (word("and")) {
            boolean right = truthy(comparison());
            left = truthy(left) && right;
        }
        return left;
    }

    private Object comparison() {
        Object left = additive();
        while (true) {
            space();
            if (symbol(">=")) {
                left = number(left) >= number(additive());
            } else if (symbol("<=")) {
                left = number(left) <= number(additive());
            } else if (symbol("=")) {
                left = equal(left, additive());
            } else if (symbol(">")) {
                left = number(left) > number(additive());
            } else if (symbol("<")) {
                left = number(left) < number(additive());
            } else {
                return left;
            }
        }
    }

    private static boolean equal(Object left, Object right) {
        if (left instanceof String || right instanceof String) return text(left).equals(text(right));
        return number(left) == number(right);
    }

    private Object additive() {
        Object left = multiplicative();
        while (true) {
            space();
            if (symbol("+")) {
                Object right = multiplicative();
                left = left instanceof String || right instanceof String
                    ? text(left) + text(right) : (Object) (number(left) + number(right));
            } else if (symbol("-")) {
                Object right = multiplicative();
                left = left instanceof String && right instanceof String
                    ? removed((String) left, (String) right) : (Object) (number(left) - number(right));
            } else {
                return left;
            }
        }
    }

    private static String removed(String from, String part) {
        return part.isEmpty() ? from : from.replace(part, "");
    }

    private Object multiplicative() {
        Object left = unary();
        while (true) {
            space();
            if (symbol("*")) {
                left = number(left) * number(unary());
            } else if (symbol("/")) {
                left = number(left) / number(unary());
            } else if (symbol("%")) {
                left = number(left) % number(unary());
            } else {
                return left;
            }
        }
    }

    private Object unary() {
        if (word("not")) return !truthy(unary());
        space();
        if (symbol("-")) return -number(unary());
        return primary();
    }

    private Object primary() {
        space();
        if (at >= source.length()) throw new IllegalArgumentException("Unfinished expression " + source);
        char next = source.charAt(at);
        if (next == '(') {
            at++;
            Object inner = or();
            space();
            if (!symbol(")")) throw new IllegalArgumentException("Unclosed parenthesis in " + source);
            return inner;
        }
        if (next == '\'') {
            int end = source.indexOf('\'', at + 1);
            if (end < 0) throw new IllegalArgumentException("Unclosed string in " + source);
            String literal = source.substring(at + 1, end);
            at = end + 1;
            return literal;
        }
        int start = at;
        if (Character.isDigit(next) || next == '.') {
            while (at < source.length() && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.')) at++;
            return Double.parseDouble(source.substring(start, at));
        }
        while (at < source.length() && name(source.charAt(at))) at++;
        if (at == start) throw new IllegalArgumentException("Unexpected '" + next + "' in " + source);

        String token = source.substring(start, at);
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> {
                Object value = lookup.apply(token);
                yield value == null ? "" : value;
            }
        };
    }

    private static boolean name(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '#' || character == '$'
            || character == '.' || character == '|';
    }

    private boolean word(String keyword) {
        space();
        int end = at + keyword.length();
        if (!source.regionMatches(true, at, keyword, 0, keyword.length())) return false;
        if (end < source.length() && name(source.charAt(end))) return false;
        at = end;
        return true;
    }

    private boolean symbol(String operator) {
        if (!source.startsWith(operator, at)) return false;
        at += operator.length();
        return true;
    }

    private void space() {
        while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
    }
}
