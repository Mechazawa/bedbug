package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.resource.AddonSet;
import at.ioexception.mcaddon.server.Dialogues;
import at.ioexception.mcaddon.state.WorldAddonList;
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
 * the creative tabs, the music, fogs, camera presets, dialogue scenes, settings and brewing a pack
 * declares, and, on a client, the models it draws with. A registered add-on outside the active set is dormant:
 * its entries stay where they are with the last definitions bound to them, and nothing offers them.
 *
 * A server's active set is what its world's add-on list turns on of the ones installed beside it. A
 * client's is the installed set until it opens a world or joins a server, the world's or the server's
 * set while it plays there, and the installed set again once it leaves.
 */
public final class ActiveAddons {
    private static final List<Consumer<AddonSet>> LISTENERS = new ArrayList<>();

    private static AddonSet installed = AddonSet.EMPTY;
    private static AddonSet active = AddonSet.EMPTY;
    private static WorldAddonList world = new WorldAddonList(List.of());
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

    /** The add-on list of the world a server in this process opened last. */
    public static WorldAddonList world() {
        return world;
    }

    /** Plays the add-ons a world's list turns on, activating them unless they already answer. */
    public static void open(WorldAddonList list) {
        world = list;
        AddonSet set = list.resolve(installed);
        if (!isActive(set)) activate(set);
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
        at.ioexception.mcaddon.pack.PackSettings.read(set.packs());
        Set<net.minecraft.world.item.Item> items = AddonRegistry.ensureRegistered(set);
        activeItems = items.stream().map(BuiltInRegistries.ITEM::getKey).collect(Collectors.toUnmodifiableSet());
        AddonSounds.readMusic(set.converted());
        AddonSounds.readVoices(set.converted());
        AddonSounds.readEvents(set.converted(), AddonItems.swingEvents());
        AddonFogs.register(set.converted());
        AddonAimAssist.register(set.packs());
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

    /** Whether `set` carries the same converted add-ons as the active set, whichever object holds them. */
    public static boolean isActive(AddonSet set) {
        return rootsOf(set).equals(rootsOf(active));
    }

    /** Whether the active set carries the add-on converted to `namespace`. */
    public static boolean isActive(String namespace) {
        return active.converted().stream().anyMatch(one -> one.namespace().equals(namespace));
    }

    private static List<java.nio.file.Path> rootsOf(AddonSet set) {
        return set.converted().stream().map(at.ioexception.mcaddon.convert.ConvertedPack::root).toList();
    }

    /** Runs after every activation, which is how the drawing side follows the active set. */
    public static void onActivate(Consumer<AddonSet> listener) {
        LISTENERS.add(listener);
    }
}
