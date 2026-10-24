package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.state.WorldAddonList;

import java.util.Optional;

/** The add-on list chosen for a world that is being created, which the world takes as it first opens. */
public final class WorldAddons {
    private static volatile WorldAddonList pending;

    private WorldAddons() {
    }

    public static void pending(WorldAddonList list) {
        pending = list;
    }

    public static Optional<WorldAddonList> takePending() {
        Optional<WorldAddonList> taken = Optional.ofNullable(pending);
        pending = null;
        return taken;
    }
}
