package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.resource.AddonSet;
import at.ioexception.mcaddon.server.Dialogues;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Which add-ons answer on this side now.
 *
 * The registries hold every add-on this process has registered and never let go of one. The active
 * set is what the game is offered of them: the converted packs in the pack repository, the items in
 * the creative tabs, the music, fogs, camera presets, dialogue scenes and brewing a pack declares,
 * and, on a client, the models it draws with. A registered add-on outside the active set is dormant:
 * its entries stay where they are with the last definitions bound to them, and nothing offers them.
 *
 * A server's active set is always the one installed beside it. A client's is the installed set until
 * it joins a server, the server's set while it plays there, and the installed set again once it
 * leaves.
 */
public final class ActiveAddons {
    private static final List<Consumer<AddonSet>> LISTENERS = new ArrayList<>();

    private static AddonSet installed = AddonSet.EMPTY;
    private static AddonSet active = AddonSet.EMPTY;
    /** Read by the resource reload's worker threads while the client thread may activate another set. */
    private static volatile Set<Identifier> activeItems = Set.of();

    private ActiveAddons() {
    }

    /** The add-ons found in `addons/` at startup. */
    public static AddonSet installed() {
        return installed;
    }

    public static AddonSet active() {
        return active;
    }

    /** Makes the add-ons found at startup the installed set, and the active one. */
    public static void install(AddonSet set) {
        installed = set;
        activate(set);
    }

    /**
     * Makes `set` the one that answers, registering whatever of it is not registered yet.
     *
     * An entry a previous set registered and this one does not carry stays registered and goes
     * dormant. One both carry is bound to this set's definition.
     */
    public static void activate(AddonSet set) {
        active = set;
        Set<net.minecraft.world.item.Item> items = AddonRegistry.ensureRegistered(set);
        activeItems = items.stream().map(BuiltInRegistries.ITEM::getKey).collect(Collectors.toUnmodifiableSet());
        AddonSounds.readMusic(set.converted());
        AddonFogs.register(set.converted());
        AddonCameras.register(set.packs());
        Dialogues.read(set);
        AddonCreativeTab.show(set.packs(), items);
        LISTENERS.forEach(listener -> listener.accept(set));
    }

    /**
     * Whether `id` names an item an add-on registered that the active set does not carry. A block
     * is asked about by its own identifier, which its item shares.
     */
    public static boolean isDormant(Identifier id) {
        return AddonRegistry.registeredItem(id) && !activeItems.contains(id);
    }

    /** Runs after every activation, which is how the drawing side follows the active set. */
    public static void onActivate(Consumer<AddonSet> listener) {
        LISTENERS.add(listener);
    }
}
