package at.ioexception.mcaddon.server;

/** A player list whose limit can be set for the session, which `PlayerListLimitMixin` gives Java's. */
public interface PlayerLimit {
    void mcaddon$limitPlayers(int limit);
}
