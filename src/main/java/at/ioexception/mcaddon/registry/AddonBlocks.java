package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.ClientBlockDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.block.AddonBlock;
import at.ioexception.mcaddon.block.AddonWaterloggedBlock;
import at.ioexception.mcaddon.block.BlockComponents;
import at.ioexception.mcaddon.block.PackShapes;
import at.ioexception.mcaddon.block.Permutations;
import at.ioexception.mcaddon.convert.BedrockBlockNames;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A block per block a behaviour pack declares, with an item so it can be placed.
 *
 * A pack's states, and the ones its placement traits add, become Java properties. A permutation's
 * shapes, hardness and light are answered per state, its textures and turn are drawn per state, and
 * any other component it names is fixed with the block and reported.
 */
public final class AddonBlocks {
    private static final Map<String, Block> BY_IDENTIFIER = new LinkedHashMap<>();

    /**
     * The answers of the two `identifierOf`, which a filter or a sensor asks every tick and which
     * never change once a name has a block. A name no block answers yet is asked again, because a
     * pack's own blocks are registered after the first packs are read.
     */
    private static final Map<BlockState, String> REPORTED = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> NAMED = new java.util.concurrent.ConcurrentHashMap<>();

    private AddonBlocks() {
    }

    public static Map<String, Block> all() {
        return BY_IDENTIFIER;
    }

    /** The block a Bedrock name refers to, a pack's own or the game's, under either edition's spelling. */
    public static Block named(String bedrock) {
        Block own = BY_IDENTIFIER.get(bedrock);
        if (own != null) return own;

        String java = BedrockBlockNames.java(bedrock);
        Identifier id = java == null ? null : Identifier.tryParse(java);
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    /**
     * The block state a Bedrock name and its states stand for, a pack's own block or the game's, or
     * null where no block answers. A state no property answers is reported through `report`.
     */
    public static BlockState stateOf(String bedrock, Map<String, ?> states, Consumer<String> report) {
        BedrockBlockNames.Resolved resolved = BedrockBlockNames.state(bedrock, states, report);
        return resolved == null ? null : resolved.state();
    }

    /**
     * The identifier a pack knows this block by, which `named` reads back: Bedrock's spelling for
     * the state the game holds it in, and the registered one for a pack's own.
     */
    public static String identifierOf(BlockState state) {
        return REPORTED.computeIfAbsent(state, BedrockBlockNames::bedrock);
    }

    /**
     * The identifier a block a pack names is reported under, whichever edition's spelling it wrote,
     * so that two names for one block compare equal. A name no block answers comes back as written.
     */
    public static String identifierOf(String bedrock) {
        String known = NAMED.get(bedrock);
        if (known != null) return known;

        BlockState state = stateOf(bedrock, Map.of(), ignored -> { });
        if (state == null) return bedrock;

        String identifier = identifierOf(state);
        NAMED.put(bedrock, identifier);
        return identifier;
    }

    /**
     * A block, the named shapes of the pack it came from, which two packs may spell alike, and how
     * the resource packs say it looks and sounds.
     */
    private record Declared(BedrockPack pack, BlockDefinition definition, PackShapes shapes,
        ClientBlockDefinition appearance) {
        AddonRegistry.Shape shape() {
            java.util.SortedMap<String, String> named = new java.util.TreeMap<>();
            shapes.byIdentifier().forEach((identifier, shape) -> named.put(identifier, shape.toAabbs().toString()));
            return new AddonRegistry.Shape("block", definition.identifier(), pack.displayName(),
                definition + " " + appearance + " " + named);
        }
    }

    /** The shape each block these packs declare is built with. */
    static List<AddonRegistry.Shape> shapes(PackSet packs) {
        return declared(packs).stream().map(Declared::shape).toList();
    }

    /** Every block definition in these packs, the first of two with one identifier deciding it. */
    private static List<Declared> declared(PackSet packs) {
        Map<String, ClientBlockDefinition> appearance = new LinkedHashMap<>();
        packs.withRole(PackRole.RESOURCE).forEach(pack -> appearance.putAll(ClientBlockDefinition.readAll(pack)));
        Map<String, Declared> declared = new LinkedHashMap<>();
        for (BedrockPack pack : packs.withRole(PackRole.BEHAVIOUR)) {
            PackShapes shapes = PackShapes.read(pack);
            BlockDefinition.readAll(pack).forEach(block -> declared.putIfAbsent(block.identifier(),
                new Declared(pack, block, shapes, ClientBlockDefinition.of(appearance, block.identifier()))));
        }
        return List.copyOf(declared.values());
    }

    /**
     * Registers a block, and an item to place it with, per definition these packs carry that is not
     * registered yet. Returns the blocks the packs declare, registered now or earlier.
     *
     * A block's states, shapes, hardness and light are fixed when it is built, so a block registered
     * earlier keeps the definition it was built from.
     */
    public static List<Block> register(PackSet packs) {
        List<Block> declared = declared(packs).stream()
            .map(AddonBlocks::register)
            .filter(java.util.Objects::nonNull)
            .toList();
        if (!declared.isEmpty()) McAddon.LOGGER.info("Registered {} blocks from add-ons.", declared.size());
        return declared;
    }

    /** The block a definition stands for, or null where its identifier is unusable or not a pack's. */
    private static Block register(Declared declared) {
        BlockDefinition definition = declared.definition();
        Block existing = BY_IDENTIFIER.get(definition.identifier());
        if (existing != null) return existing;
        Identifier id = identifier(definition.identifier());
        if (id == null || BuiltInRegistries.BLOCK.containsKey(id)) return null;

        for (String component : definition.components().keySet()) {
            if (!IMPLEMENTED.contains(component)
                && !at.ioexception.mcaddon.bedrock.behaviour.CustomComponents.isCustom(component)) {
                AddonEntities.unsupported(component);
            }
        }
        if (definition.components().has("minecraft:crafting_table")) {
            // Java has one crafting grid and it makes every recipe, so the tags choosing which
            // recipes a Bedrock table may make have nothing to become.
            AddonEntities.unsupported("minecraft:crafting_table crafting_tags");
        }
        for (String part : BlockComponents.unmappedTransformation(definition)) {
            AddonEntities.unsupported("minecraft:transformation " + part);
        }
        for (String component : Permutations.fixed(definition)) {
            AddonEntities.unsupported(component + " in a permutation");
        }

        Map<String, Property<?>> states = AddonBlock.propertiesOf(definition);
        BlockComponents components = BlockComponents.of(definition, states, declared.shapes());
        BlockBehaviour.Properties settings = components.settings(definition, declared.appearance());
        List<Property<?>> properties = new ArrayList<>(states.values());

        Block block = AddonBlock.carrying(components, () -> BlockComponents.waterlogged(definition)
            ? AddonWaterloggedBlock.build(settings, id, properties)
            : Platform.buildStatefulBlock(settings, id, properties));
        Registry.register(BuiltInRegistries.BLOCK, id, block);
        BY_IDENTIFIER.put(definition.identifier(), block);
        AddonRegistry.built(declared.shape());
        components.registerFlammability(block);

        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            Item item = Platform.buildBlockItem(block, Platform.blockItemProperties(), id);
            Registry.register(BuiltInRegistries.ITEM, id, item);
            AddonItems.all().put(definition.identifier(), item);
        }
        if (definition.components().has("minecraft:flower_pottable")) registerPotted(id, block);
        return block;
    }

    /**
     * A second block for the block sitting in a flower pot, which is how Java draws a potted plant.
     *
     * Java's empty pot only accepts what a `FlowerPotBlock` was built around, and building one is
     * what puts this block on that list.
     */
    private static void registerPotted(Identifier id, Block block) {
        Identifier potted = Platform.id(id.getNamespace(), "potted_" + id.getPath());
        if (BuiltInRegistries.BLOCK.containsKey(potted)) return;

        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
            .instabreak()
            .noOcclusion()
            .pushReaction(Platform.POPPED);
        //? if >=1.21.2 {
        properties = properties.setId(ResourceKey.create(Registries.BLOCK, potted));
        //?}
        Registry.register(BuiltInRegistries.BLOCK, potted, new FlowerPotBlock(block, properties));
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
        "minecraft:instrument_sound", "minecraft:sound", "minecraft:random_offset",
        "minecraft:tick", "minecraft:queued_ticking", "minecraft:random_ticking",
        "minecraft:on_placed", "minecraft:on_player_placing", "minecraft:on_player_destroyed",
        "minecraft:on_interact", "minecraft:on_step_on", "minecraft:on_step_off",
        "minecraft:on_fall_on", "minecraft:entity_fall_on",
        "minecraft:placement_filter", "minecraft:redstone_producer", "minecraft:redstone_consumer",
        "minecraft:liquid_detection", "minecraft:destruction_particles",
        "minecraft:flower_pottable", "minecraft:unit_cube", "minecraft:item_visual",
        "minecraft:leashable", "minecraft:custom_components",
        "minecraft:breathability", "minecraft:embedded_visual", "minecraft:block_entity",
        "minecraft:chest_obstruction", "minecraft:support", "minecraft:connection_rule",
        "minecraft:precipitation_interactions");

    private static Identifier identifier(String bedrock) {
        Names.Identifier split = Names.split(bedrock, "minecraft");
        return split.namespace().isEmpty() || split.path().isEmpty() ? null
            : Platform.id(split.namespace(), split.path());
    }
}
