package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.ItemDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.convert.SpawnEggAppearance;
import at.ioexception.mcaddon.item.AddonScriptedItem;
import at.ioexception.mcaddon.item.DurabilitySensor;
import at.ioexception.mcaddon.item.AddonShooterItem;
import at.ioexception.mcaddon.item.AddonThrowableItem;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An item per item a behaviour pack declares, and a spawn egg per entity that can be spawned.
 *
 * Only the components Java can express are applied. The rest are counted and reported, because a
 * pack whose sword does no damage should say so rather than look like it works.
 */
public final class AddonItems {
    private static final Map<String, Item> BY_IDENTIFIER = new LinkedHashMap<>();
    private static final List<Item> DYEABLE = new ArrayList<>();
    private static final Set<Item> KEPT = new HashSet<>();
    private static final Map<Item, String> PROJECTILES = new LinkedHashMap<>();
    private static final Map<Item, Set<String>> ABSORBED = new LinkedHashMap<>();
    private static final Map<Item, Set<String>> COMPONENTS = new LinkedHashMap<>();
    private static final Map<Item, KineticTimings> KINETIC = new LinkedHashMap<>();
    private static final Map<Item, Float> COOLDOWNS = new LinkedHashMap<>();
    private static final Map<Item, DurabilitySensor> SENSORS = new LinkedHashMap<>();
    private static final Set<Item> OFF_HAND_REFUSED = new HashSet<>();
    private static final Set<Item> LIQUID_CLIPPED = new HashSet<>();

    private AddonItems() {
    }

    /** The durations `minecraft:kinetic_weapon` states, in ticks, which a pack reads back by name. */
    public record KineticTimings(int delay, int damage, int knockback, int dismount) {
    }

    /** The kinetic weapon timings of a pack's own item, or null for anything else. */
    public static KineticTimings kineticOf(Item item) {
        return KINETIC.get(item);
    }

    /** How long `minecraft:cooldown` keeps a pack's own item busy, in seconds, or nothing. */
    public static float cooldownOf(Item item) {
        return COOLDOWNS.getOrDefault(item, 0f);
    }

    public static Map<String, Item> all() {
        return BY_IDENTIFIER;
    }

    /** The items a pack declared dyeable, which the drawing side has to paint their dyed colour. */
    public static List<Item> dyeable() {
        return DYEABLE;
    }

    /** The item a Bedrock name refers to, a pack's own or the game's. */
    public static Item named(String bedrock) {
        Item own = BY_IDENTIFIER.get(bedrock);
        return own != null ? own : itemNamed(bedrock);
    }

    /**
     * The component names a pack wrote on an item, which its filters ask about by name.
     *
     * Java keeps no such list on an item, and only the components this mod acts on leave any trace
     * on the registered one, so what the pack declared is kept whole beside it. An item of the
     * game's own has none.
     */
    public static Set<String> componentsOf(Item item) {
        return COMPONENTS.getOrDefault(item, Set.of());
    }

    /**
     * The entity an item launches when something fires it.
     *
     * `minecraft:projectile` on the ammunition says what flies. Ammunition with no such component is
     * one of the game's own, where the entity carries the item's name: an arrow fires an arrow.
     */
    public static String projectileOf(Item ammunition) {
        String declared = PROJECTILES.get(ammunition);
        return declared != null ? declared : BuiltInRegistries.ITEM.getKey(ammunition).toString();
    }

    /**
     * Lets a dropped stack lie there forever where the pack asked it to.
     *
     * `minecraft:should_despawn` switches off the five minutes a dropped item lasts. Bedrock states
     * that on the item and Java counts the time on the entity carrying it, so the answer is given
     * to each drop as it comes into the world.
     */
    public static void keepDropped(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity dropped
            && KEPT.contains(dropped.getItem().getItem())) {
            dropped.setUnlimitedLifetime();
        }
    }

    /**
     * Whether the off hand refuses this stack.
     *
     * Bedrock takes nothing into that slot until `minecraft:allow_off_hand` lets it in, so every
     * item a behaviour pack declares is kept out of it unless the pack asked otherwise. The game's
     * own items are Java's business and keep Java's answer.
     */
    public static boolean refusedOffHand(net.minecraft.world.item.ItemStack stack) {
        return OFF_HAND_REFUSED.contains(stack.getItem());
    }

    /**
     * Whether holding this stack lets the crosshair stop at water and lava.
     *
     * `minecraft:liquid_clipped` is what asks for it, and Java casts the same ray for every item,
     * so the question has to be asked of whatever is in hand at the moment it is cast.
     */
    public static boolean clipsLiquid(net.minecraft.world.item.ItemStack stack) {
        return LIQUID_CLIPPED.contains(stack.getItem());
    }

    /**
     * Shows what `minecraft:durability_sensor` asks for, as a pack's own item takes damage.
     *
     * The durability the sensor measures is what the stack has left, which Java counts from the
     * other end as the damage done to it.
     */
    public static void durabilityLost(net.minecraft.world.item.ItemStack stack,
        net.minecraft.world.entity.LivingEntity holder) {
        DurabilitySensor sensor = SENSORS.get(stack.getItem());
        if (sensor == null || !(holder.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }

        sensor.reached(level, holder, stack.getMaxDamage() - stack.getDamageValue());
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
            if (!IMPLEMENTED.contains(component)
                && !at.ioexception.mcaddon.bedrock.behaviour.CustomComponents.isCustom(component)) {
                AddonEntities.unsupported(component);
            }
        }

        Item item = build(definition, properties(definition, id), id);
        Registry.register(BuiltInRegistries.ITEM, id, item);
        BY_IDENTIFIER.put(definition.identifier(), item);
        COMPONENTS.put(item, Set.copyOf(definition.components().keySet()));
        if (definition.dyeColour() >= 0) DYEABLE.add(item);
        if (definition.components().containsKey("minecraft:should_despawn")
            && !definition.declaredTrue("minecraft:should_despawn")) {
            KEPT.add(item);
        }

        String flies = definition.text("minecraft:projectile", "projectile_entity");
        if (flies != null) PROJECTILES.put(item, flies);

        // What a pack stated, kept beside the item so every version answers the same number, even
        // where the game has no component of its own to hold it.
        if (definition.components().containsKey("minecraft:kinetic_weapon")) {
            KINETIC.put(item, new KineticTimings(
                (int) definition.number("minecraft:kinetic_weapon", "delay", 0f),
                maxDuration(definition, "damage_conditions"),
                maxDuration(definition, "knockback_conditions"),
                maxDuration(definition, "dismount_conditions")));
        }
        float busyFor = definition.number("minecraft:cooldown", "duration", 0f);
        if (busyFor > 0f) COOLDOWNS.put(item, busyFor);

        DurabilitySensor sensor = DurabilitySensor.tryFrom(definition);
        if (sensor != null) SENSORS.put(item, sensor);

        // The two editions disagree about the default: Java takes anything in the off hand and
        // Bedrock takes nothing there until `allow_off_hand` says so, which is why a pack's own item
        // is refused whether or not it declares the component.
        if (!definition.declaredTrue("minecraft:allow_off_hand")) OFF_HAND_REFUSED.add(item);
        if (definition.declaredTrue("minecraft:liquid_clipped")) LIQUID_CLIPPED.add(item);

        fuel(definition, item);
        compostable(definition, item);
        absorption(definition, item);
    }

    /**
     * Remembers what a worn item takes the damage for, where it can spend durability on it.
     *
     * `minecraft:damage_absorption` counts the absorbed hit against the item's own durability, so
     * an item the pack gave none of has nothing to spend and is reported as a gap instead.
     */
    private static void absorption(ItemDefinition definition, Item item) {
        List<String> causes = definition.absorbedCauses();
        if (causes.isEmpty()) return;

        if (definition.durability() <= 0) {
            AddonEntities.unsupported("minecraft:damage_absorption without durability");
            return;
        }
        ABSORBED.put(item, Set.copyOf(causes));
    }

    /**
     * Spends a worn item's durability instead of hurting whoever wears it.
     *
     * Bedrock ignores whatever damage is left over once the item has taken what it can, so a hit an
     * armour slot absorbs never reaches the wearer at all. The item breaks where the hit costs more
     * than it has left, which is what spends the last of its durability.
     */
    public static boolean absorbed(net.minecraft.world.entity.LivingEntity hurt,
        net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (ABSORBED.isEmpty()) return false;

        String cause = at.ioexception.mcaddon.server.DamageSources.causeOf(source);
        for (EquipmentSlot slot : ARMOUR) {
            net.minecraft.world.item.ItemStack worn = hurt.getItemBySlot(slot);
            Set<String> causes = ABSORBED.get(worn.getItem());
            if (causes == null || !worn.isDamageableItem()) continue;
            if (!causes.contains(cause) && !causes.contains("all")) continue;

            int spent = worn.getDamageValue() + Math.max(1, (int) Math.ceil(amount));
            if (spent >= worn.getMaxDamage()) {
                worn.shrink(1);
            } else {
                worn.setDamageValue(spent);
            }
            return true;
        }
        return false;
    }

    /** The slots Bedrock takes an absorbing item from, which are the ones armour is worn in. */
    private static final List<EquipmentSlot> ARMOUR = List.of(EquipmentSlot.HEAD,
        EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    /**
     * Which Java item class this becomes.
     *
     * Placing a block, releasing an entity and throwing one each need a class of their own, and
     * every other component only decorates the plain item at the end.
     */
    private static Item build(ItemDefinition definition, Item.Properties properties, Identifier id) {
        Block placed = placedBlock(definition);
        if (placed != null) return Platform.buildBlockItem(placed, properties, id);

        EntityType<?> released = placedEntity(definition);
        if (released != null) {
            SpawnEggAppearance appearance =
                appearanceOf(definition.text("minecraft:entity_placer", "entity"));
            Item egg = Platform.spawnEgg(released, id, appearance.baseColour(), appearance.overlayColour());
            if (egg != null) return egg;
        }

        if (definition.components().containsKey("minecraft:record")) {
            Item disc = Platform.buildRecord(properties, recordSound(definition, id),
                (int) definition.number("minecraft:record", "comparator_signal", 1f),
                Math.round(definition.number("minecraft:record", "duration", 0f)));
            if (disc != null) return disc;
        }

        // Bedrock splits the bag from the way it is clicked. Java keeps both in one class, so either
        // component alone is enough to mean a bundle.
        if (definition.components().containsKey("minecraft:storage_item")
            || definition.components().containsKey("minecraft:bundle_interaction")) {
            return Platform.buildStorage(properties, id);
        }

        Item shooter = shooter(definition, properties, id);
        if (shooter != null) return shooter;

        Item thrown = thrower(definition, properties, id);
        if (thrown != null) return thrown;

        // An item whose behaviour is a pack's own script, and which nothing above already claimed.
        at.ioexception.mcaddon.bedrock.behaviour.CustomComponents scripted =
            definition.scriptedComponents();
        if (!scripted.isEmpty()) return AddonScriptedItem.build(properties, id, scripted);

        JsonObject wearable = definition.components().get("minecraft:wearable");
        return wearable == null
            ? Platform.buildItem(properties, id)
            : Platform.buildWearable(properties, id, wornSlot(definition));
    }

    /**
     * The sound event a record names, or null when this game has none under that name.
     *
     * Under a stable format version a record names one of the engine's own sound events, and a pack
     * gives it music of its own by writing that same name in its `sound_definitions.json`. The
     * definition is what the converter turns into a sound event, so a record naming an engine sound
     * the pack never defined has nothing to play and is reported rather than left silent.
     *
     * A name carrying no namespace of its own belongs to the pack that wrote it, and an item's own
     * namespace is that pack's, because a converted pack takes the namespace its declarations use.
     */
    private static Identifier recordSound(ItemDefinition definition, Identifier id) {
        String named = definition.text("minecraft:record", "sound_event");
        if (named == null) return null;

        Names.Identifier sound = Names.soundEvent(named, id.getNamespace());
        if (sound.namespace().isEmpty() || sound.path().isEmpty()) return null;

        Identifier event = Platform.id(sound.namespace(), sound.path());
        return BuiltInRegistries.SOUND_EVENT.containsKey(event) ? event : null;
    }

    /** Everything a component can say about an ordinary item, gathered into one set of properties. */
    private static Item.Properties properties(ItemDefinition definition, Identifier id) {
        Item.Properties properties = new Item.Properties().stacksTo(definition.maxStackSize());

        int durability = definition.durability();
        // A damageable item holds one per slot in both editions, and `durability` is what says so,
        // so it has to come after the stack size the pack asked for.
        if (durability > 0) properties = properties.durability(durability);
        if (definition.declaredTrue("minecraft:fire_resistant")) properties = properties.fireResistant();

        Rarity rarity = rarity(definition);
        if (rarity != null) properties = properties.rarity(rarity);

        properties = food(definition, properties);
        properties = glint(definition, properties);
        properties = attributes(definition, properties);
        properties = digger(definition, properties);
        properties = enchantable(definition, properties);
        properties = cooldown(definition, properties);
        properties = repairable(definition, properties);
        properties = dyeable(definition, properties);
        properties = piercing(definition, properties);
        properties = kinetic(definition, properties);
        properties = reach(definition, properties);
        // A record's song is named rather than held, and the entry that name points at is a file the
        // converter writes, so the name is all an item registered this early can carry. A record
        // with no sound to play gets no song, because the entry would name an event that is not
        // there and the game throws rather than shrugging when it looks one up.
        if (definition.components().containsKey("minecraft:record")) {
            if (recordSound(definition, id) == null) {
                AddonEntities.unsupported("minecraft:record");
            } else {
                properties = Platform.jukeboxPlayable(properties, id);
            }
        }
        return weapon(definition, properties);
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
     * Eating, where the pack means eating.
     *
     * A food component beside a `use_animation` of `none` asks for an item the player can hold
     * right click on. Bedrock drives that interaction from the animation and `none` draws nothing,
     * so the player never swallows it. Java has one component for both, and handing that case to
     * `Item.Properties.food` makes a hat edible.
     */
    private static Item.Properties food(ItemDefinition definition, Item.Properties properties) {
        if (!definition.edible()) return properties;

        JsonObject food = definition.components().get("minecraft:food");
        Item.Properties eaten = Platform.food(properties,
            food.has("nutrition") ? food.get("nutrition").getAsInt() : 0,
            definition.saturation(),
            food.has("can_always_eat") && food.get("can_always_eat").getAsBoolean(),
            // A pack that says a hat takes 9999 seconds to eat means it is never eaten, and Java
            // cannot express that, so the duration is kept within what a use can actually last.
            Math.min(definition.useSeconds(), 60f));

        if (!definition.useAnimation().equals("drink")) return eaten;

        //? if >=26 {
        return eaten.component(net.minecraft.core.component.DataComponents.CONSUMABLE,
            net.minecraft.world.item.component.Consumable.builder()
                .consumeSeconds(Math.min(definition.useSeconds(), 60f))
                .animation(net.minecraft.world.item.ItemUseAnimation.DRINK)
                .sound(net.minecraft.sounds.SoundEvents.GENERIC_DRINK)
                .build());
        //?} else {
        /*// The animation a use plays only became a component of its own in 1.21.2; before that it
        // is what the item's class returns, and a data-driven item is a plain one.
        return eaten;
        *///?}
    }

    /**
     * How the name is coloured.
     *
     * Java shows four name colours and they are the four rarities, so a `hover_text_color` one of
     * them matches becomes that rarity. `minecraft:rarity` names the same four outright and wins
     * where a pack declares both.
     */
    private static Rarity rarity(ItemDefinition definition) {
        String named = definition.text("minecraft:rarity", "value");
        Rarity declared = named == null ? null : switch (named.toLowerCase(Locale.ROOT)) {
            case "common" -> Rarity.COMMON;
            case "uncommon" -> Rarity.UNCOMMON;
            case "rare" -> Rarity.RARE;
            case "epic" -> Rarity.EPIC;
            default -> null;
        };
        if (declared != null) return declared;

        String colour = definition.text("minecraft:hover_text_color", "value");
        return colour == null ? null : switch (colour.toLowerCase(Locale.ROOT)) {
            case "white" -> Rarity.COMMON;
            case "yellow" -> Rarity.UNCOMMON;
            case "aqua" -> Rarity.RARE;
            case "light_purple" -> Rarity.EPIC;
            default -> null;
        };
    }

    /**
     * The enchanted shimmer, which Java has carried as a component of its own since 1.20.5.
     *
     * Bedrock spells it twice over: `minecraft:glint` and the older `minecraft:foil`, which the
     * vanilla behaviour pack still writes on both golden apples. Either one saying so is enough.
     */
    private static Item.Properties glint(ItemDefinition definition, Item.Properties properties) {
        if (!definition.declaredTrue("minecraft:glint") && !definition.declaredTrue("minecraft:foil")) {
            return properties;
        }

        //? if >=1.21 {
        return properties.component(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        //?} else {
        /*return properties;
        *///?}
    }

    /**
     * Attack damage and armour points, as the attribute modifiers Java carries on an item.
     *
     * Bedrock counts damage as the whole amount the hit deals and Java counts it on top of the
     * player's own bare hand, which is worth one.
     *
     * Before 1.20.5 both numbers came from the material an item class picked, and a data-driven
     * item has no class of its own.
     */
    private static Item.Properties attributes(ItemDefinition definition, Item.Properties properties) {
        float damage = definition.number("minecraft:damage", "value", 0f) - 1f;
        float protection = definition.protection();
        float swingSeconds = definition.number("minecraft:swing_duration", "value", 0f);
        if (damage <= 0f && protection <= 0f && swingSeconds <= 0f) return properties;

        //? if >=1.21 {
        net.minecraft.world.item.component.ItemAttributeModifiers.Builder modifiers =
            net.minecraft.world.item.component.ItemAttributeModifiers.builder();
        if (damage > 0f) {
            modifiers.add(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
                new net.minecraft.world.entity.ai.attributes.AttributeModifier(McAddon.id("item_damage"),
                    damage, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
                net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND);
        }
        if (protection > 0f) {
            modifiers.add(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR,
                new net.minecraft.world.entity.ai.attributes.AttributeModifier(McAddon.id("item_protection"),
                    protection, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
                net.minecraft.world.entity.EquipmentSlotGroup.bySlot(wornSlot(definition)));
        }
        // Bedrock says how long one swing takes and Java says how many fit in a second, and what an
        // item carries is what to add to the four an empty hand is worth.
        if (swingSeconds > 0f) {
            modifiers.add(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED,
                new net.minecraft.world.entity.ai.attributes.AttributeModifier(McAddon.id("item_swing"),
                    1f / swingSeconds - BARE_HAND_SWINGS,
                    net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
                net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND);
        }
        return properties.attributes(modifiers.build());
        //?} else {
        /*return properties;
        *///?}
    }

    /** How many times a second an empty hand swings, which every item's own speed is stated against. */
    private static final float BARE_HAND_SWINGS = 4f;

    /**
     * Mining speed per block, from the speeds `minecraft:digger` lists.
     *
     * Before 1.20.5 a tool's speed came from its material, which a data-driven item has none of.
     */
    private static Item.Properties digger(ItemDefinition definition, Item.Properties properties) {
        if (definition.destroySpeeds().isEmpty()) return properties;

        //? if >=26 {
        return properties.component(net.minecraft.core.component.DataComponents.TOOL,
            new net.minecraft.world.item.component.Tool(rules(definition), 1f,
                definition.durability() > 0 ? 1 : 0,
                definition.declaredTrue("minecraft:can_destroy_in_creative")));
        //?} else if >=1.21 {
        /*return properties.component(net.minecraft.core.component.DataComponents.TOOL,
            new net.minecraft.world.item.component.Tool(rules(definition), 1f,
                definition.durability() > 0 ? 1 : 0));
        *///?} else {
        /*return properties;
        *///?}
    }

    //? if >=1.21 {
    /** The blocks a digger names, as the rules Java matches a block being broken against. */
    private static List<net.minecraft.world.item.component.Tool.Rule> rules(ItemDefinition definition) {
        return definition.destroySpeeds().stream()
            .map(AddonItems::rule)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    /**
     * One speed entry as a rule, or null where the game has no block under that name.
     *
     * An entry whose block is a Molang tag query never reaches here, so every rule built names one
     * block and marks it as one this item mines the drops of, which is what a Bedrock digger does.
     */
    private static net.minecraft.world.item.component.Tool.Rule rule(ItemDefinition.DestroySpeed speed) {
        Block block = blockNamed(speed.block());
        if (block == null) return null;

        return new net.minecraft.world.item.component.Tool.Rule(
            net.minecraft.core.HolderSet.direct(block.builtInRegistryHolder()),
            java.util.Optional.of(speed.speed()), java.util.Optional.of(true));
    }
    //?}

    /** The value an enchanting table works from, which Java has carried as a component since 1.21.2. */
    private static Item.Properties enchantable(ItemDefinition definition, Item.Properties properties) {
        int value = (int) definition.number("minecraft:enchantable", "value", 0f);
        if (value <= 0) return properties;

        //? if >=26 {
        return properties.enchantable(value);
        //?} else {
        /*return properties;
        *///?}
    }

    /** The pause before the item can be used again. Both editions count it in seconds. */
    private static Item.Properties cooldown(ItemDefinition definition, Item.Properties properties) {
        float seconds = definition.number("minecraft:cooldown", "duration", 0f);
        if (seconds <= 0f) return properties;

        //? if >=26 {
        return properties.useCooldown(seconds);
        //?} else {
        /*return properties;
        *///?}
    }

    /**
     * The items an anvil accepts for repairing this one.
     *
     * How much each repair gives back is Java's own rule. Before 1.21.5 the list came from the item
     * class, and a data-driven item has no class of its own.
     */
    private static Item.Properties repairable(ItemDefinition definition, Item.Properties properties) {
        List<String> names = definition.repairItems();
        if (names.isEmpty()) return properties;

        //? if >=26 {
        List<net.minecraft.core.Holder.Reference<Item>> items = names.stream()
            .map(AddonItems::itemNamed)
            .filter(java.util.Objects::nonNull)
            .map(Item::builtInRegistryHolder)
            .toList();
        if (items.isEmpty()) return properties;

        return properties.component(net.minecraft.core.component.DataComponents.REPAIRABLE,
            new net.minecraft.world.item.enchantment.Repairable(net.minecraft.core.HolderSet.direct(items)));
        //?} else {
        /*return properties;
        *///?}
    }

    /**
     * The colour a dyeable item is painted before anybody dyes it.
     *
     * What may be dyed is a tag before 26 and a recipe naming the item from 26 on, and the converter
     * writes whichever the running version reads. 1.20.1 decides it from the item's Java class
     * instead, which a data-driven item has none of, so a dyeable item is an ordinary one there.
     */
    private static Item.Properties dyeable(ItemDefinition definition, Item.Properties properties) {
        int colour = definition.dyeColour();
        if (colour < 0) return properties;

        //? if >=26 {
        return properties.component(net.minecraft.core.component.DataComponents.DYED_COLOR,
            new net.minecraft.world.item.component.DyedItemColor(colour));
        //?} else if >=1.21 {
        /*return properties.component(net.minecraft.core.component.DataComponents.DYED_COLOR,
            new net.minecraft.world.item.component.DyedItemColor(colour, true));
        *///?} else {
        /*return properties;
        *///?}
    }

    /**
     * A weapon that hits everything standing in a line, which Java carries as a component from 26.
     *
     * Bedrock says only how far the line reaches, which is the reach component beside this one.
     * Whether the hit pushes its targets back and whether it unseats a rider are Java's own, and
     * are answered the way Java's own piercing weapon answers them.
     */
    private static Item.Properties piercing(ItemDefinition definition, Item.Properties properties) {
        if (!definition.components().containsKey("minecraft:piercing_weapon")) return properties;

        //? if >=26 {
        return properties.component(net.minecraft.core.component.DataComponents.PIERCING_WEAPON,
            new net.minecraft.world.item.component.PiercingWeapon(true, false,
                java.util.Optional.empty(), java.util.Optional.empty()));
        //?} else {
        /*return properties;
        *///?}
    }

    /**
     * A weapon whose damage comes from how fast its user and its target close on each other.
     *
     * Both editions hold the item to use it and count the damage off the two velocities along the
     * view vector. `damage_modifier` adds to the scaled result and Java's component has nowhere to
     * put it, so it is reported rather than silently dropped. The pause before the same target may
     * be hit again is Java's own, as is how far the held model lunges.
     */
    private static Item.Properties kinetic(ItemDefinition definition, Item.Properties properties) {
        if (!definition.components().containsKey("minecraft:kinetic_weapon")) return properties;
        if (definition.number("minecraft:kinetic_weapon", "damage_modifier", 0f) != 0f) {
            AddonEntities.unsupported("minecraft:kinetic_weapon damage_modifier");
        }

        //? if >=26 {
        return properties.component(net.minecraft.core.component.DataComponents.KINETIC_WEAPON,
            new net.minecraft.world.item.component.KineticWeapon(SAME_TARGET_PAUSE,
                (int) definition.number("minecraft:kinetic_weapon", "delay", 0f),
                condition(definition, "dismount_conditions"),
                condition(definition, "knockback_conditions"),
                condition(definition, "damage_conditions"),
                0f, definition.number("minecraft:kinetic_weapon", "damage_multiplier", 1f),
                java.util.Optional.empty(), java.util.Optional.empty()));
        //?} else {
        /*return properties;
        *///?}
    }

    /** How long a kinetic weapon leaves a target alone after hitting it, in ticks. */
    private static final int SAME_TARGET_PAUSE = 10;

    /** How long one of a kinetic weapon's effects lasts, in ticks, or nothing where it never applies. */
    private static int maxDuration(ItemDefinition definition, String field) {
        ItemDefinition.KineticCondition declared = definition.kineticCondition(field);
        return declared == null ? 0 : declared.durationTicks();
    }

    //? if >=26 {
    /** One `*_conditions` block, absent where the pack declared none and the effect never applies. */
    private static java.util.Optional<net.minecraft.world.item.component.KineticWeapon.Condition>
        condition(ItemDefinition definition, String field) {
        ItemDefinition.KineticCondition declared = definition.kineticCondition(field);
        return declared == null ? java.util.Optional.empty()
            : java.util.Optional.of(new net.minecraft.world.item.component.KineticWeapon.Condition(
                declared.durationTicks(), declared.speed(), declared.relativeSpeed()));
    }
    //?}

    /**
     * How far a piercing or kinetic weapon reaches, and how wide a miss it forgives.
     *
     * Bedrock states the reach on the weapon component itself and Java carries it separately, so
     * one component's range becomes the range of every attack made with the item.
     */
    private static Item.Properties reach(ItemDefinition definition, Item.Properties properties) {
        ItemDefinition.Reach declared = definition.reach("minecraft:piercing_weapon");
        if (declared == null) declared = definition.reach("minecraft:kinetic_weapon");
        if (declared == null) return properties;

        //? if >=26 {
        return properties.component(net.minecraft.core.component.DataComponents.ATTACK_RANGE,
            new net.minecraft.world.item.component.AttackRange(declared.min(), declared.max(),
                declared.creativeMin(), declared.creativeMax(), declared.margin(), 1f));
        //?} else {
        /*return properties;
        *///?}
    }

    /**
     * A weapon spends a point of durability on every swing, which is the whole of what Java's own
     * weapon component carries.
     */
    private static Item.Properties weapon(ItemDefinition definition, Item.Properties properties) {
        if (!definition.components().containsKey("minecraft:weapon")) return properties;

        //? if >=26 {
        return properties.component(net.minecraft.core.component.DataComponents.WEAPON,
            new net.minecraft.world.item.component.Weapon(1));
        //?} else {
        /*return properties;
        *///?}
    }

    /**
     * How long the item burns in a furnace. Bedrock counts it in seconds and Java in ticks.
     *
     * The burn list is built once per world load from 26 on, so the value is handed to the event
     * that builds it instead of to a registry that already exists.
     */
    private static void fuel(ItemDefinition definition, Item item) {
        float seconds = definition.number("minecraft:fuel", "duration", 0f);
        if (seconds <= 0f) return;

        int ticks = Math.round(seconds * 20f);
        //? if >=26 {
        net.fabricmc.fabric.api.registry.FuelValueEvents.BUILD.register(
            (builder, context) -> builder.add(item, ticks));
        //?} else {
        /*net.fabricmc.fabric.api.registry.FuelRegistry.INSTANCE.add(item, ticks);
        *///?}
    }

    /** What a composter gives back. Bedrock states the chance out of a hundred and Java out of one. */
    private static void compostable(ItemDefinition definition, Item item) {
        float chance = definition.number("minecraft:compostable", "composting_chance", 0f);
        if (chance <= 0f) return;

        //? if >=26 {
        net.fabricmc.fabric.api.registry.CompostableRegistry.INSTANCE.add(item, chance / 100f);
        //?} else {
        /*net.fabricmc.fabric.api.registry.CompostingChanceRegistry.INSTANCE.add(item, chance / 100f);
        *///?}
    }

    /**
     * The block this item places.
     *
     * `minecraft:seed` names the crop that grows where the seed was used, which is what a Java seed
     * item places. A behaviour pack's own blocks are registered after its items, so an item naming
     * one of those finds nothing here and the name is counted as a gap.
     */
    private static Block placedBlock(ItemDefinition definition) {
        Block placer = blockComponent(definition, "minecraft:block_placer", "block");
        if (placer != null) return placer;

        Block crop = blockComponent(definition, "minecraft:seed", "crop_result");
        return crop != null ? crop : blockComponent(definition, "minecraft:block", "value");
    }

    /** The block `component` names under `field`, counting a name the game does not have as a gap. */
    private static Block blockComponent(ItemDefinition definition, String component, String field) {
        String name = definition.text(component, field);
        if (name == null) return null;

        Block block = blockNamed(name);
        if (block == null) AddonEntities.unsupported(component);
        return block;
    }

    private static Block blockNamed(String name) {
        Identifier id = identifier(name);
        return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
    }

    private static Item itemNamed(String name) {
        Identifier id = identifier(name);
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /** The entity this item releases, which is what a Java spawn egg does. */
    private static EntityType<?> placedEntity(ItemDefinition definition) {
        String name = definition.text("minecraft:entity_placer", "entity");
        if (name == null) return null;

        EntityType<?> type = AddonEntities.typeOf(name);
        if (type == null) AddonEntities.unsupported("minecraft:entity_placer");
        return type;
    }

    /**
     * The bow a pack declared, or null where this item is not one.
     *
     * A shooter with no ammunition listed can never fire, so it is reported as a gap rather than
     * registered as a weapon that does nothing when it is drawn.
     */
    private static Item shooter(ItemDefinition definition, Item.Properties properties, Identifier id) {
        if (!definition.components().containsKey("minecraft:shooter")) return null;

        List<String> ammunition = definition.ammunition();
        if (ammunition.isEmpty()) {
            AddonEntities.unsupported("minecraft:shooter");
            return null;
        }

        JsonObject shooter = definition.components().get("minecraft:shooter");
        return AddonShooterItem.build(properties, id, ammunition,
            definition.number("minecraft:shooter", "max_draw_duration", 0f),
            !shooter.has("scale_power_by_draw_duration")
                || shooter.get("scale_power_by_draw_duration").getAsBoolean(),
            definition.number("minecraft:shooter", "launch_power_scale", 1f));
    }

    /**
     * The item a pack throws, or null where it described something else.
     *
     * `minecraft:projectile` on its own describes ammunition a shooter fires, which needs the bow
     * this mod has no reader for, and `minecraft:throwable` on its own never says what flies.
     */
    private static Item thrower(ItemDefinition definition, Item.Properties properties, Identifier id) {
        String entity = definition.text("minecraft:projectile", "projectile_entity");
        boolean throwable = definition.components().containsKey("minecraft:throwable");
        if (throwable && entity != null) {
            return AddonThrowableItem.build(properties, id, entity,
                definition.number("minecraft:throwable", "launch_power_scale", 1f));
        }

        if (throwable) AddonEntities.unsupported("minecraft:throwable");
        if (entity != null) AddonEntities.unsupported("minecraft:projectile");
        return null;
    }

    private static EquipmentSlot wornSlot(ItemDefinition definition) {
        JsonObject wearable = definition.components().get("minecraft:wearable");
        return slot(wearable == null ? "slot.armor.head" : Json.text(wearable, "slot", "slot.armor.head"));
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

    /**
     * Everything an item can declare that this mod does something about.
     *
     * A component read only to shape another one belongs here as well: `use_modifiers` carries the
     * duration the food component is registered with, and `use_animation` decides whether there is a
     * food component at all. A component named here and read nowhere would stop `/mcaddon entities`
     * counting a gap that is still a gap, so nothing is listed on the grounds that Java cannot
     * express it.
     *
     * `minecraft:stacked_by_data` is the one name here with no reader, and that is because there is
     * nothing for it to read. It decides whether copies of an item holding different data values
     * stack, and an add-on item in Java holds no data value, so every copy of it is alike and Java
     * stacks them as Bedrock does.
     *
     * `minecraft:record` reaches a jukebox two ways. From 1.21 the song is an entry in a registry a
     * data pack writes into, which the converter writes as a file and the item names; before that
     * the item is a `RecordItem` carrying the sound itself and joins the tag a jukebox accepts.
     */
    private static final List<String> IMPLEMENTED = List.of(
        "minecraft:icon", "minecraft:display_name", "minecraft:max_stack_size", "minecraft:tags",
        "minecraft:record",
        "minecraft:food", "minecraft:use_animation", "minecraft:use_duration",
        "minecraft:use_modifiers", "minecraft:durability", "minecraft:max_damage",
        "minecraft:repairable", "minecraft:wearable", "minecraft:armor",
        "minecraft:hand_equipped", "minecraft:rarity", "minecraft:hover_text_color",
        "minecraft:glint", "minecraft:foil", "minecraft:fire_resistant", "minecraft:fuel",
        "minecraft:compostable", "minecraft:damage", "minecraft:weapon",
        "minecraft:digger", "minecraft:can_destroy_in_creative", "minecraft:enchantable",
        "minecraft:cooldown", "minecraft:block", "minecraft:block_placer",
        "minecraft:seed", "minecraft:entity_placer", "minecraft:throwable",
        "minecraft:projectile", "minecraft:dyeable", "minecraft:should_despawn",
        "minecraft:shooter", "minecraft:storage_item", "minecraft:bundle_interaction",
        "minecraft:swing_duration", "minecraft:custom_components",
        "minecraft:piercing_weapon", "minecraft:kinetic_weapon", "minecraft:damage_absorption",
        "minecraft:allow_off_hand", "minecraft:liquid_clipped", "minecraft:durability_sensor",
        "minecraft:stacked_by_data");

    /**
     * Item components read on purpose that the schemas Mojang publishes today do not carry.
     *
     * `minecraft:armor` states the protection `minecraft:wearable` states instead, and
     * `minecraft:weapon` the swing `minecraft:damage` states instead. A pack written against an
     * older format version ships the older spelling, so both are read and both are listed in
     * `IMPLEMENTED` beside the current ones. `minecraft:custom_components` is the list of a pack's
     * own components an item named before each became a component of its own, and
     * `CustomComponents` reads both spellings.
     *
     * `tools/census/components.py` reads this declaration to tell a name kept for such a pack from
     * one that has gone stale, so the list it ends with means a name nothing published has and
     * nobody meant. A name here has to be one `IMPLEMENTED` carries and one no published schema
     * does, and the census says so when it is neither.
     */
    public static final Set<String> LEGACY = Set.of("minecraft:armor", "minecraft:weapon",
        "minecraft:custom_components");

    private static Identifier identifier(String bedrock) {
        Names.Identifier split = Names.split(bedrock, "minecraft");
        return split.namespace().isEmpty() || split.path().isEmpty() ? null
            : Platform.id(split.namespace(), split.path());
    }
}
