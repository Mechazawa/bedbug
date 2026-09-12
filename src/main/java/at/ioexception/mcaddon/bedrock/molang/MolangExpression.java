package at.ioexception.mcaddon.bedrock.molang;

/** A parsed Molang expression, ready to be evaluated against an environment. */
@FunctionalInterface
public interface MolangExpression {
    /**
     * An expression that can be absent, which is the difference `??` turns on.
     *
     * Bedrock's null coalescing operator falls through when the left side was never assigned, not
     * when it happens to be zero, so a variable read has to be able to say which it is.
     */
    interface Maybe extends MolangExpression {
        boolean isPresent(MolangEnvironment environment);
    }

    MolangValue evaluate(MolangEnvironment environment);

    MolangExpression ZERO = environment -> MolangValue.ZERO;

    default float asFloat(MolangEnvironment environment) {
        return evaluate(environment).asFloat();
    }

    static MolangExpression constant(double value) {
        MolangValue result = MolangValue.of(value);
        return environment -> result;
    }
}
