package at.ioexception.mcaddon.sync;

/**
 * The messages of an add-on transfer, whatever phase of the connection carries them.
 *
 * A server sends the manifest; a client answers with the archives it wants and where each one's
 * partial copy ends; the server sends pieces, which the client acknowledges as it writes them; and
 * the client says it is ready, or why it is not, once the server's add-ons are active.
 */
public final class Transfer {
    /** The most bytes of an archive one piece carries, which leaves room in a one megabyte payload. */
    public static final int PIECE = 1_000_000;

    /** The most pieces a server sends before an acknowledgement. */
    public static final int WINDOW = 4;

    private Transfer() {
    }

    /** An archive a client wants, from `offset` on; as an acknowledgement, how much it holds. */
    public record Wanted(String sha256, long offset) {
    }

    public record Piece(String sha256, long offset, byte[] data) {
    }

    public record Ready(boolean ok, String reason) {
    }

    /** The most archives one request names. */
    public static final int MAX_WANTED = 64;
}
