package at.ioexception.mcaddon.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the server gives up on a client it is handing add-ons to.
 *
 * A client that holds every archive it asked for sends nothing while it converts and activates
 * them, so silence then is no reason to drop it. Silence before it has asked is.
 */
class OutgoingTest {
    private static final long MINUTE = 60_000;

    private final List<String> refused = new ArrayList<>();
    private final Outgoing outgoing = new Outgoing(new AddonManifest("1", List.of()), new Outgoing.Link() {
        @Override
        public void send(Transfer.Piece piece) {
        }

        @Override
        public void refuse(String reason) {
            refused.add(reason);
        }

        @Override
        public void done() {
        }
    });

    @Test
    void dropsAClientThatNeverAsks() {
        assertTrue(outgoing.expire(System.currentTimeMillis() + MINUTE + 1000));
        assertEquals(1, refused.size());
    }

    @Test
    void waitsOnAClientActivatingWhatItHolds() {
        outgoing.request(List.of());
        assertFalse(outgoing.expire(System.currentTimeMillis() + 5 * MINUTE));
        assertTrue(refused.isEmpty());
    }

    @Test
    void dropsAClientPastTenMinutesWhateverItIsDoing() {
        outgoing.request(List.of());
        assertTrue(outgoing.expire(System.currentTimeMillis() + 11 * MINUTE));
    }
}
