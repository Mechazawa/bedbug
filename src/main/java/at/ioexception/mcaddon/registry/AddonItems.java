package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.ItemDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.convert.SpawnEggAppearance;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
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

        Item.Properties properties = new Item.Properties().stacksTo(definition.maxStackSize());
        properties = food(definition, properties);
        properties = wearable(definition, properties);

        for (String component : definition.components().keySet()) {
            if (!IMPLEMENTED.contains(component)) AddonEntities.unsupported(component);
        }

        Item item = Platform.buildItem(properties, id);
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

            SpawnEggAppearance appearance = appearanceOf(entry.getValue().identifier());
            Item egg = Platform.spawnEgg(entry.getKey(), id,
                appearance.baseColour(), appearance.overlayColour());
            if (egg == null) continue;
            Registry.register(BuiltInRegistries.ITEM, id, egg);
            BY_IDENTIFIER.put(entry.getValue().identifier() + "_spawn_egg", egg);
        }
    }

    /** How the pack that declared this entity wants its egg painted, or Bedrock's own colours. */
    private static SpawnEggAppearance appearanceOf(String identifier) {
        for (at.ioexception.mcaddon.convert.ConvertedPack pack : at.ioexception.mcaddon.resource.AddonPacks.all()) {
            SpawnEggAppearance appearance = pack.spawnEggs().get(identifier);
            if (appearance != null) return appearance;
        }
        return SpawnEggAppearance.DEFAULT;
    }

    /**
     * Eating, with the duration the pack asked for.
     *
     * `use_modifiers.use_duration` is in seconds and Bedrock's default is 1.6, the same as a Java
     * food item's, so a pack that leaves it out behaves the same in both.
     */
    private static Item.Properties food(ItemDefinition definition, Item.Properties properties) {
        JsonObject food = definition.components().get("minecraft:food");
        if (food == null) return properties;

        JsonObject modifiers = definition.components().get("minecraft:use_modifiers");
        float seconds = modifiers != null && modifiers.has("use_duration")
            ? modifiers.get("use_duration").getAsFloat() : 1.6f;

        return Platform.food(properties,
            food.has("nutrition") ? food.get("nutrition").getAsInt() : 0,
            food.has("saturation_modifier") ? food.get("saturation_modifier").getAsFloat() : 0.6f,
            food.has("can_always_eat") && food.get("can_always_eat").getAsBoolean(),
            // A pack that says a hat takes 9999 seconds to eat means it is never eaten, and Java
            // cannot express that, so the duration is kept within what a use can actually last.
            Math.min(seconds, 60f));
    }

    private static Item.Properties wearable(ItemDefinition definition, Item.Properties properties) {
        JsonObject wearable = definition.components().get("minecraft:wearable");
        if (wearable == null) return properties;
        if (!Platform.supportsWearable()) {
            AddonEntities.unsupported("minecraft:wearable");
            return properties;
        }
        return Platform.wearable(properties, slot(Json.text(wearable, "slot", "slot.armor.head")));
    }

    /** Bedrock names a slot `slot.armor.head`; Java names the same slot `HEAD`. */
    private static EquipmentSlot slot(String bedrock) {
        return switch (bedrock) {
            case "slot.armor.chest" -> EquipmentSlot.CHEST;
            case "slot.armor.legs" -> EquipmentSlot.LEGS;
            case "slot.armor.feet" -> EquipmentSlot.FEET;
            case "slot.weapon.offhand" -> EquipmentSlot.OFFHAND;
            default -> EquipmentSlot.HEAD;
        };
    }

    private static final List<String> IMPLEMENTED = List.of(
        "minecraft:icon", "minecraft:display_name", "minecraft:max_stack_size", "minecraft:tags",
        "minecraft:food", "minecraft:wearable", "minecraft:use_modifiers", "minecraft:use_animation",
        "minecraft:interact_button");

    private static Identifier identifier(String bedrock) {
        int colon = bedrock.indexOf(':');
        String namespace = Names.sanitise(colon > 0 ? bedrock.substring(0, colon) : "minecraft");
        String path = Names.sanitise(colon > 0 ? bedrock.substring(colon + 1) : bedrock);
        return namespace.isEmpty() || path.isEmpty() ? null : Platform.id(namespace, path);
    }
}
