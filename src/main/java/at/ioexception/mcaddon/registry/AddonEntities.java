package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import com.google.gson.JsonObject;
import at.ioexception.mcaddon.bedrock.behaviour.TradeTable;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An entity type per entity a behaviour pack declares.
 *
 * Registration happens during mod initialisation, before the registries freeze, which is why the
 * packs are read that early. A pack that names an entity Java cannot represent is reported here
 * rather than dropped without a word.
 */
public final class AddonEntities {
    private static final Map<EntityType<?>, ServerEntityDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<String, EntityType<?>> BY_IDENTIFIER = new LinkedHashMap<>();
    private static final Map<String, AtomicInteger> UNSUPPORTED = new TreeMap<>();
    private static final Map<EntityType<?>, TradeTable> TRADES = new LinkedHashMap<>();

    private AddonEntities() {
    }

    public static ServerEntityDefinition definitionOf(EntityType<?> type) {
        return DEFINITIONS.get(type);
    }

    /** Null when the entity does not trade. */
    public static TradeTable tradesOf(EntityType<?> type) {
        return TRADES.get(type);
    }

    /**
     * The entity type a Bedrock identifier names, whoever owns it.
     *
     * An add-on's own types are checked first, then the game's, under whichever name the two
     * editions give it: a pack writing `minecraft:zombie_pigman` means Java's `zombified_piglin`,
     * and it means that wherever the pack writes it.
     */
    public static EntityType<?> typeOf(String identifier) {
        EntityType<?> own = BY_IDENTIFIER.get(identifier);
        if (own != null) return own;

        String java = at.ioexception.mcaddon.convert.BedrockEntityNames.java(identifier);
        Identifier id = java == null ? null : Identifier.tryParse(java);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    public static Map<EntityType<?>, ServerEntityDefinition> all() {
        return DEFINITIONS;
    }

    /** Counted rather than logged one by one: a pack uses the same component on every entity it has. */
    public static void unsupported(String component) {
        UNSUPPORTED.computeIfAbsent(component, name -> new AtomicInteger()).incrementAndGet();
    }

    public static List<String> report() {
        List<String> lines = new ArrayList<>();
        UNSUPPORTED.forEach((component, uses) -> lines.add("    " + component + " x" + uses.get()));
        if (!lines.isEmpty()) lines.add(0, "Entity components the packs used and this mod does not implement:");
        return lines;
    }

    public static void register(PackSet packs) {
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;
            for (ServerEntityDefinition definition : ServerEntityDefinition.readAll(pack)) {
                register(pack, definition);
            }
        }
        if (!DEFINITIONS.isEmpty()) {
            McAddon.LOGGER.info("Registered {} entity types from add-ons.", DEFINITIONS.size());
            report().forEach(McAddon.LOGGER::info);
        }
    }

    private static void register(BedrockPack pack, ServerEntityDefinition definition) {
        Identifier id = identifier(definition.identifier());
        if (id == null) {
            McAddon.LOGGER.warn("{} declares {}, which is not a usable identifier.",
                pack.displayName(), definition.identifier());
            return;
        }
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            // A pack that changes a vanilla entity rather than adding one: the type already exists.
            return;
        }

        EntityType<AddonEntity> type = Platform.buildEntityType(
            EntityType.Builder.of(AddonEntity::new, MobCategory.CREATURE)
                .sized(definition.width() * definition.scale(), definition.height() * definition.scale()),
            id);

        count(definition);
        DEFINITIONS.put(type, definition);
        BY_IDENTIFIER.put(definition.identifier(), type);
        readTrades(pack, definition).ifPresent(trades -> TRADES.put(type, trades));
        Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type);
        FabricDefaultAttributeRegistry.register(type, AddonEntity.attributes(definition));
    }

    /**
     * Counts the components an entity declares that nothing here acts on.
     *
     * Every other reader pulls the components it knows by name, so a component nobody asked for is
     * simply never seen. Walking the whole set once is what turns a silent gap into a reported one,
     * which is the difference between `/mcaddon entities` being evidence and being a guess. A
     * component group counts too: a pack switches those on at runtime and its contents are as real
     * as the ones declared at the top.
     */
    private static void count(ServerEntityDefinition definition) {
        for (String component : definition.components().keySet()) {
            if (!IMPLEMENTED.contains(component)) unsupported(component);
        }
        for (JsonObject group : definition.componentGroups().values()) {
            for (String component : group.keySet()) {
                if (!IMPLEMENTED.contains(component)) unsupported(component);
            }
        }
    }

    /**
     * Everything an entity can declare that this mod does something about.
     *
     * A component naming something the game already does for every mob is in here too: a pack saying
     * an entity is pushable or has physics is describing what a Java mob does anyway, so honouring it
     * is doing nothing, and reporting it would be noise rather than a gap.
     */
    private static final Set<String> IMPLEMENTED = Set.of(
        "minecraft:health", "minecraft:scale", "minecraft:collision_box", "minecraft:movement",
        "minecraft:variant", "minecraft:mark_variant", "minecraft:skin_id", "minecraft:is_baby",
        "minecraft:is_sheared", "minecraft:is_dyeable", "minecraft:type_family",
        "minecraft:trade_table", "minecraft:economy_trade_table", "minecraft:interact",
        "minecraft:transformation", "minecraft:instant_despawn", "minecraft:damage_sensor",
        "minecraft:environment_sensor", "minecraft:entity_sensor", "minecraft:timer",
        "minecraft:scheduler", "minecraft:hurt_on_condition", "minecraft:spawn_entity",
        "minecraft:physics", "minecraft:pushable", "minecraft:push_through", "minecraft:despawn",
        "minecraft:nameable", "minecraft:persistent", "minecraft:loot", "minecraft:jump.static",
        "minecraft:can_climb", "minecraft:breathable", "minecraft:conditional_bandwidth_optimization",
        "minecraft:entity_spawned", "minecraft:follow_range", "minecraft:knockback_resistance",
        "minecraft:fire_immune", "minecraft:is_hidden_when_invisible", "minecraft:experience_reward",
        "minecraft:behavior.float", "minecraft:behavior.panic", "minecraft:behavior.random_stroll",
        "minecraft:behavior.move_outdoors", "minecraft:behavior.explore_outskirts",
        "minecraft:behavior.look_at_player", "minecraft:behavior.look_at_trading_player",
        "minecraft:behavior.random_look_around", "minecraft:behavior.move_indoors",
        "minecraft:behavior.move_towards_dwelling_restriction", "minecraft:behavior.tempt",
        "minecraft:behavior.move_towards_home_restriction",
        "minecraft:behavior.move_towards_restriction", "minecraft:behavior.mount_pathing",
        "minecraft:behavior.follow_owner", "minecraft:behavior.stroll_towards_village",
        "minecraft:behavior.avoid_mob_type", "minecraft:behavior.hide",
        "minecraft:behavior.avoid_block", "minecraft:behavior.panic_on_fire",
        "minecraft:behavior.open_door", "minecraft:annotation.open_door",
        "minecraft:behavior.melee_attack", "minecraft:behavior.melee_box_attack",
        "minecraft:behavior.nearest_attackable_target", "minecraft:behavior.hurt_by_target",
        "minecraft:navigation.walk", "minecraft:navigation.generic", "minecraft:navigation.swim",
        "minecraft:navigation.float", "minecraft:navigation.fly", "minecraft:navigation.climb",
        "minecraft:navigation.hover", "minecraft:movement.basic", "minecraft:movement.generic",
        "minecraft:movement.fly", "minecraft:movement.hover", "minecraft:movement.jump",
        "minecraft:movement.skip", "minecraft:movement.sway", "minecraft:movement.amphibious",
        "minecraft:preferred_path", "minecraft:leashable", "minecraft:angry",
        "minecraft:pushable_by_entity", "minecraft:pushable_by_block", "minecraft:rideable",
        "minecraft:teleport", "minecraft:trade_resupply", "minecraft:behavior.trade_with_player",
        "minecraft:behavior.trade_interest", "minecraft:behavior.pickup_items",
        "minecraft:shareables", "minecraft:inventory", "minecraft:equipment",
        "minecraft:home", "minecraft:dweller", "minecraft:target_nearby_sensor",
        "minecraft:color", "minecraft:color2", "minecraft:breedable", "minecraft:offspring",
        "minecraft:leashable_to", "minecraft:spawn_egg_interaction",
        "minecraft:behavior.breed", "minecraft:behavior.follow_parent", "minecraft:behavior.sleep",
        "minecraft:behavior.drink_milk", "minecraft:behavior.drink_potion",
        "minecraft:behavior.share_items", "minecraft:dimension_bound", "minecraft:hide",
        "minecraft:behavior.play");

    /**
     * The trade table an entity names, read while the pack it came from is still in hand.
     *
     * Bedrock has two components for this and they differ only in whether the trades cost emeralds,
     * which Java's merchant screen does not distinguish.
     */
    private static java.util.Optional<TradeTable> readTrades(BedrockPack pack,
        ServerEntityDefinition definition) {
        for (String component : List.of("minecraft:trade_table", "minecraft:economy_trade_table")) {
            com.google.gson.JsonObject body = definition.components().get(component);
            String table = body == null ? null : at.ioexception.mcaddon.util.Json.text(body, "table", null);
            if (table != null) return java.util.Optional.of(TradeTable.read(pack, table));
        }
        return java.util.Optional.empty();
    }

    /** Bedrock identifiers carry characters a resource location rejects, so both halves are cleaned. */
    private static Identifier identifier(String bedrock) {
        Names.Identifier split = Names.split(bedrock, "minecraft");
        return split.namespace().isEmpty() || split.path().isEmpty() ? null
            : Platform.id(split.namespace(), split.path());
    }
}
