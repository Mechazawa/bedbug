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
 * variables and the structs they hold, `this`, and statement lists ending in `return`.
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

    /**
     * A `return` inside a brace block, which ends the whole expression with its value. Packs pick a
     * texture with `v.a ? {return Texture.a;}; return Texture.b;`, and a return that ended only its
     * block would always answer `Texture.b`.
     */
    private static final class Returned extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient MolangValue value;

        Returned(MolangValue value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /**
     * A `break` or a `continue`, which ends the pass of the innermost loop running it. Neither is
     * parsed outside a loop, so one never escapes the expression.
     */
    private static final class Jump extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final Jump BREAK = new Jump();
        private static final Jump CONTINUE = new Jump();

        private Jump() {
            super(null, null, false, false);
        }
    }

    private final String source;
    private int at;
    private boolean returnsFromBlock;

    /** How many loops the parser is inside, which is where `break` and `continue` may stand. */
    private int loops;

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
        MolangExpression whole = statements.get(0);
        if (statements.size() > 1) {
            List<MolangExpression> program = List.copyOf(statements);
            int last = program.size() - 1;
            boolean valueIsLast = returns;
            whole = environment -> {
                MolangValue result = MolangValue.ZERO;
                for (int i = 0; i < program.size(); i++) {
                    MolangValue value = program.get(i).evaluate(environment);
                    if (i == last && valueIsLast) result = value;
                }
                return result;
            };
        }
        if (!returnsFromBlock) return whole;

        MolangExpression body = whole;
        return environment -> {
            try {
                return body.evaluate(environment);
            } catch (Returned returned) {
                return returned.value;
            }
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
            MolangExpression statement = parseExpression();
            if (returns) {
                returnsFromBlock = true;
                statements.add(environment -> {
                    throw new Returned(statement.evaluate(environment));
                });
            } else {
                statements.add(statement);
            }
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
        if (head.equals("break") || head.equals("continue")) {
            if (loops == 0) throw new Malformed();
            Jump jump = head.equals("break") ? Jump.BREAK : Jump.CONTINUE;
            return environment -> {
                throw jump;
            };
        }

        skipSpace();
        if (peek() != '.') throw new Malformed();
        at += 1;
        // A variable or a query can hold a struct, so the name may carry members after it.
        StringBuilder path = new StringBuilder(readName());
        List<String> members = new ArrayList<>();
        while (peek() == '.' && (Character.isLetter(peekAt(1)) || peekAt(1) == '_')) {
            at += 1;
            String member = readName();
            path.append('.').append(member);
            members.add(member.toLowerCase(Locale.ROOT));
        }
        String name = path.toString();
        String insensitive = name.toLowerCase(Locale.ROOT);
        String root = members.isEmpty() ? insensitive : insensitive.substring(0, insensitive.indexOf('.'));

        return arrow(switch (head) {
            case "math" -> parseCall(arguments -> MolangMath.call(insensitive, arguments));
            case "query", "q" -> {
                List<MolangExpression> arguments = readArguments();
                // A query taking arguments names the member it wants after the call.
                members.addAll(trailingMembers());
                List<String> member = List.copyOf(members);
                yield environment -> environment.query(root, evaluateAll(arguments, environment)).member(member);
            }
            case "context", "c" -> parseContext(root, List.copyOf(members));
            case "variable", "v" -> parseVariable(new Variable(false, root, List.copyOf(members)));
            case "temp", "t" -> parseVariable(new Variable(true, root, List.copyOf(members)));
            // A render controller names its textures, geometries and materials rather than computing them.
            case "texture", "geometry", "material" -> {
                MolangValue key = MolangValue.of(name);
                yield environment -> key;
            }
            case "array" -> parseArrayIndex(name);
            default -> throw new Malformed();
        });
    }

    /**
     * `left->right` evaluates `right` as the entity `left` refers to, so `v.friend->v.location` reads
     * the friend's variable and `t.pig->q.is_baby` asks the pig.
     *
     * A left side that refers to no entity, or to one that is gone, answers zero without evaluating
     * the right side.
     */
    private MolangExpression arrow(MolangExpression left) {
        skipSpace();
        if (peek() != '-' || peekAt(1) != '>') return left;

        at += 2;
        skipSpace();
        MolangExpression right = parsePrimary();
        return environment -> {
            MolangEnvironment scope = left.evaluate(environment).scope();
            return scope == null ? MolangValue.ZERO : right.evaluate(scope);
        };
    }

    /** `c.name`, which `??` falls through on where it holds an entity that is gone. */
    private MolangExpression parseContext(String name, List<String> members) {
        List<MolangExpression> arguments = readArguments();
        List<String> member = new ArrayList<>(members);
        member.addAll(trailingMembers());
        List<String> path = List.copyOf(member);
        return new MolangExpression.Maybe() {
            @Override
            public MolangValue evaluate(MolangEnvironment environment) {
                return environment.context(name, evaluateAll(arguments, environment)).member(path);
            }

            @Override
            public boolean isPresent(MolangEnvironment environment) {
                return evaluate(environment).resolves();
            }
        };
    }

    /**
     * `loop(count, {statements})` runs its block a fixed number of times.
     *
     * Bedrock caps the count so a pack cannot hang the game inside one expression, and so does this.
     */
    private MolangExpression parseLoop() {
        loops += 1;
        List<MolangExpression> arguments = readArguments();
        loops -= 1;
        if (arguments.size() < 2) throw new Malformed();

        MolangExpression count = arguments.get(0);
        MolangExpression body = arguments.get(1);
        return environment -> {
            int times = Math.min((int) count.evaluate(environment).number(), LOOP_LIMIT);
            for (int i = 0; i < times; i++) {
                if (!pass(body, environment)) break;
            }
            return MolangValue.ZERO;
        };
    }

    /** Runs one pass of a loop's body, answering false once a `break` has ended the loop. */
    private static boolean pass(MolangExpression body, MolangEnvironment environment) {
        try {
            body.evaluate(environment);
            return true;
        } catch (Jump jump) {
            return jump == Jump.CONTINUE;
        }
    }

    /**
     * `for_each(t.name, array, {statements})` runs its block once per entity the array holds, with
     * the variable holding that entity.
     */
    private MolangExpression parseForEach() {
        skipSpace();
        if (peek() != '(') throw new Malformed();
        at += 1;
        Variable each = readVariable();
        MolangExpression array = nextArgument();
        loops += 1;
        MolangExpression body = nextArgument();
        loops -= 1;
        skipSpace();
        if (peek() != ')') throw new Malformed();
        at += 1;

        return environment -> {
            for (MolangValue.Reference entity : array.evaluate(environment).references().toList()) {
                each.assign(environment, MolangValue.of(entity));
                if (!pass(body, environment)) break;
            }
            return MolangValue.ZERO;
        };
    }

    private MolangExpression nextArgument() {
        skipSpace();
        if (peek() != ',') throw new Malformed();
        at += 1;
        return parseExpression();
    }

    /** The `v.name` or `t.name` a statement assigns to, members included. */
    private Variable readVariable() {
        skipSpace();
        String head = readName().toLowerCase(Locale.ROOT);
        boolean temporary = head.equals("temp") || head.equals("t");
        if (!temporary && !head.equals("variable") && !head.equals("v")) throw new Malformed();
        if (peek() != '.') throw new Malformed();
        at += 1;
        String name = readName().toLowerCase(Locale.ROOT);
        return new Variable(temporary, name, trailingMembers());
    }

    /** A `v.` or `t.` variable, and the path of members into the struct it holds. */
    private record Variable(boolean temporary, String name, List<String> members) {
        MolangValue read(MolangEnvironment environment) {
            return (temporary ? environment.temporary(name) : environment.variable(name)).member(members);
        }

        boolean isSet(MolangEnvironment environment) {
            boolean held = temporary ? environment.hasTemporary(name) : environment.hasVariable(name);
            return held && (temporary ? environment.temporary(name) : environment.variable(name)).hasMember(members);
        }

        void assign(MolangEnvironment environment, MolangValue value) {
            if (temporary) environment.setTemporary(name, environment.temporary(name).with(members, value));
            else environment.setVariable(name, environment.variable(name).with(members, value));
        }
    }

    private MolangExpression parseVariable(Variable variable) {
        skipSpace();
        // An assignment is a statement in its own right and evaluates to the value it stored.
        if (peek() == '=' && peekAt(1) != '=') {
            at += 1;
            MolangExpression value = parseExpression();
            return environment -> {
                MolangValue result = value.evaluate(environment);
                variable.assign(environment, result);
                return result;
            };
        }
        return new MolangExpression.Maybe() {
            @Override
            public MolangValue evaluate(MolangEnvironment environment) {
                return variable.read(environment);
            }

            @Override
            public boolean isPresent(MolangEnvironment environment) {
                return variable.isSet(environment) && variable.read(environment).resolves();
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

    /** The `.x` chain trailing a call, which names one member of the struct the call answered with. */
    private List<String> trailingMembers() {
        List<String> members = new ArrayList<>();
        while (peek() == '.' && (Character.isLetter(peekAt(1)) || peekAt(1) == '_')) {
            at += 1;
            members.add(readName().toLowerCase(Locale.ROOT));
        }
        return members;
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
