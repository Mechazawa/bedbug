package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.state.WorldAddonList;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

import java.util.Optional;

/**
 * The add-on list chosen for a world that is being created, which the world takes as it first opens.
 *
 * Create World loads its data packs as it opens and builds the world from them, so a choice is made
 * active, the resources reloaded for it and the screen's data packs loaded again before the world
 * can be made. Leaving Create World without a world puts the installed set back.
 */
public final class WorldAddons {
    private static volatile WorldAddonList pending;

    private WorldAddons() {
    }

    /** What Create World can do with its own data packs, which its mixin answers. */
    public interface DataPacks {
        /** Loads the screen's data packs again from the pack repository as it is now. */
        void mcaddon$reapply();
    }

    public static void pending(WorldAddonList list) {
        pending = list;
    }

    /** The list chosen for the world being created, left in place for the world to take. */
    public static Optional<WorldAddonList> chosen() {
        return Optional.ofNullable(pending);
    }

    public static Optional<WorldAddonList> takePending() {
        Optional<WorldAddonList> taken = Optional.ofNullable(pending);
        pending = null;
        return taken;
    }

    /** Plays `list` in `screen`: activates its add-ons and reloads the resources and the screen's data packs. */
    public static void choose(CreateWorldScreen screen, WorldAddonList list) {
        pending = list;
        AddonSession.play(list.resolve(ActiveAddons.installed())).thenRun(() -> Platform.schedule(() -> {
            if (Platform.currentScreen() == screen) ((DataPacks) screen).mcaddon$reapply();
        }));
    }

    /** Forgets the choice and puts the installed add-ons back, as Create World closes without a world. */
    public static void cancel() {
        pending = null;
        AddonSession.leave();
    }
}
