package at.ioexception.mcaddon.sync;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a client makes of a server's add-on list before it asks for anything.
 *
 * The list is the first thing a server sends that this mod reads, so anything a server writes into
 * it is untrusted: a list it would not write is refused, and one asking for more than a client is
 * willing to hold is refused with the reason before any download starts.
 */
class AddonManifestTest {
    private static final String ONE = "a".repeat(64);
    private static final String TWO = "b".repeat(64);
    private static final long FREE = 100L << 30;

    @Test
    void readsBackWhatItWrote() {
        AddonManifest manifest = manifest(entry(ONE, 10, true), entry(TWO, 20, false));
        assertEquals(manifest, AddonManifest.decode(manifest.encode()));
    }

    @Test
    void refusesAnArchiveNamedByNoDigest() {
        String json = manifest(entry("not a digest", 10, true)).encode();
        assertThrows(IllegalArgumentException.class, () -> AddonManifest.decode(json));
    }

    @Test
    void refusesANameLongerThanAnyPackWrites() {
        AddonManifest.Entry entry = new AddonManifest.Entry(ONE, 10, true,
            List.of(new AddonManifest.PackId("uuid", "1.0.0", "x".repeat(257))));
        String json = manifest(entry).encode();
        assertThrows(IllegalArgumentException.class, () -> AddonManifest.decode(json));
    }

    @Test
    void fetchesOnlyWhatIsNotHeld() {
        AddonManifest manifest = manifest(entry(ONE, 10, true), entry(TWO, 20, true));
        assertEquals(List.of(manifest.entries().get(1)), manifest.toFetch(Set.of(ONE)));
    }

    @Test
    void refusesAnotherBuildOfThisModNamingBoth() {
        String refusal = manifest(entry(ONE, 10, true)).refusal("0.2.0", Set.of(), FREE).orElseThrow();
        assertTrue(refusal.contains("0.1.0") && refusal.contains("0.2.0"), refusal);
    }

    @Test
    void refusesAnArchiveOverTheLimitAndNamesIt() {
        String refusal = manifest(entry(ONE, AddonManifest.MAX_ARCHIVE + 1, true))
            .refusal("0.1.0", Set.of(), FREE).orElseThrow();
        assertTrue(refusal.contains("Pack " + ONE.substring(0, 4)), refusal);
    }

    @Test
    void refusesMoreThanAJoinDownloadsInAll() {
        long each = AddonManifest.MAX_ARCHIVE;
        AddonManifest manifest = manifest(entry(ONE, each, true), entry(TWO, each, true),
            entry("c".repeat(64), each, true), entry("d".repeat(64), each, true), entry("e".repeat(64), 1, true));
        assertTrue(manifest.refusal("0.1.0", Set.of(), FREE).isPresent());
    }

    @Test
    void refusesWhatTheDiskHasNoRoomFor() {
        AddonManifest manifest = manifest(entry(ONE, 10L << 20, true));
        assertTrue(manifest.refusal("0.1.0", Set.of(), AddonManifest.FREE_MARGIN).isPresent());
    }

    @Test
    void refusesMoreArchivesThanAClientHolds() {
        AddonManifest.Entry[] many = new AddonManifest.Entry[AddonManifest.MAX_ARCHIVES + 1];
        for (int index = 0; index < many.length; index++) many[index] = entry(ONE, 1, true);
        assertTrue(manifest(many).refusal("0.1.0", Set.of(ONE), FREE).isPresent());
    }

    @Test
    void takesWhatItAlreadyHoldsWhateverItsSize() {
        AddonManifest manifest = manifest(entry(ONE, AddonManifest.MAX_TOTAL * 2, true));
        assertTrue(manifest.refusal("0.1.0", Set.of(ONE), FREE).isEmpty());
    }

    private static AddonManifest manifest(AddonManifest.Entry... entries) {
        return new AddonManifest("0.1.0", List.of(entries));
    }

    private static AddonManifest.Entry entry(String sha256, long size, boolean active) {
        return new AddonManifest.Entry(sha256, size, active,
            List.of(new AddonManifest.PackId("uuid", "1.0.0", "Pack " + sha256.substring(0, 4))));
    }
}
