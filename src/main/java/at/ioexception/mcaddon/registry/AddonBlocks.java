package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.block.AddonBlock;
import at.ioexception.mcaddon.block.BlockComponents;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A block per block a behaviour pack declares, with an item so it can be placed.
 *
 * Bedrock's states and permutations are not mapped: a Java block's states have to exist before the
 * registries freeze and a pack's permutation conditions are Molang over them. A pack that uses them
 * gets its default look and is told so.
 */
public final class AddonBlocks {
    private static final Map<String, Block> BY_IDENTIFIER = new LinkedHashMap<>();

    private AddonBlocks() {
    }

    public static Map<String, Block> all() {
        return BY_IDENTIFIER;
    }

    public static void register(PackSet packs) {
        List<BlockDefinition> definitions = new ArrayList<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() == PackRole.BEHAVIOUR) definitions.addAll(BlockDefinition.readAll(pack));
        }

        for (BlockDefinition definition : definitions) register(definition);
        if (!BY_IDENTIFIER.isEmpty()) {
            McAddon.LOGGER.info("Registered {} blocks from add-ons.", BY_IDENTIFIER.size());
        }
    }

    private static void register(BlockDefinition definition) {
        Identifier id = identifier(definition.identifier());
        if (id == null || BuiltInRegistries.BLOCK.containsKey(id)) return;

        for (String component : definition.components().keySet()) {
            if (!IMPLEMENTED.contains(component)) AddonEntities.unsupported(component);
        }
        if (definition.components().has("minecraft:crafting_table")) {
            // Java has one crafting grid and it makes every recipe, so the tags choosing which
            // recipes a Bedrock table may make have nothing to become.
            AddonEntities.unsupported("minecraft:crafting_table crafting_tags");
        }
        for (String part : BlockComponents.unmappedTransformation(definition)) {
            AddonEntities.unsupported("minecraft:transformation " + part);
        }

        BlockComponents components = BlockComponents.of(definition);
        BlockBehaviour.Properties properties = BlockComponents.settings(definition);
        Map<String, Property<?>> states = AddonBlock.propertiesOf(definition);

        Block block = AddonBlock.carrying(components,
            () -> Platform.buildStatefulBlock(properties, id, new ArrayList<>(states.values())));
        Registry.register(BuiltInRegistries.BLOCK, id, block);
        BY_IDENTIFIER.put(definition.identifier(), block);
        components.registerFlammability(block);

        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            Item item = Platform.buildBlockItem(block, new Item.Properties(), id);
            Registry.register(BuiltInRegistries.ITEM, id, item);
            AddonItems.all().put(definition.identifier(), item);
        }
    }

    /**
     * Everything a block can declare that this mod does something about.
     *
     * A component read only to shape another one belongs here too: `material_instances` decides both
     * the faces a model wears and whether the block occludes. Nothing is listed on the grounds that
     * Java does it anyway, so every name here has a reader that can be pointed at.
     */
    private static final List<String> IMPLEMENTED = List.of(
        "minecraft:material_instances", "minecraft:geometry",
        "minecraft:destructible_by_mining", "minecraft:destructible_by_explosion",
        "minecraft:light_emission", "minecraft:light_dampening", "minecraft:friction",
        "minecraft:map_color", "minecraft:flammable", "minecraft:replaceable",
        "minecraft:collision_box", "minecraft:selection_box", "minecraft:movable",
        "minecraft:redstone_conductivity", "minecraft:crafting_table", "minecraft:display_name",
        "minecraft:loot", "minecraft:tags", "minecraft:transformation",
        "minecraft:instrument_sound", "minecraft:random_offset");

    private static Identifier identifier(String bedrock) {
        Names.Identifier split = Names.split(bedrock, "minecraft");
        return split.namespace().isEmpty() || split.path().isEmpty() ? null
            : Platform.id(split.namespace(), split.path());
    }
}
