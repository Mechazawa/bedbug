package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.resource.AddonSet;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What this process has registered from add-ons. It only grows.
 *
 * Every writer skips an id its registry already holds and binds the definition of the set being
 * registered to the object already there, so registering a set twice, or a set that overlaps one
 * registered earlier, adds only what is new. Startup goes through here as well, so the path an
 * add-on takes into a registry is the same whether the registries are frozen or not.
 */
public final class AddonRegistry {
    private AddonRegistry() {
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
