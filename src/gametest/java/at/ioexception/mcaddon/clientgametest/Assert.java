package at.ioexception.mcaddon.clientgametest;

/** What a client game test fails with, worded as what was expected and what was found. */
final class Assert {
    private Assert() {
    }

    static void that(boolean holds, String failure) {
        if (!holds) throw new AssertionError(failure);
    }

    static void near(double found, double wanted, double tolerance, String what) {
        if (Math.abs(found - wanted) > tolerance) {
            throw new AssertionError("Expected " + what + " to be " + wanted + " and found " + found + ".");
        }
    }
}
