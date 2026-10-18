package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.resource.AddonSet;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What this process has registered from add-ons. It only grows.
 *
 * Every writer skips an id its registry already holds and binds the definition of the set being
 * registered to the object already there, so registering a set twice, or a set that overlaps one
 * registered earlier, adds only what is new. Startup goes through here as well, so the path an
 * add-on takes into a registry is the same whether the registries are frozen or not.
 */
public final class AddonRegistry {
    /** The shape every object was built with, by its kind and identifier. */
    private static final Map<String, Shape> BUILT = new HashMap<>();

    private AddonRegistry() {
    }

    /**
     * What an object is built from that nothing can change once it exists: an entity type's
     * population and size, a block's whole definition and an item's whole set of components.
     *
     * Two definitions of one identifier with the same shape can share the object registered for
     * the first, and two with different shapes cannot.
     *
     * @param addOn the name of the add-on declaring it, which is what a player is told
     */
    public record Shape(String kind, String identifier, String addOn, String built) {
        String key() {
            return kind + " " + identifier;
        }
    }

    /** Remembers the shape of an object a writer has just built and registered. */
    static void built(Shape shape) {
        BUILT.putIfAbsent(shape.key(), shape);
    }

    /**
     * The add-on in `packs` declaring an object this process has already registered with another
     * shape, or nothing when every object it shares with what is registered can be bound to it.
     */
    public static Optional<String> conflict(PackSet packs) {
        return Stream.of(AddonEntities.shapes(packs), AddonBlocks.shapes(packs), AddonItems.shapes(packs))
            .flatMap(List::stream)
            .filter(shape -> BUILT.containsKey(shape.key()) && !BUILT.get(shape.key()).built().equals(shape.built()))
            .map(Shape::addOn)
            .findFirst();
    }

    /** Returns the items `set` declares, whether this call registered them or an earlier one did. */
    public static Set<Item> ensureRegistered(AddonSet set) {
        Set<EntityType<?>> types = new LinkedHashSet<>();
        Set<Item> items = new LinkedHashSet<>();
        RegistryEdits.adding(() -> {
            AddonSounds.register(set.converted());
            types.addAll(AddonEntities.register(set));
            // Blocks before items: an item that places a block names it, so the block has to be in
            // the registry by the time the item asking for it is built.
            List<Block> blocks = AddonBlocks.register(set.packs());
            blocks.stream().map(Block::asItem).filter(item -> item != Items.AIR).forEach(items::add);
            items.addAll(AddonItems.register(set, types));
            AddonParticles.register(set.converted());
        });
        return items;
    }
}
