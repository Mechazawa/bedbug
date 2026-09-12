package at.ioexception.mcaddon.bedrock.molang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a Molang source string into an expression tree.
 *
 * Molang is the small expression language Bedrock uses wherever a value has to be computed at
 * render time. This covers the subset the add-on actually uses: arithmetic, comparisons, the
 * ternary and null coalescing operators, `Math.*` functions, `q.*` queries, `v.*` and `t.*`
 * variables, `this`, and statement lists ending in `return`.
 */
public final class MolangParser {
    /** Bedrock caps a loop's iterations too, so a pack cannot hang the game inside one expression. */
    private static final int LOOP_LIMIT = 1024;

    /**
     * Thrown where the parser meets something it cannot read.
     *
     * Bedrock answers zero for a malformed expression as a whole, so reading the part in front of
     * the mistake and calling that the answer would be worse than nothing. Every site that cannot go
     * on says so the same way, and `parse` turns all of them into the one answer.
     */
    private static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Malformed() {
            super(null, null, false, false);
        }
    }

    private final String source;
    private int at;

    private MolangParser(String source) {
        this.source = source;
        this.at = 0;
    }

    /** Parses a whole Molang value, which may be a plain expression or a list of statements. */
    public static MolangExpression parse(String source) {
        if (source == null || source.isBlank()) return MolangExpression.ZERO;

        try {
            return new MolangParser(source).parseProgram();
        } catch (Malformed ignored) {
            return MolangExpression.ZERO;
        }
    }

    /** Bedrock also allows a bare number where an expression is expected. */
    public static MolangExpression parse(Object value) {
        if (value instanceof Number number) return MolangExpression.constant(number.doubleValue());
        if (value instanceof String text) return parse(text);
        return MolangExpression.ZERO;
    }

    private MolangExpression parseProgram() {
        List<MolangExpression> statements = new ArrayList<>();
        boolean returns = false;

        while (true) {
            skipSpace();
            if (at >= source.length()) break;

            if (word("return")) {
                MolangExpression value = parseExpression();
                statements.add(value);
                returns = true;
                skipSpace();
                if (peek() == ';') at += 1;
                break;
            }

            statements.add(parseExpression());
            skipSpace();
            if (peek() == ';') at += 1;
            else break;
        }

        skipSpace();
        if (at < source.length() || statements.isEmpty()) throw new Malformed();
        if (statements.size() == 1) return statements.get(0);

        List<MolangExpression> program = List.copyOf(statements);
        int last = program.size() - 1;
        boolean valueIsLast = returns;

        return environment -> {
            MolangValue result = MolangValue.ZERO;
            for (int i = 0; i < program.size(); i++) {
                MolangValue value = program.get(i).evaluate(environment);
                if (i == last && valueIsLast) result = value;
            }
            return result;
        };
    }

    private MolangExpression parseExpression() {
        return parseTernary();
    }

    private MolangExpression parseBlock() {
        List<MolangExpression> statements = new ArrayList<>();
        while (true) {
            skipSpace();
            if (at >= source.length() || peek() == '}') break;

            boolean returns = word("return");
            statements.add(parseExpression());
            skipSpace();
            if (peek() == ';') at += 1;
            if (returns) break;
        }

        List<MolangExpression> block = List.copyOf(statements);
        return environment -> {
            MolangValue result = MolangValue.ZERO;
            for (MolangExpression statement : block) result = statement.evaluate(environment);
            return result;
        };
    }

    private MolangExpression parseTernary() {
        MolangExpression condition = parseNullCoalesce();
        skipSpace();
        if (peek() != '?' || peekAt(1) == '?') return condition;

        at += 1;
        MolangExpression whenTrue = parseExpression();
        skipSpace();

        // `a ? b` with no colon is legal Molang and yields zero when false.
        if (peek() != ':') {
            return environment -> condition.evaluate(environment).truthy()
                ? whenTrue.evaluate(environment)
                : MolangValue.ZERO;
        }
        at += 1;
        MolangExpression whenFalse = parseExpression();
        return environment -> condition.evaluate(environment).truthy()
            ? whenTrue.evaluate(environment)
            : whenFalse.evaluate(environment);
    }

    private MolangExpression parseNullCoalesce() {
        MolangExpression left = parseOr();
        skipSpace();
        if (peek() != '?' || peekAt(1) != '?') return left;

        at += 2;
        MolangExpression right = parseNullCoalesce();
        return environment -> left instanceof MolangExpression.Maybe maybe && !maybe.isPresent(environment)
            ? right.evaluate(environment)
            : left.evaluate(environment);
    }

    private MolangExpression parseOr() {
        MolangExpression left = parseAnd();
        while (true) {
            skipSpace();
            if (peek() != '|' || peekAt(1) != '|') return left;
            at += 2;
            MolangExpression right = parseAnd();
            MolangExpression current = left;
            left = environment -> MolangValue.of(
                current.evaluate(environment).truthy() || right.evaluate(environment).truthy());
        }
    }

    private MolangExpression parseAnd() {
        MolangExpression left = parseEquality();
        while (true) {
            skipSpace();
            if (peek() != '&' || peekAt(1) != '&') return left;
            at += 2;
            MolangExpression right = parseEquality();
            MolangExpression current = left;
            left = environment -> MolangValue.of(
                current.evaluate(environment).truthy() && right.evaluate(environment).truthy());
        }
    }

    private MolangExpression parseEquality() {
        MolangExpression left = parseRelational();
        while (true) {
            skipSpace();
            boolean equals = peek() == '=' && peekAt(1) == '=';
            boolean differs = peek() == '!' && peekAt(1) == '=';
            if (!equals && !differs) return left;

            at += 2;
            MolangExpression right = parseRelational();
            MolangExpression current = left;
            left = environment -> MolangValue.of(
                current.evaluate(environment).matches(right.evaluate(environment)) == equals);
        }
    }

    private MolangExpression parseRelational() {
        MolangExpression left = parseAdditive();
        while (true) {
            skipSpace();
            char operator = peek();
            if (operator != '<' && operator != '>') return left;
            boolean orEqual = peekAt(1) == '=';

            at += orEqual ? 2 : 1;
            MolangExpression right = parseAdditive();
            MolangExpression current = left;
            boolean lessThan = operator == '<';
            left = environment -> {
                double a = current.evaluate(environment).number();
                double b = right.evaluate(environment).number();
                boolean result = lessThan ? (orEqual ? a <= b : a < b) : (orEqual ? a >= b : a > b);
                return MolangValue.of(result);
            };
        }
    }

    private MolangExpression parseAdditive() {
        MolangExpression left = parseMultiplicative();
        while (true) {
            skipSpace();
            char operator = peek();
            if (operator != '+' && operator != '-') return left;

            at += 1;
            MolangExpression right = parseMultiplicative();
            MolangExpression current = left;
            boolean add = operator == '+';
            left = environment -> MolangValue.of(add
                ? current.evaluate(environment).number() + right.evaluate(environment).number()
                : current.evaluate(environment).number() - right.evaluate(environment).number());
        }
    }

    private MolangExpression parseMultiplicative() {
        MolangExpression left = parseUnary();
        while (true) {
            skipSpace();
            char operator = peek();
            if (operator != '*' && operator != '/') return left;

            at += 1;
            MolangExpression right = parseUnary();
            MolangExpression current = left;
            boolean multiply = operator == '*';
            left = environment -> {
                double a = current.evaluate(environment).number();
                double b = right.evaluate(environment).number();
                if (multiply) return MolangValue.of(a * b);
                return MolangValue.of(b == 0 ? 0 : a / b);
            };
        }
    }

    private MolangExpression parseUnary() {
        skipSpace();
        if (peek() == '-') {
            at += 1;
            MolangExpression operand = parseUnary();
            return environment -> MolangValue.of(-operand.evaluate(environment).number());
        }
        if (peek() == '!' && peekAt(1) != '=') {
            at += 1;
            MolangExpression operand = parseUnary();
            return environment -> MolangValue.of(!operand.evaluate(environment).truthy());
        }
        return parsePrimary();
    }

    private MolangExpression parsePrimary() {
        skipSpace();
        char start = peek();

        if (start == '(') {
            at += 1;
            MolangExpression inner = parseExpression();
            skipSpace();
            if (peek() == ')') at += 1;
            return inner;
        }
        // A brace block groups statements where one expression is expected, as a ternary branch does.
        if (start == '{') {
            at += 1;
            MolangExpression block = parseBlock();
            skipSpace();
            if (peek() == '}') at += 1;
            return block;
        }
        if (start == '\'' || start == '"') return parseString(start);
        if (Character.isDigit(start) || start == '.') return parseNumber();
        if (Character.isLetter(start) || start == '_') return parsePath();

        throw new Malformed();
    }

    private MolangExpression parseString(char quote) {
        at += 1;
        int start = at;
        while (at < source.length() && source.charAt(at) != quote) at += 1;
        MolangValue value = MolangValue.of(source.substring(start, at));
        if (at < source.length()) at += 1;
        return environment -> value;
    }

    private MolangExpression parseNumber() {
        int start = at;
        while (at < source.length() && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.')) at += 1;
        String text = source.substring(start, at);
        try {
            return MolangExpression.constant(Double.parseDouble(text));
        } catch (NumberFormatException ignored) {
            throw new Malformed();
        }
    }

    /** Handles `this`, `Math.f(...)`, `q.name(...)`, `v.name`, `t.name`, and assignment to a variable. */
    private MolangExpression parsePath() {
        String head = readName().toLowerCase(Locale.ROOT);

        if (head.equals("this")) return MolangEnvironment::self;
        if (head.equals("true")) return environment -> MolangValue.ONE;
        if (head.equals("false")) return environment -> MolangValue.ZERO;

        if (head.equals("loop")) return parseLoop();
        if (head.equals("for_each")) return parseForEach();

        skipSpace();
        if (peek() != '.') throw new Malformed();
        at += 1;
        // A variable can hold a struct, so the name may carry further dotted segments.
        StringBuilder path = new StringBuilder(readName());
        while (peek() == '.' && (Character.isLetter(peekAt(1)) || peekAt(1) == '_')) {
            at += 1;
            path.append('.').append(readName());
        }
        String name = path.toString();
        String insensitive = name.toLowerCase(Locale.ROOT);

        return arrow(switch (head) {
            case "math" -> parseCall(arguments -> MolangMath.call(insensitive, arguments));
            case "query", "q", "context", "c" -> {
                List<MolangExpression> arguments = readArguments();
                yield environment -> environment.query(insensitive, evaluateAll(arguments, environment));
            }
            case "variable", "v" -> parseVariable(insensitive, false);
            case "temp", "t" -> parseVariable(insensitive, true);
            // A render controller names its textures, geometries and materials rather than computing them.
            case "texture", "geometry", "material" -> {
                MolangValue key = MolangValue.of(name);
                yield environment -> key;
            }
            case "array" -> parseArrayIndex(name);
            default -> throw new Malformed();
        }, name);
    }

    /**
     * `c.owning_entity->v.name` reads a variable out of another entity's environment.
     *
     * The left side names the link; what follows is parsed as an ordinary path and evaluated against
     * whatever was linked under that name. Nothing linked reads as zero, the same as an unknown query.
     */
    private MolangExpression arrow(MolangExpression left, String name) {
        skipSpace();
        if (peek() != '-' || peekAt(1) != '>') return left;

        at += 2;
        skipSpace();
        MolangExpression right = parsePrimary();
        return environment -> {
            MolangEnvironment linked = environment.link(name);
            return linked == null ? MolangValue.ZERO : right.evaluate(linked);
        };
    }

    /**
     * `loop(count, {statements})` runs its block a fixed number of times.
     *
     * Bedrock caps the count so a pack cannot hang the game inside one expression, and so does this.
     */
    private MolangExpression parseLoop() {
        List<MolangExpression> arguments = readArguments();
        if (arguments.size() < 2) throw new Malformed();

        MolangExpression count = arguments.get(0);
        MolangExpression body = arguments.get(1);
        return environment -> {
            int times = Math.min((int) count.evaluate(environment).number(), LOOP_LIMIT);
            MolangValue result = MolangValue.ZERO;
            for (int i = 0; i < times; i++) result = body.evaluate(environment);
            return result;
        };
    }

    /** `for_each(v.name, array, {statements})` runs its block once per element, binding each in turn. */
    private MolangExpression parseForEach() {
        List<MolangExpression> arguments = readArguments();
        if (arguments.size() < 3) throw new Malformed();
        return arguments.get(2);
    }

    private MolangExpression parseVariable(String name, boolean temporary) {
        skipSpace();
        // An assignment is a statement in its own right and evaluates to the value it stored.
        if (peek() == '=' && peekAt(1) != '=') {
            at += 1;
            MolangExpression value = parseExpression();
            return environment -> {
                MolangValue result = value.evaluate(environment);
                if (temporary) environment.setTemporary(name, result);
                else environment.setVariable(name, result);
                return result;
            };
        }
        return new MolangExpression.Maybe() {
            @Override
            public MolangValue evaluate(MolangEnvironment environment) {
                return temporary ? environment.temporary(name) : environment.variable(name);
            }

            @Override
            public boolean isPresent(MolangEnvironment environment) {
                return temporary ? environment.hasTemporary(name) : environment.hasVariable(name);
            }
        };
    }

    private MolangExpression parseArrayIndex(String name) {
        skipSpace();
        if (peek() != '[') return environment -> environment.element(name, 0);

        at += 1;
        MolangExpression index = parseExpression();
        skipSpace();
        if (peek() == ']') at += 1;
        return environment -> environment.element(name, (int) index.evaluate(environment).number());
    }

    private MolangExpression parseCall(java.util.function.Function<List<MolangValue>, MolangValue> function) {
        List<MolangExpression> arguments = readArguments();
        return environment -> function.apply(evaluateAll(arguments, environment));
    }

    private List<MolangExpression> readArguments() {
        skipSpace();
        if (peek() != '(') return List.of();

        at += 1;
        List<MolangExpression> arguments = new ArrayList<>();
        skipSpace();
        if (peek() == ')') {
            at += 1;
            return arguments;
        }
        while (true) {
            arguments.add(parseExpression());
            skipSpace();
            if (peek() == ',') {
                at += 1;
                continue;
            }
            if (peek() == ')') at += 1;
            break;
        }
        return List.copyOf(arguments);
    }

    private static List<MolangValue> evaluateAll(List<MolangExpression> arguments, MolangEnvironment environment) {
        List<MolangValue> values = new ArrayList<>(arguments.size());
        for (MolangExpression argument : arguments) values.add(argument.evaluate(environment));
        return values;
    }

    private String readName() {
        int start = at;
        while (at < source.length()) {
            char character = source.charAt(at);
            if (!Character.isLetterOrDigit(character) && character != '_') break;
            at += 1;
        }
        return source.substring(start, at);
    }

    private boolean word(String keyword) {
        skipSpace();
        if (!source.startsWith(keyword, at)) return false;
        int after = at + keyword.length();
        if (after < source.length()
            && (Character.isLetterOrDigit(source.charAt(after)) || source.charAt(after) == '_')) {
            return false;
        }
        at = after;
        return true;
    }

    private void skipSpace() {
        while (at < source.length() && Character.isWhitespace(source.charAt(at))) at += 1;
    }

    private char peek() {
        return at < source.length() ? source.charAt(at) : '\0';
    }

    private char peekAt(int offset) {
        int index = at + offset;
        return index < source.length() ? source.charAt(index) : '\0';
    }
}
