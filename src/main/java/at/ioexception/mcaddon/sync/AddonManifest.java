package at.ioexception.mcaddon.sync;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The add-ons a server plays with, as it tells a client joining it.
 *
 * Each add-on is one archive, named by the sha256 of its bytes, with the packs inside it beside it
 * for the messages a player reads. An archive the server holds only so that its registry entries
 * have a source is listed as not active: a client registers it and offers none of it.
 *
 * @param modVersion the version of this mod the server runs, because what an archive registers is
 *                   a function of the converter as much as of the archive
 */
public record AddonManifest(String modVersion, List<Entry> entries) {
    /** The most characters a manifest may take on the wire. */
    public static final int MAX_LENGTH = 262_144;

    /** The most archives one server may ask a client to hold at once. */
    static final int MAX_ARCHIVES = 64;

    /** The largest archive a client takes from a server. */
    static final long MAX_ARCHIVE = 256L << 20;

    /** The most a client downloads to join one server. */
    static final long MAX_TOTAL = 1L << 30;

    /** What a client keeps free on its disk, whatever a server asks it to hold. */
    static final long FREE_MARGIN = 512L << 20;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_NAME = 256;
    private static final Gson GSON = new Gson();

    public record Entry(String sha256, long size, boolean active, List<PackId> packs) {
        /** The name a player knows this add-on by. */
        public String name() {
            return packs.isEmpty() ? sha256.substring(0, 8) : packs.get(0).name() + " " + packs.get(0).version();
        }
    }

    public record PackId(String uuid, String version, String name) {
    }

    public String encode() {
        return GSON.toJson(this);
    }

    /** Reads what a server sent, refusing anything that is not a manifest this mod would write. */
    public static AddonManifest decode(String json) {
        AddonManifest manifest;
        try {
            manifest = GSON.fromJson(json, AddonManifest.class);
        } catch (JsonParseException malformed) {
            throw new IllegalArgumentException("the server's add-on list is not readable", malformed);
        }
        if (manifest == null || manifest.modVersion() == null || manifest.entries() == null) {
            throw new IllegalArgumentException("the server's add-on list is empty");
        }
        for (Entry entry : manifest.entries()) {
            if (entry == null || entry.sha256() == null || !SHA256.matcher(entry.sha256()).matches()) {
                throw new IllegalArgumentException("the server's add-on list names an archive by no digest");
            }
            if (entry.packs() == null || entry.size() <= 0 || entry.packs().stream().anyMatch(pack -> pack == null
                || pack.name() == null || pack.name().length() > MAX_NAME || pack.version() == null
                || pack.uuid() == null || pack.version().length() > MAX_NAME || pack.uuid().length() > MAX_NAME)) {
                throw new IllegalArgumentException("the server's add-on list describes an archive it cannot");
            }
        }
        return manifest;
    }

    /** The archives a client holding `held` still has to fetch. */
    public List<Entry> toFetch(Set<String> held) {
        return entries.stream().filter(entry -> !held.contains(entry.sha256())).toList();
    }

    /**
     * Why a client refuses to take these add-ons, in the words the player reads, or nothing when it
     * takes them. Asked before anything is requested, so a refused server costs no download.
     *
     * @param ownVersion the version of this mod the client runs
     * @param held       the archives the client already holds
     * @param free       the bytes free where the client keeps what it receives
     */
    public Optional<String> refusal(String ownVersion, Set<String> held, long free) {
        if (!modVersion.equals(ownVersion)) {
            return Optional.of("This server runs Bedrock Add-on Loader " + modVersion + " and you run " + ownVersion
                + ". Install " + modVersion + " to join it.");
        }
        if (entries.size() > MAX_ARCHIVES) {
            return Optional.of("This server asks for " + entries.size() + " add-ons, more than the " + MAX_ARCHIVES
                + " a client takes.");
        }
        List<Entry> missing = toFetch(held);
        Optional<Entry> oversized = missing.stream().filter(entry -> entry.size() > MAX_ARCHIVE).findFirst();
        if (oversized.isPresent()) {
            return Optional.of("The server's add-on " + oversized.get().name() + " is "
                + megabytes(oversized.get().size()) + ", more than the " + megabytes(MAX_ARCHIVE)
                + " a client downloads.");
        }
        long total = missing.stream().mapToLong(Entry::size).sum();
        if (total > MAX_TOTAL) {
            return Optional.of("The server's add-ons come to " + megabytes(total) + ", more than the "
                + megabytes(MAX_TOTAL) + " a client downloads to join.");
        }
        if (total > free - FREE_MARGIN) {
            return Optional.of("The server's add-ons come to " + megabytes(total) + " and this disk has "
                + megabytes(Math.max(0, free - FREE_MARGIN)) + " to spare.");
        }
        return Optional.empty();
    }

    static String megabytes(long bytes) {
        return (bytes + (1 << 20) - 1 >> 20) + " MB";
    }
}
