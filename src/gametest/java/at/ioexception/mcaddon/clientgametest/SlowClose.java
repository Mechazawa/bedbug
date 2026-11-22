package at.ioexception.mcaddon.clientgametest;

/**
 * Holds the client's network thread for a second between closing a connection and storing why it
 * closed, while a test asks for it, so the client ticks inside that window.
 */
public final class SlowClose {
    private static volatile boolean on;

    private SlowClose() {
    }

    public static void during(Runnable test) {
        on = true;
        try {
            test.run();
        } finally {
            on = false;
        }
    }

    public static void hold() {
        if (!on) return;
        try {
            Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
