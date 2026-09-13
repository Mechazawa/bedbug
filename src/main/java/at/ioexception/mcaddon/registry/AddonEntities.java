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
    private static final Map<EntityType<?>, String> NAMESPACES = new LinkedHashMap<>();

    private AddonEntities() {
    }

    /**
     * The loot table id a pack-relative table path names, for an entity of this type.
     *
     * `minecraft:loot` names the table by the path the file sits at inside the behaviour pack, and
     * the conversion files every table under the namespace the add-on's own content uses, so the id
     * is those two put together. Null when the type came from no converted pack.
     */
    public static Identifier lootTable(EntityType<?> type, String table) {
        String namespace = NAMESPACES.get(type);
        return namespace == null ? null
            : Platform.id(namespace, at.ioexception.mcaddon.bedrock.behaviour.LootTableDefinition
                .javaPath(table));
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
        namespaceOf(pack).ifPresent(namespace -> NAMESPACES.put(type, namespace));
        readTrades(pack, definition).ifPresent(trades -> TRADES.put(type, trades));
        Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type);
        FabricDefaultAttributeRegistry.register(type, AddonEntity.attributes(definition));
    }

    /** Whether this mod does anything about a component, wherever the component was met. */
    public static boolean implemented(String component) {
        return IMPLEMENTED.contains(component);
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
     *
     * That is the only reason a name belongs here without a reader of its own. A component this mod
     * neither acts on nor gets for free stays out, so `/mcaddon entities` counts it.
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
        "minecraft:nameable", "minecraft:persistent", "minecraft:jump.static",
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
        "minecraft:movement.fly", "minecraft:movement.hover", "minecraft:movement.glide",
        "minecraft:movement.amphibious",
        "minecraft:preferred_path", "minecraft:leashable", "minecraft:angry",
        "minecraft:pushable_by_entity", "minecraft:pushable_by_block", "minecraft:rideable",
        "minecraft:teleport", "minecraft:trade_resupply", "minecraft:behavior.trade_with_player",
        "minecraft:behavior.trade_interest", "minecraft:behavior.pickup_items",
        "minecraft:shareables",
        "minecraft:home", "minecraft:dweller", "minecraft:target_nearby_sensor",
        "minecraft:color", "minecraft:color2", "minecraft:breedable", "minecraft:offspring",
        "minecraft:leashable_to", "minecraft:spawn_egg_interaction",
        "minecraft:behavior.breed", "minecraft:behavior.sleep",
        "minecraft:behavior.drink_milk", "minecraft:behavior.drink_potion",
        "minecraft:dimension_bound",
        "minecraft:behavior.play", "minecraft:balloonable", "minecraft:managed_wandering_trader",
        "minecraft:behavior.take_flower", "minecraft:behavior.celebrate_survive",
        "minecraft:behavior.look_at_entity", "minecraft:behavior.random_look_around_and_sit",
        "minecraft:behavior.go_home", "minecraft:behavior.move_to_village",
        "minecraft:behavior.move_through_village", "minecraft:behavior.mingle",
        "minecraft:behavior.move_towards_target", "minecraft:behavior.random_fly",
        "minecraft:behavior.random_hover", "minecraft:behavior.hover",
        "minecraft:behavior.float_wander", "minecraft:behavior.random_swim",
        "minecraft:behavior.swim_wander", "minecraft:behavior.move_to_water",
        "minecraft:behavior.move_to_liquid", "minecraft:behavior.swim_up_for_breath",
        "minecraft:behavior.eat_block", "minecraft:behavior.flee_sun",
        "minecraft:behavior.find_cover", "minecraft:behavior.restrict_sun",
        "minecraft:behavior.break_door", "minecraft:annotation.break_door",
        "minecraft:behavior.follow_mob", "minecraft:behavior.leap_at_target",
        "minecraft:behavior.stalk_and_pounce_on_target", "minecraft:behavior.delayed_attack",
        "minecraft:behavior.stomp_attack", "minecraft:behavior.charge_attack",
        "minecraft:behavior.nearest_prioritized_attackable_target",
        "minecraft:behavior.target_when_pushed", "minecraft:behavior.float_tempt",
        "minecraft:attack", "minecraft:attack_damage",
        "minecraft:burns_in_daylight", "minecraft:damage_over_time",
        "minecraft:ambient_sound_interval", "minecraft:sound_volume",
        "minecraft:cannot_be_attacked", "minecraft:is_collidable",
        "minecraft:loot", "minecraft:ageable", "minecraft:scale_by_age", "minecraft:healable",
        "minecraft:tameable", "minecraft:tamemount", "minecraft:boss", "minecraft:break_blocks",
        "minecraft:mob_effect", "minecraft:area_attack", "minecraft:inventory", "minecraft:shooter",
        "minecraft:explode", "minecraft:spawn_on_death", "minecraft:on_death", "minecraft:on_hurt",
        "minecraft:on_target_acquired", "minecraft:on_target_escape",
        "minecraft:combat_regeneration", "minecraft:trail", "minecraft:grows_crop",
        "minecraft:looked_at", "minecraft:can_fly", "minecraft:block_climber",
        "minecraft:buoyant", "minecraft:floats_in_liquid", "minecraft:bounciness",
        "minecraft:body_rotation_blocked", "minecraft:body_rotation_always_follows_head",
        "minecraft:default_look_angle", "minecraft:drying_out_timer",
        "minecraft:equippable", "minecraft:giveable", "minecraft:sittable", "minecraft:trusting",
        "minecraft:underwater_movement", "minecraft:water_movement", "minecraft:lava_movement",
        "minecraft:addrider", "minecraft:on_ignite", "minecraft:on_hurt_by_player",
        "minecraft:on_start_takeoff", "minecraft:on_start_landing",
        "minecraft:rotation_locked_to_vehicle", "minecraft:air_drag_modifier",
        "minecraft:behavior.ranged_attack", "minecraft:behavior.ocelotattack",
        "minecraft:behavior.follow_parent", "minecraft:behavior.follow_caravan",
        "minecraft:behavior.follow_target_leader", "minecraft:behavior.move_to_block",
        "minecraft:behavior.move_to_land", "minecraft:behavior.move_to_lava",
        "minecraft:behavior.move_to_random_block", "minecraft:behavior.equip_item",
        "minecraft:behavior.hold_ground", "minecraft:behavior.look_at_target",
        "minecraft:behavior.door_interact", "minecraft:behavior.raid_garden",
        "minecraft:behavior.harvest_farm_block", "minecraft:behavior.fertilize_farm_block",
        "minecraft:behavior.stomp_turtle_egg", "minecraft:behavior.timer_flag_1",
        "minecraft:behavior.timer_flag_2", "minecraft:behavior.timer_flag_3",
        "minecraft:behavior.random_sitting", "minecraft:behavior.lay_down",
        "minecraft:behavior.knockback_roar", "minecraft:behavior.eat_carried_item",
        "minecraft:behavior.move_around_target", "minecraft:behavior.jump_around_target",
        "minecraft:behavior.owner_hurt_target", "minecraft:behavior.owner_hurt_by_target",
        "minecraft:behavior.defend_trusted_target", "minecraft:behavior.teleport_to_owner",
        "minecraft:behavior.go_and_give_items_to_owner", "minecraft:behavior.pet_sleep_with_owner",
        "minecraft:behavior.stay_while_sitting", "minecraft:behavior.find_mount",
        "minecraft:on_wake_with_owner", "minecraft:anger_level",
        "minecraft:apply_knockback_rules", "minecraft:attack_cooldown",
        "minecraft:entity_armor_equipment_slot_mapping", "minecraft:free_camera_controlled",
        "minecraft:group_size", "minecraft:ignore_cannot_be_attacked",
        "minecraft:input_ground_controlled", "minecraft:item_controllable",
        "minecraft:item_hopper", "minecraft:not_pickable_from_inside",
        "minecraft:on_equipment_changed", "minecraft:on_friendly_anger",
        "minecraft:reflect_projectiles", "minecraft:tick_world",
        "minecraft:underwater_mount_breathing", "minecraft:vertical_movement_action",
        "minecraft:behavior.roar", "minecraft:behavior.croak", "minecraft:behavior.sniff",
        "minecraft:behavior.play_dead", "minecraft:behavior.scared", "minecraft:behavior.celebrate",
        "minecraft:behavior.sneeze", "minecraft:behavior.roll", "minecraft:behavior.swim_idle",
        "minecraft:behavior.restrict_open_door", "minecraft:behavior.rise_to_liquid_level",
        "minecraft:behavior.move_to_poi", "minecraft:behavior.jump_to_block",
        "minecraft:behavior.lay_egg", "minecraft:behavior.eat_mob",
        "minecraft:behavior.fire_at_target", "minecraft:behavior.charge_held_item",
        "minecraft:behavior.circle_around_anchor", "minecraft:behavior.controlled_by_player",
        "minecraft:behavior.take_block", "minecraft:behavior.place_block",
        "minecraft:behavior.enderman_take_block", "minecraft:behavior.enderman_leave_block",
        "minecraft:behavior.ram_attack", "minecraft:behavior.swoop_attack",
        "minecraft:behavior.aquatic_charge_attack", "minecraft:behavior.send_event",
        "minecraft:behavior.summon_entity",
        "minecraft:is_charged", "minecraft:is_chested", "minecraft:is_saddled",
        "minecraft:is_ignited", "minecraft:is_illager_captain", "minecraft:is_pregnant",
        "minecraft:is_shaking", "minecraft:is_stackable", "minecraft:is_stunned",
        "minecraft:is_tamed", "minecraft:absorption", "minecraft:transient",
        "minecraft:mob_effect_immunity",
        "minecraft:behavior.slime_random_direction", "minecraft:behavior.slime_keep_on_jumping",
        "minecraft:behavior.slime_float", "minecraft:behavior.slime_attack",
        "minecraft:flying_speed", "minecraft:luck", "minecraft:horse.jump_strength",
        "minecraft:variable_max_auto_step", "minecraft:strength", "minecraft:equipment",
        "minecraft:equip_item",
        "minecraft:behavior.admire_item", "minecraft:behavior.barter",
        "minecraft:admire_item", "minecraft:barter",
        "minecraft:behavior.work", "minecraft:behavior.work_composter",
        "minecraft:behavior.share_items", "minecraft:behavior.transport_items",
        "minecraft:behavior.drop_item_for", "minecraft:behavior.offer_flower",
        "minecraft:behavior.inspect_bookshelf", "minecraft:behavior.make_love",
        "minecraft:behavior.receive_love", "minecraft:behavior.nap",
        "minecraft:behavior.snacking",
        "minecraft:behavior.emerge", "minecraft:behavior.dig",
        "minecraft:behavior.random_search_and_dig", "minecraft:behavior.random_breach",
        "minecraft:behavior.find_underwater_treasure", "minecraft:behavior.swim_with_entity",
        "minecraft:movement.dolphin", "minecraft:movement.sway", "minecraft:movement.jump",
        "minecraft:movement.skip", "minecraft:heartbeat", "minecraft:ground_offset",
        "minecraft:inside_block_notifier", "minecraft:block_sensor", "minecraft:celebrate_hunt");

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

    /**
     * The namespace the add-on this behaviour pack came out of writes its own data under.
     *
     * A behaviour pack and the resource pack beside it are one add-on and converted as one, so the
     * namespace is looked up by the file both halves were unpacked from.
     */
    private static java.util.Optional<String> namespaceOf(BedrockPack pack) {
        return at.ioexception.mcaddon.resource.AddonPacks.all().stream()
            .filter(converted -> converted.pack().origin().equals(pack.origin()))
            .map(at.ioexception.mcaddon.convert.ConvertedPack::namespace)
            .findFirst();
    }

    /** Bedrock identifiers carry characters a resource location rejects, so both halves are cleaned. */
    private static Identifier identifier(String bedrock) {
        Names.Identifier split = Names.split(bedrock, "minecraft");
        return split.namespace().isEmpty() || split.path().isEmpty() ? null
            : Platform.id(split.namespace(), split.path());
    }
}
