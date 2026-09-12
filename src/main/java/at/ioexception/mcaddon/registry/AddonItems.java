package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.ItemDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An item per item a behaviour pack declares, and a spawn egg per entity that can be spawned.
 *
 * Only the components Java can express are applied. The rest are counted and reported, because a
 * pack whose sword does no damage should say so rather than look like it works.
 */
public final class AddonItems {
    private static final Map<String, Item> BY_IDENTIFIER = new LinkedHashMap<>();

    private AddonItems() {
    }

    public static Map<String, Item> all() {
        return BY_IDENTIFIER;
    }

    public static void register(PackSet packs) {
        List<ItemDefinition> definitions = new ArrayList<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() == PackRole.BEHAVIOUR) definitions.addAll(ItemDefinition.readAll(pack));
        }

        for (ItemDefinition definition : definitions) register(definition);
        registerSpawnEggs();

        if (!BY_IDENTIFIER.isEmpty()) {
            McAddon.LOGGER.info("Registered {} items from add-ons.", BY_IDENTIFIER.size());
        }
    }

    private static void register(ItemDefinition definition) {
        Identifier id = identifier(definition.identifier());
        if (id == null || BuiltInRegistries.ITEM.containsKey(id)) return;

        for (String component : definition.components().keySet()) {
            if (!IMPLEMENTED.contains(component)) AddonEntities.unsupported(component);
        }

        Item item = Platform.buildItem(new Item.Properties().stacksTo(definition.maxStackSize()), id);
        Registry.register(BuiltInRegistries.ITEM, id, item);
        BY_IDENTIFIER.put(definition.identifier(), item);
    }

    /**
     * A spawn egg is a `SpawnEggItem` and not a plain item carrying the component.
     *
     * The behaviour lives in the class: a plain item with the same component registers, shows an
     * icon and spawns nothing.
     */
    private static void registerSpawnEggs() {
        for (Map.Entry<EntityType<?>, ServerEntityDefinition> entry : AddonEntities.all().entrySet()) {
            if (!entry.getValue().spawnable()) continue;

            Identifier type = identifier(entry.getValue().identifier());
            if (type == null) continue;

            Identifier id = Platform.id(type.getNamespace(), type.getPath() + "_spawn_egg");
            if (BuiltInRegistries.ITEM.containsKey(id)) continue;

            Item egg = Platform.spawnEgg(entry.getKey(), id);
            if (egg == null) continue;
            Registry.register(BuiltInRegistries.ITEM, id, egg);
            BY_IDENTIFIER.put(entry.getValue().identifier() + "_spawn_egg", egg);
        }
    }

    private static final List<String> IMPLEMENTED = List.of(
        "minecraft:icon", "minecraft:display_name", "minecraft:max_stack_size", "minecraft:tags");

    private static Identifier identifier(String bedrock) {
        int colon = bedrock.indexOf(':');
        String namespace = Names.sanitise(colon > 0 ? bedrock.substring(0, colon) : "minecraft");
        String path = Names.sanitise(colon > 0 ? bedrock.substring(colon + 1) : bedrock);
        return namespace.isEmpty() || path.isEmpty() ? null : Platform.id(namespace, path);
    }
}
