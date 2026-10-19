package at.ioexception.mcaddon.bedrock.behaviour;

/**
 * A bundle's contents while something is put in, told how much the bag they belong to holds.
 *
 * Java's contents know nothing of the bag carrying them and measure every bag against the one
 * capacity, so the bag hands its own to them as they are opened for a change.
 */
public interface BagCapacity {
    /** @param limit in Bedrock's weight units, of which Java's own bundle holds 64 */
    void mcaddon$holdUpTo(int limit);
}
