package at.ioexception.mcaddon.bedrock.molang;

/**
 * A Molang value: a number, or a string.
 *
 * Strings are rare but real. The talking animations assign one to pick a mouth shape, and render
 * controllers compare against them, so collapsing everything to a number would lose that.
 */
public record MolangValue(double number, String text) {
    public static final MolangValue ZERO = new MolangValue(0, null);
    public static final MolangValue ONE = new MolangValue(1, null);

    public static MolangValue of(double number) {
        return new MolangValue(number, null);
    }

    public static MolangValue of(boolean value) {
        return value ? ONE : ZERO;
    }

    public static MolangValue of(String text) {
        return new MolangValue(0, text);
    }

    public boolean isText() {
        return text != null;
    }

    public float asFloat() {
        return (float) number;
    }

    /** Molang treats any non-zero number as true; a string is true when it is not empty. */
    public boolean truthy() {
        return isText() ? !text.isEmpty() : number != 0;
    }

    public boolean matches(MolangValue other) {
        if (isText() || other.isText()) return isText() && other.isText() && text.equals(other.text);
        return number == other.number;
    }
}
