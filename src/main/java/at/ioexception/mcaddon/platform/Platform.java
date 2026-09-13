package at.ioexception.mcaddon.platform;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.21 {
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
//?}
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
//? if >=26 {
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
//?}

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every call whose spelling differs between the supported Minecraft versions.
 *
 * Keeping them here is what lets the rest of the mod read as ordinary code. Whole-class renames are
 * handled by the string replacements in the buildscript instead.
 */
public final class Platform {
    private Platform() {
    }

    /** The factory replaced the public constructor in 1.21. */
    public static Identifier id(String namespace, String path) {
        //? if >=1.21 {
        return Identifier.fromNamespaceAndPath(namespace, path);
        //?} else {
        /*return new Identifier(namespace, path);
        *///?}
    }

    public static Identifier idOf(ResourceKey<?> key) {
        //? if >=26 {
        return key.identifier();
        //?} else {
        /*return key.location();
        *///?}
    }

    public static Entity entityByUuid(ServerLevel level, UUID uuid) {
        //? if >=26 {
        return level.getEntityInAnyDimension(uuid);
        //?} else {
        /*return level.getEntity(uuid);
        *///?}
    }

    public static Set<String> tagsOf(Entity entity) {
        //? if >=26 {
        return entity.entityTags();
        //?} else {
        /*return entity.getTags();
        *///?}
    }

    /** Ticks since dawn on day zero. The name changed when the clock moved to the overworld. */
    public static long dayTime(Level level) {
        //? if >=26 {
        return level.getOverworldClockTime();
        //?} else {
        /*return level.getDayTime();
        *///?}
    }

    public static void kill(Entity entity, ServerLevel level) {
        //? if >=1.21.2 {
        entity.kill(level);
        //?} else {
        /*entity.kill();
        *///?}
    }

    public static void hurt(Entity entity, ServerLevel level, DamageSource source, float amount) {
        //? if >=1.21.2 {
        entity.hurtServer(level, source, amount);
        //?} else {
        /*entity.hurt(source, amount);
        *///?}
    }

    /** Sets position and rotation without interpolating from where the entity was. */
    public static void placeAt(Entity entity, double x, double y, double z, float yaw, float pitch) {
        //? if >=26 {
        entity.snapTo(x, y, z, yaw, pitch);
        //?} else {
        /*entity.moveTo(x, y, z, yaw, pitch);
        *///?}
    }

    public static CommandSourceStack commandSourceOf(Entity entity, ServerLevel level) {
        //? if >=26 {
        return entity.createCommandSourceStackForNameResolution(level);
        //?} else {
        /*return entity.createCommandSourceStack();
        *///?}
    }

    public static GameType gameMode(ServerPlayer player) {
        //? if >=26 {
        return player.gameMode();
        //?} else {
        /*return player.gameMode.getGameModeForPlayer();
        *///?}
    }

    /** The builder started naming its type with a registry key rather than a string in 1.21.2. */
    public static <T extends Entity> EntityType<T> buildEntityType(EntityType.Builder<T> builder, Identifier id) {
        //? if >=1.21.2 {
        return builder.build(ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, id));
        //?} else {
        /*return builder.build(id.toString());
        *///?}
    }

    public static Entity createMob(EntityType<?> type, ServerLevel level) {
        //? if >=1.21.2 {
        return type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        //?} else {
        /*return type.create(level);
        *///?}
    }

    /**
     * False when no effect is registered under that id.
     *
     * Three forms rather than two: the effect instance started taking a holder in 1.20.5, and the
     * registry lookup that returns one was `getHolder` until 26 renamed it to `get`.
     */
    public static boolean addEffect(LivingEntity entity, Identifier effect, int duration, int amplifier,
        boolean particles) {
        //? if >=26 {
        return BuiltInRegistries.MOB_EFFECT.get(effect)
            .map(holder -> entity.addEffect(new MobEffectInstance(holder, duration, amplifier, false, particles)))
            .orElse(false);
        //?} else if >=1.21 {
        /*return BuiltInRegistries.MOB_EFFECT.getHolder(effect)
            .map(holder -> entity.addEffect(new MobEffectInstance(holder, duration, amplifier, false, particles)))
            .orElse(false);
        *///?} else {
        /*net.minecraft.world.effect.MobEffect found = BuiltInRegistries.MOB_EFFECT.get(effect);
        return found != null
            && entity.addEffect(new MobEffectInstance(found, duration, amplifier, false, particles));
        *///?}
    }

    /** Whether the entity currently has that effect. The lookup returns a holder from 1.21. */
    public static boolean hasEffect(LivingEntity entity, Identifier effect) {
        //? if >=26 {
        return BuiltInRegistries.MOB_EFFECT.get(effect).map(entity::hasEffect).orElse(false);
        //?} else if >=1.21 {
        /*return BuiltInRegistries.MOB_EFFECT.getHolder(effect).map(entity::hasEffect).orElse(false);
        *///?} else {
        /*net.minecraft.world.effect.MobEffect found = BuiltInRegistries.MOB_EFFECT.get(effect);
        return found != null && entity.hasEffect(found);
        *///?}
    }

    public static boolean removeEffect(LivingEntity entity, Identifier effect) {
        //? if >=26 {
        return BuiltInRegistries.MOB_EFFECT.get(effect).map(entity::removeEffect).orElse(false);
        //?} else if >=1.21 {
        /*return BuiltInRegistries.MOB_EFFECT.getHolder(effect).map(entity::removeEffect).orElse(false);
        *///?} else {
        /*net.minecraft.world.effect.MobEffect found = BuiltInRegistries.MOB_EFFECT.get(effect);
        return found != null && entity.removeEffect(found);
        *///?}
    }

    /** The slot the player is holding. It was a public field before 26 gave it an accessor. */
    public static int selectedSlot(ServerPlayer player) {
        //? if >=26 {
        return player.getInventory().getSelectedSlot();
        //?} else {
        /*return player.getInventory().selected;
        *///?}
    }

    /**
     * A pack the game reads out of a directory, built without consulting its metadata.
     *
     * The metadata is supplied here rather than read back off disk so the pack is always reported as
     * compatible: one converted directory is contributed to both the resource and the data pack
     * repository, and a single `pack.mcmeta` cannot carry the right format number for both.
     */
    public static Pack directoryPack(String id, String title, Path directory, PackType type) {
        Component name = Component.literal(title);
        //? if >=1.21 {
        PackLocationInfo location = new PackLocationInfo(id, name, PackSource.BUILT_IN, Optional.empty());
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo info) {
                return new PathPackResources(info, directory);
            }

            @Override
            public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                return new PathPackResources(info, directory);
            }
        };
        // The features a pack asks for are what the world ends up enabled for, and a world enabled
        // for anything other than the ordinary set is one the game calls experimental: every world
        // with this mod installed would open behind a warning asking whether to load it at all.
        Pack.Metadata metadata = new Pack.Metadata(name, PackCompatibility.COMPATIBLE,
            FeatureFlags.VANILLA_SET, List.of());
        return new Pack(location, resources, metadata, new PackSelectionConfig(true, Pack.Position.TOP, true));
        //?} else {
        /*Pack.ResourcesSupplier resources = ignored -> new PathPackResources(id, directory, false);
        return Pack.readMetaAndCreate(id, name, true, resources, type, Pack.Position.TOP, PackSource.BUILT_IN);
        *///?}
    }

    /** Fabric renamed its creative tab builder in 26 to follow the game's own rename. */
    public static net.minecraft.world.item.CreativeModeTab.Builder creativeTab() {
        //? if >=26 {
        return net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab.builder();
        //?} else {
        /*return net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder();
        *///?}
    }

    /**
     * One villager trade.
     *
     * What a trade costs became an `ItemCost` in 1.21, which carries the item and the count where a
     * stack used to.
     */
    public static net.minecraft.world.item.trading.MerchantOffer offer(net.minecraft.world.item.ItemStack costA,
        net.minecraft.world.item.ItemStack costB, net.minecraft.world.item.ItemStack result,
        int maxUses, int experience) {
        //? if >=1.21 {
        return new net.minecraft.world.item.trading.MerchantOffer(
            new net.minecraft.world.item.trading.ItemCost(costA.getItem(), costA.getCount()),
            costB.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(new net.minecraft.world.item.trading.ItemCost(
                    costB.getItem(), costB.getCount())),
            result, maxUses, experience, 0.05f);
        //?} else {
        /*return new net.minecraft.world.item.trading.MerchantOffer(costA, costB, result, maxUses,
            experience, 0.05f);
        *///?}
    }

    /**
     * Adds one natural spawn to a biome.
     *
     * The weight moved out of the spawner entry and into the call in 26, and Fabric renamed its
     * accessor for the settings in the same release.
     */
    public static void addSpawn(net.fabricmc.fabric.api.biome.v1.BiomeModificationContext context,
        MobCategory category, EntityType<?> type, int weight, int minimum, int maximum) {
        //? if >=26 {
        context.getMobSpawnSettings().addSpawn(category,
            new net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData(type, minimum, maximum), weight);
        //?} else {
        /*context.getSpawnSettings().addSpawn(category,
            new net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData(type, weight, minimum, maximum));
        *///?}
    }

    /**
     * Makes an item edible.
     *
     * The saturation field was renamed in 1.21, and from 1.21.2 how long eating takes moved out of
     * the food properties and into a separate consumable component.
     */
    public static Item.Properties food(Item.Properties properties, int nutrition, float saturation,
        boolean alwaysEdible, float seconds) {
        //? if >=1.21.2 {
        net.minecraft.world.food.FoodProperties.Builder food = new net.minecraft.world.food.FoodProperties
            .Builder().nutrition(nutrition).saturationModifier(saturation);
        if (alwaysEdible) food = food.alwaysEdible();
        return properties.food(food.build(), net.minecraft.world.item.component.Consumable.builder()
            .consumeSeconds(seconds).build());
        //?} else if >=1.21 {
        /*net.minecraft.world.food.FoodProperties.Builder food = new net.minecraft.world.food.FoodProperties
            .Builder().nutrition(nutrition).saturationModifier(saturation);
        if (alwaysEdible) food = food.alwaysEdible();
        return properties.food(food.build());
        *///?} else {
        /*net.minecraft.world.food.FoodProperties.Builder food = new net.minecraft.world.food.FoodProperties
            .Builder().nutrition(nutrition).saturationMod(saturation);
        if (alwaysEdible) food = food.alwaysEat();
        return properties.food(food.build());
        *///?}
    }

    /**
     * An item a pack said can be worn, in the slot it named.
     *
     * What carries that differs by version. From 1.21.2 any item can say it is equippable through a
     * data component. Before that only an armour item could be worn, and an armour item needs a
     * material, so the item is built as one with a material that adds no protection and no armour
     * layer of its own: a pack's hat is a costume, and its attachable is what draws it.
     *
     * How many of it fit in a slot is whatever the properties already say, because an item carrying
     * durability is one to a slot already and saying so twice is what the older versions refuse.
     */
    public static Item buildWearable(Item.Properties properties, Identifier id,
        net.minecraft.world.entity.EquipmentSlot slot) {
        //? if >=1.21.2 {
        return buildItem(properties.component(net.minecraft.core.component.DataComponents.EQUIPPABLE,
            net.minecraft.world.item.equipment.Equippable.builder(slot).build()), id);
        //?} else {
        /*net.minecraft.world.item.ArmorItem.Type type = armourType(slot);
        if (type == null) return buildItem(properties, id);

        return new net.minecraft.world.item.ArmorItem(costume(), type, properties);
        *///?}
    }

    //? if <1.21.2 {
    /*// The armour slot a Bedrock wearable names, or null for a slot Java has no armour item for.
    private static net.minecraft.world.item.ArmorItem.Type armourType(
        net.minecraft.world.entity.EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> net.minecraft.world.item.ArmorItem.Type.HELMET;
            case CHEST -> net.minecraft.world.item.ArmorItem.Type.CHESTPLATE;
            case LEGS -> net.minecraft.world.item.ArmorItem.Type.LEGGINGS;
            case FEET -> net.minecraft.world.item.ArmorItem.Type.BOOTS;
            default -> null;
        };
    }
    *///?}

    //? if >=1.21 && <1.21.2 {
    /*// A material that protects nothing and draws nothing, which is what a costume is.
    private static net.minecraft.core.Holder<net.minecraft.world.item.ArmorMaterial> costume() {
        return net.minecraft.core.Holder.direct(new net.minecraft.world.item.ArmorMaterial(
            java.util.Map.of(), 0, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER,
            () -> net.minecraft.world.item.crafting.Ingredient.EMPTY, java.util.List.of(), 0f, 0f));
    }
    *///?}

    //? if <1.21 {
    /*// A material that protects nothing and draws nothing, which is what a costume is.
    private static net.minecraft.world.item.ArmorMaterial costume() {
        return new net.minecraft.world.item.ArmorMaterial() {
            @Override
            public int getDurabilityForType(net.minecraft.world.item.ArmorItem.Type type) {
                return 0;
            }

            @Override
            public int getDefenseForType(net.minecraft.world.item.ArmorItem.Type type) {
                return 0;
            }

            @Override
            public int getEnchantmentValue() {
                return 0;
            }

            @Override
            public net.minecraft.sounds.SoundEvent getEquipSound() {
                return net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER;
            }

            @Override
            public net.minecraft.world.item.crafting.Ingredient getRepairIngredient() {
                return net.minecraft.world.item.crafting.Ingredient.EMPTY;
            }

            @Override
            public String getName() {
                return "mcaddon_costume";
            }

            @Override
            public float getToughness() {
                return 0f;
            }

            @Override
            public float getKnockbackResistance() {
                return 0f;
            }
        };
    }
    *///?}

    /** A block carries its own registry id from 1.21.2, the same way an item does. */
    public static Block buildBlock(BlockBehaviour.Properties properties, Identifier id) {
        //? if >=1.21.2 {
        return new Block(properties.setId(ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, id)));
        //?} else {
        /*return new Block(properties);
        *///?}
    }

    /** The same as {@link #buildBlock}, for a block class that declares its own states. */
    public static at.ioexception.mcaddon.block.AddonBlock buildStatefulBlock(
        BlockBehaviour.Properties properties, Identifier id,
        java.util.List<net.minecraft.world.level.block.state.properties.Property<?>> states) {
        //? if >=1.21.2 {
        BlockBehaviour.Properties named = properties
            .setId(ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK, id));
        //?} else {
        /*BlockBehaviour.Properties named = properties;
        *///?}
        return at.ioexception.mcaddon.block.AddonBlock.build(named, states,
            at.ioexception.mcaddon.block.AddonBlock::new);
    }

    public static Item buildBlockItem(Block block, Item.Properties properties, Identifier id) {
        //? if >=1.21.2 {
        return new net.minecraft.world.item.BlockItem(block,
            properties.setId(ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, id)));
        //?} else {
        /*return new net.minecraft.world.item.BlockItem(block, properties);
        *///?}
    }

    /** An item has carried its own registry id since 1.21.2, so it must be set before registering. */
    public static Item buildItem(Item.Properties properties, Identifier id) {
        //? if >=1.21.2 {
        return new Item(properties.setId(ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, id)));
        //?} else {
        /*return new Item(properties);
        *///?}
    }

    /**
     * A bag that holds other items, which is what Bedrock's `minecraft:storage_item` describes.
     *
     * Java keeps the picking up and putting back in `BundleItem` rather than in a component, and
     * from 1.20.5 the contents are a component the item has to carry from the start.
     */
    public static Item buildStorage(Item.Properties properties, Identifier id) {
        Item.Properties bag = properties.stacksTo(1);
        //? if >=1.21.2 {
        return new net.minecraft.world.item.BundleItem(bag
            .setId(ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, id))
            .component(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
                net.minecraft.world.item.component.BundleContents.EMPTY));
        //?} else if >=1.21 {
        /*return new net.minecraft.world.item.BundleItem(bag.component(
            net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS,
            net.minecraft.world.item.component.BundleContents.EMPTY));
        *///?} else {
        /*return new net.minecraft.world.item.BundleItem(bag);
        *///?}
    }

    /**
     * A spawn egg for an entity type.
     *
     * From 1.21.2 the entity moved into a data component, but the behaviour still lives in
     * `SpawnEggItem`: a plain item carrying the component registers, shows an icon, and spawns
     * nothing.
     */
    public static Item spawnEgg(EntityType<?> type, Identifier id, int baseColour, int overlayColour) {
        //? if >=1.21.2 {
        // From 1.21.4 the two colours are tints in the item's own model definition, which the
        // conversion writes, so the item itself carries only the entity it spawns.
        return new net.minecraft.world.item.SpawnEggItem(new Item.Properties()
            .spawnEgg(type)
            .setId(ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, id)));
        //?} else {
        /*return new net.minecraft.world.item.SpawnEggItem(castType(type), baseColour, overlayColour,
            new Item.Properties());
        *///?}
    }

    //? if <1.21.2 {
    /*@SuppressWarnings("unchecked")
    private static net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>
        castType(EntityType<?> type) {
        return (net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob>) type;
    }
    *///?}

    /**
     * Whether a data pack's directories carry their singular names.
     *
     * 1.21 renamed every one of them: `recipes`, `loot_tables`, `functions`, `advancements` and the
     * registry directories under `tags` all lost their plural. A file written under the other
     * spelling is read by nobody and reports nothing, so this is what decides where they go.
     */
    public static boolean singularDataDirectories() {
        //? if >=1.21 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Whether a recipe's ingredient is written as a bare item id.
     *
     * 1.21.2 replaced the object that named one. Before that the id goes under an `item` key.
     */
    public static boolean bareRecipeIngredients() {
        //? if >=1.21.2 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * Whether dyeing an item is stated as a recipe naming it.
     *
     * Up to 1.21 anything in `#minecraft:dyeable` could be dyed and washed. 26 dropped that tag for
     * a `minecraft:crafting_dye` recipe per item, and kept a tag only for what a cauldron washes.
     */
    public static boolean dyeingIsARecipe() {
        //? if >=26 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * What a recipe result names its item under.
     *
     * 1.21 renamed the field from `item` to `id`, a version before the ingredient changed shape.
     */
    public static String recipeResultKey() {
        //? if >=1.21 {
        return "id";
        //?} else {
        /*return "item";
        *///?}
    }

    /**
     * Who is holding this entity on a lead, or null when nobody is.
     *
     * 1.21 moved being led off `Mob` and onto an interface, so a boat and a leash knot answer for
     * themselves where only a mob used to.
     */
    public static Entity leashHolder(Entity entity) {
        //? if >=1.21 {
        return entity instanceof net.minecraft.world.entity.Leashable led && led.isLeashed()
            ? led.getLeashHolder() : null;
        //?} else {
        /*return entity instanceof net.minecraft.world.entity.Mob led && led.isLeashed()
            ? led.getLeashHolder() : null;
        *///?}
    }

    /** False for an entity that cannot be led at all, which is the answer Bedrock gives. */
    public static boolean leashTo(Entity entity, Entity holder) {
        //? if >=1.21 {
        if (!(entity instanceof net.minecraft.world.entity.Leashable led)) return false;
        //?} else {
        /*if (!(entity instanceof net.minecraft.world.entity.Mob led)) return false;
        *///?}
        led.setLeashedTo(holder, true);
        return true;
    }

    /** Drops the lead where the entity stands, the way shearing one off does. */
    public static boolean unleash(Entity entity) {
        //? if >=26 {
        if (!(entity instanceof net.minecraft.world.entity.Leashable led)) return false;
        led.dropLeash();
        //?} else if >=1.21 {
        /*if (!(entity instanceof net.minecraft.world.entity.Leashable led)) return false;
        led.dropLeash(true, true);
        *///?} else {
        /*if (!(entity instanceof net.minecraft.world.entity.Mob led)) return false;
        led.dropLeash(true, true);
        *///?}
        return true;
    }

    /**
     * A game rule, by the name Bedrock calls it, as text.
     *
     * Null for a name Java has nothing that means the same as, such as whether coordinates are
     * shown, which is what lets a pack be told the rule went unanswered instead of reading a
     * default that was never Java's.
     */
    public static String gameRule(net.minecraft.server.MinecraftServer server, String bedrock) {
        //? if >=26 {
        GameRules rules = server.getGameRules();
        if (bedrock.equals("doFireTick")) {
            return String.valueOf(rules.get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) != 0);
        }

        GameRule<?> rule = rule(bedrock);
        return rule == null ? null : rules.getAsString(rule);
        //?} else {
        /*net.minecraft.world.level.GameRules rules = server.getGameRules();
        net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.BooleanValue> flag =
            flagRule(bedrock);
        if (flag != null) return rules.getRule(flag).serialize();

        net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.IntegerValue> count =
            countRule(bedrock);
        return count == null ? null : rules.getRule(count).serialize();
        *///?}
    }

    /**
     * Writes a game rule, answering whether Java holds one that means the same.
     *
     * Text that does not read as the rule's own type leaves the rule where it was, the way the
     * command does with an argument it cannot parse.
     */
    public static boolean setGameRule(net.minecraft.server.MinecraftServer server, String bedrock,
        String value) {
        //? if >=26 {
        if (bedrock.equals("doFireTick")) {
            server.getGameRules().set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER,
                Boolean.parseBoolean(value)
                    ? GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER.defaultValue() : 0,
                server);
            return true;
        }

        GameRule<?> rule = rule(bedrock);
        if (rule == null) return false;
        assign(server, rule, value);
        return true;
        //?} else {
        /*net.minecraft.world.level.GameRules rules = server.getGameRules();
        net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.BooleanValue> flag =
            flagRule(bedrock);
        if (flag != null) {
            rules.getRule(flag).set(Boolean.parseBoolean(value), server);
            return true;
        }

        net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.IntegerValue> count =
            countRule(bedrock);
        if (count == null) return false;

        Integer parsed = whole(value);
        if (parsed != null) rules.getRule(count).set(parsed, server);
        return true;
        *///?}
    }

    //? if >=26 {
    /**
     * The Java rule a Bedrock rule name means.
     *
     * Bedrock's fire rule is absent because 26 turned it into a radius, which the two callers
     * handle themselves. The rest are named apart from their Bedrock spelling often enough that the
     * table is written out rather than derived.
     */
    private static GameRule<?> rule(String bedrock) {
        return switch (bedrock) {
            case "commandBlockOutput" -> GameRules.COMMAND_BLOCK_OUTPUT;
            case "commandBlocksEnabled" -> GameRules.COMMAND_BLOCKS_WORK;
            case "doDayLightCycle" -> GameRules.ADVANCE_TIME;
            case "doEntityDrops" -> GameRules.ENTITY_DROPS;
            case "doImmediateRespawn" -> GameRules.IMMEDIATE_RESPAWN;
            case "doInsomnia" -> GameRules.SPAWN_PHANTOMS;
            case "doLimitedCrafting" -> GameRules.LIMITED_CRAFTING;
            case "doMobLoot" -> GameRules.MOB_DROPS;
            case "doMobSpawning" -> GameRules.SPAWN_MOBS;
            case "doTileDrops" -> GameRules.BLOCK_DROPS;
            case "doWeatherCycle" -> GameRules.ADVANCE_WEATHER;
            case "drowningDamage" -> GameRules.DROWNING_DAMAGE;
            case "fallDamage" -> GameRules.FALL_DAMAGE;
            case "fireDamage" -> GameRules.FIRE_DAMAGE;
            case "freezeDamage" -> GameRules.FREEZE_DAMAGE;
            case "keepInventory" -> GameRules.KEEP_INVENTORY;
            case "maxCommandChainLength" -> GameRules.MAX_COMMAND_SEQUENCE_LENGTH;
            case "mobGriefing" -> GameRules.MOB_GRIEFING;
            case "naturalRegeneration" -> GameRules.NATURAL_HEALTH_REGENERATION;
            case "playersSleepingPercentage" -> GameRules.PLAYERS_SLEEPING_PERCENTAGE;
            case "projectilesCanBreakBlocks" -> GameRules.PROJECTILES_CAN_BREAK_BLOCKS;
            case "pvp" -> GameRules.PVP;
            case "randomTickSpeed" -> GameRules.RANDOM_TICK_SPEED;
            case "sendCommandFeedback" -> GameRules.SEND_COMMAND_FEEDBACK;
            case "showDeathMessages" -> GameRules.SHOW_DEATH_MESSAGES;
            case "spawnRadius" -> GameRules.RESPAWN_RADIUS;
            case "tntExplodes" -> GameRules.TNT_EXPLODES;
            case "tntExplosionDropDecay" -> GameRules.TNT_EXPLOSION_DROP_DECAY;
            default -> null;
        };
    }

    /** Reads text as the rule's own value type, leaving the rule alone when it does not read. */
    private static <T> void assign(net.minecraft.server.MinecraftServer server, GameRule<T> rule,
        String value) {
        rule.deserialize(value).result()
            .ifPresent(parsed -> server.getGameRules().set(rule, parsed, server));
    }
    //?}

    //? if <26 {
    /*// The Java rule a Bedrock rule name means, among the rules holding a flag.
    private static net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.BooleanValue>
        flagRule(String bedrock) {
        return switch (bedrock) {
            case "commandBlockOutput" -> net.minecraft.world.level.GameRules.RULE_COMMANDBLOCKOUTPUT;
            case "doDayLightCycle" -> net.minecraft.world.level.GameRules.RULE_DAYLIGHT;
            case "doEntityDrops" -> net.minecraft.world.level.GameRules.RULE_DOENTITYDROPS;
            case "doFireTick" -> net.minecraft.world.level.GameRules.RULE_DOFIRETICK;
            case "doImmediateRespawn" -> net.minecraft.world.level.GameRules.RULE_DO_IMMEDIATE_RESPAWN;
            case "doInsomnia" -> net.minecraft.world.level.GameRules.RULE_DOINSOMNIA;
            case "doLimitedCrafting" -> net.minecraft.world.level.GameRules.RULE_LIMITED_CRAFTING;
            case "doMobLoot" -> net.minecraft.world.level.GameRules.RULE_DOMOBLOOT;
            case "doMobSpawning" -> net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING;
            case "doTileDrops" -> net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS;
            case "doWeatherCycle" -> net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE;
            case "drowningDamage" -> net.minecraft.world.level.GameRules.RULE_DROWNING_DAMAGE;
            case "fallDamage" -> net.minecraft.world.level.GameRules.RULE_FALL_DAMAGE;
            case "fireDamage" -> net.minecraft.world.level.GameRules.RULE_FIRE_DAMAGE;
            case "freezeDamage" -> net.minecraft.world.level.GameRules.RULE_FREEZE_DAMAGE;
            case "keepInventory" -> net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY;
            case "mobGriefing" -> net.minecraft.world.level.GameRules.RULE_MOBGRIEFING;
            case "naturalRegeneration" -> net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION;
            case "sendCommandFeedback" -> net.minecraft.world.level.GameRules.RULE_SENDCOMMANDFEEDBACK;
            case "showDeathMessages" -> net.minecraft.world.level.GameRules.RULE_SHOWDEATHMESSAGES;
            case "tntExplosionDropDecay" -> net.minecraft.world.level.GameRules.RULE_TNT_EXPLOSION_DROP_DECAY;
            default -> null;
        };
    }

    // The Java rule a Bedrock rule name means, among the rules holding a count.
    private static net.minecraft.world.level.GameRules.Key<net.minecraft.world.level.GameRules.IntegerValue>
        countRule(String bedrock) {
        return switch (bedrock) {
            case "maxCommandChainLength" -> net.minecraft.world.level.GameRules.RULE_MAX_COMMAND_CHAIN_LENGTH;
            case "playersSleepingPercentage" -> net.minecraft.world.level.GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE;
            case "randomTickSpeed" -> net.minecraft.world.level.GameRules.RULE_RANDOMTICKING;
            case "spawnRadius" -> net.minecraft.world.level.GameRules.RULE_SPAWN_RADIUS;
            default -> null;
        };
    }

    // A count a pack wrote, or null when the text does not read as one.
    private static Integer whole(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }
    *///?}

    /**
     * One participant's score, or null when they have none on that objective.
     *
     * A score was keyed by a plain name until 1.20.3 and by a score holder after it, so reading one
     * is the only part of the scoreboard that differs between the supported versions.
     */
    public static Integer score(net.minecraft.world.scores.Scoreboard scoreboard,
        net.minecraft.world.scores.Objective objective, String participant) {
        //? if >=1.21 {
        net.minecraft.world.scores.ReadOnlyScoreInfo info = scoreboard.getPlayerScoreInfo(
            net.minecraft.world.scores.ScoreHolder.forNameOnly(participant), objective);
        return info == null ? null : info.value();
        //?} else {
        /*return scoreboard.getPlayerScores(participant).containsKey(objective)
            ? scoreboard.getOrCreatePlayerScore(participant, objective).getScore() : null;
        *///?}
    }

    /** Everyone the scoreboard is tracking, by name. */
    public static String[] scoreboardParticipants(net.minecraft.world.scores.Scoreboard scoreboard) {
        //? if >=1.21 {
        return scoreboard.getTrackedPlayers().stream()
            .map(net.minecraft.world.scores.ScoreHolder::getScoreboardName)
            .toArray(String[]::new);
        //?} else {
        /*return scoreboard.getTrackedPlayers().toArray(String[]::new);
        *///?}
    }

    /**
     * Whether a command source is allowed to run something at a given permission level.
     *
     * Java counted permission in integers until 26 replaced them with a set of named permissions, so
     * Bedrock's ladder is mapped onto whichever the version has: `GameDirectors` and above need more
     * than an ordinary player, and `Any` needs nothing.
     */
    public static boolean allowedAt(CommandSourceStack source, int bedrockLevel) {
        if (bedrockLevel <= 0) return true;
        //? if >=26 {
        net.minecraft.server.permissions.PermissionLevel level = switch (bedrockLevel) {
            case 1 -> net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS;
            case 2 -> net.minecraft.server.permissions.PermissionLevel.ADMINS;
            default -> net.minecraft.server.permissions.PermissionLevel.OWNERS;
        };
        return source.permissions().hasPermission(
            new net.minecraft.server.permissions.Permission.HasCommandLevel(level));
        //?} else {
        /*return source.hasPermission(Math.min(4, bedrockLevel + 1));
        *///?}
    }

    /**
     * Copies a region of the world into a structure template.
     *
     * What to leave out changed from one block to a list of them in 1.21.2; nothing is left out
     * either way, which is what Bedrock does when a pack does not say otherwise.
     */
    public static void fillFromWorld(
        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template,
        ServerLevel level, net.minecraft.core.BlockPos corner, net.minecraft.core.Vec3i size) {
        //? if >=1.21.2 {
        template.fillFromWorld(level, corner, size, false, List.of());
        //?} else {
        /*template.fillFromWorld(level, corner, size, false, (Block) null);
        *///?}
    }

    /**
     * The render type a Bedrock material asks for.
     *
     * Bedrock's entity materials disable culling by default and use an alpha test, which is Java's
     * cutout-no-cull; a material that asks for blending gets a translucent type, and one that glows
     * gets the emissive variant. 26 swapped two of the names: its `entityCutout` is the one that
     * does not cull, and `entityCutoutNoCull` is gone.
     */
    public static RenderType renderType(Identifier texture, boolean blend, boolean cull,
        boolean emissive) {
        if (emissive) return RenderTypes.entityTranslucentEmissive(texture);
        if (blend) return RenderTypes.entityTranslucent(texture);
        return cull ? RenderTypes.entityCutoutCull(texture) : RenderTypes.entityCutout(texture);
    }

    /**
     * How much of a stack's use cooldown is still to run, between nothing and one.
     *
     * 1.21.2 moved the question from the item to the stack, because a cooldown became something a
     * stack's own components can name a group for.
     */
    public static float cooldownLeft(net.minecraft.world.entity.player.Player player,
        net.minecraft.world.item.ItemStack stack) {
        //? if >=1.21.2 {
        return player.getCooldowns().getCooldownPercent(stack, 0f);
        //?} else {
        /*return player.getCooldowns().getCooldownPercent(stack.getItem(), 0f);
        *///?}
    }

    /**
     * An entity's score under one objective, or zero while it has none.
     *
     * 1.21 gave a score holder an identity of its own, where before a score was filed under the name
     * the entity goes by on the scoreboard.
     */
    public static double score(Entity entity, String objective) {
        net.minecraft.world.scores.Scoreboard scoreboard = entity.level().getScoreboard();
        net.minecraft.world.scores.Objective named = scoreboard.getObjective(objective);
        if (named == null) return 0;

        //? if >=1.21 {
        net.minecraft.world.scores.ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(entity, named);
        return score == null ? 0 : score.value();
        //?} else {
        /*String holder = entity.getScoreboardName();
        return scoreboard.hasPlayerScore(holder, named)
            ? scoreboard.getOrCreatePlayerScore(holder, named).getScore() : 0;
        *///?}
    }

    /**
     * Paints a pack's dyeable items in the colour they carry.
     *
     * From 1.21.4 the colour is a tint named in the item's own definition file, which the converter
     * writes, so nothing is registered here. 1.21 reads tints from a colour provider instead, and
     * 1.20.1 has no dyed colour on a stack at all.
     */
    public static void registerDyeTint(java.util.List<Item> items) {
        //? if >=1.21 && <26 {
        /*if (items.isEmpty()) return;

        // 0xA06540 is the leather brown a stack carrying no colour of its own falls back to.
        net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register(
            (stack, layer) -> layer == 0
                ? net.minecraft.world.item.component.DyedItemColor.getOrDefault(stack, 0xA06540)
                : -1,
            items.toArray(new Item[0]));
        *///?}
    }

    /**
     * Tells the client how to make one kind of particle.
     *
     * Fabric renamed both the registry and the sprite set it hands over in 26, and the sprite set is
     * the game's own interface in both, so only the name of the registry differs here.
     */
    public static void registerParticle(net.minecraft.core.particles.SimpleParticleType type,
        java.util.function.Function<net.minecraft.client.particle.SpriteSet,
            net.minecraft.client.particle.ParticleProvider<net.minecraft.core.particles.SimpleParticleType>>
            provider) {
        //? if >=26 {
        net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry.getInstance()
            .register(type, provider::apply);
        //?} else {
        /*net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry.getInstance()
            .register(type, provider::apply);
        *///?}
    }

    /** The registry id of an active effect. What the instance carries became a holder in 1.21. */
    public static String effectId(net.minecraft.world.effect.MobEffectInstance active) {
        //? if >=1.21 {
        return net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
            .getKey(active.getEffect().value()).toString();
        //?} else {
        /*return net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT
            .getKey(active.getEffect()).toString();
        *///?}
    }

    /**
     * A goal that follows whoever is holding food.
     *
     * Bedrock names the items in the component; Java took an ingredient until 1.21.2 and a test on
     * the stack after. Anything edible tempts, which is what a pack's own list usually amounts to.
     */
    public static net.minecraft.world.entity.ai.goal.Goal temptGoal(
        net.minecraft.world.entity.PathfinderMob mob) {
        //? if >=1.21.2 {
        return new net.minecraft.world.entity.ai.goal.TemptGoal(mob, 1.0,
            stack -> stack.has(net.minecraft.core.component.DataComponents.FOOD), false);
        //?} else {
        /*return new net.minecraft.world.entity.ai.goal.TemptGoal(mob, 1.0,
            net.minecraft.world.item.crafting.Ingredient.of(net.minecraft.world.item.Items.WHEAT), false);
        *///?}
    }

    /**
     * Ties a mob to a place it will not wander far from.
     *
     * This is what Bedrock's `minecraft:home` and `minecraft:dweller` describe, and Java has had it
     * all along under a name that changed in 26.
     */
    public static void setHome(net.minecraft.world.entity.Mob mob, net.minecraft.core.BlockPos at,
        int radius) {
        //? if >=26 {
        mob.setHomeTo(at, radius);
        //?} else {
        /*mob.restrictTo(at, radius);
        *///?}
    }

    /**
     * Multiplies how fast a mob moves, or puts it back when the multiplier is one.
     *
     * A modifier rather than a new base value, so whatever else changed the speed keeps its say.
     * 1.21 took the string name off a modifier and gave it a registry id instead.
     */
    public static void scaleSpeed(net.minecraft.world.entity.Mob mob, double multiplier) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance speed =
            mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speed == null) return;

        //? if >=1.21 {
        Identifier id = id("mcaddon", "boost");
        speed.removeModifier(id);
        if (multiplier != 1) {
            speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                id, multiplier - 1,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        //?} else {
        /*java.util.UUID id = java.util.UUID.fromString("7f3d1c90-2d9a-4a1e-9a1f-0b5f2c8d6e41");
        speed.removeModifier(id);
        if (multiplier != 1) {
            speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                id, "mcaddon boost", multiplier - 1,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.MULTIPLY_BASE));
        }
        *///?}
    }

    /** Where a mob was told it belongs, or null when it was told nothing. 26 renamed both calls. */
    public static net.minecraft.core.BlockPos home(net.minecraft.world.entity.Mob mob) {
        //? if >=26 {
        return mob.hasHome() ? mob.getHomePosition() : null;
        //?} else {
        /*return mob.hasRestriction() ? mob.getRestrictCenter() : null;
        *///?}
    }

    /** Whether an effect is one an entity would want. What the instance carries became a holder in 1.21. */
    public static boolean beneficial(net.minecraft.world.effect.MobEffectInstance active) {
        //? if >=1.21 {
        return active.getEffect().value().isBeneficial();
        //?} else {
        /*return active.getEffect().isBeneficial();
        *///?}
    }

    /**
     * An iron golem near this point that is holding a flower out, or null when none is.
     *
     * A golem offering one is what `minecraft:behavior.take_flower` waits for. The class moved into
     * its own package in 26, and the offer itself is a countdown the golem exposes either way.
     */
    public static net.minecraft.world.entity.Entity offeredFlower(
        net.minecraft.world.entity.Entity near, double range) {
        //? if >=26 {
        for (net.minecraft.world.entity.animal.golem.IronGolem golem : near.level().getEntitiesOfClass(
            net.minecraft.world.entity.animal.golem.IronGolem.class,
            near.getBoundingBox().inflate(range))) {
            if (golem.getOfferFlowerTick() > 0) return golem;
        }
        //?} else {
        /*for (net.minecraft.world.entity.animal.IronGolem golem : near.level().getEntitiesOfClass(
            net.minecraft.world.entity.animal.IronGolem.class,
            near.getBoundingBox().inflate(range))) {
            if (golem.getOfferFlowerTick() > 0) return golem;
        }
        *///?}
        return null;
    }

    /** Takes the flower back off the golem, which is what ends the offer. */
    public static void takeFlower(net.minecraft.world.entity.Entity golem) {
        //? if >=26 {
        if (golem instanceof net.minecraft.world.entity.animal.golem.IronGolem holding) {
            holding.offerFlower(false);
        }
        //?} else {
        /*if (golem instanceof net.minecraft.world.entity.animal.IronGolem holding) {
            holding.offerFlower(false);
        }
        *///?}
    }

    /** Lets go of whatever is leashed to a mob. What the call takes changed in 1.21.2. */
    public static void dropLeash(net.minecraft.world.entity.Mob mob) {
        //? if >=1.21.2 {
        mob.dropLeash();
        //?} else {
        /*mob.dropLeash(true, true);
        *///?}
    }

    /** Whether a raid at this point has just been won, which is what a celebration follows. */
    public static boolean raidWon(ServerLevel level, net.minecraft.core.BlockPos at) {
        net.minecraft.world.entity.raid.Raid raid = level.getRaidAt(at);
        return raid != null && raid.isVictory();
    }

    /** Where a raid still being fought around this point is centred, or null when none is. */
    public static net.minecraft.core.BlockPos raidCentre(ServerLevel level, net.minecraft.core.BlockPos at) {
        net.minecraft.world.entity.raid.Raid raid = level.getRaidAt(at);
        return raid == null || !raid.isActive() || raid.isVictory() ? null : raid.getCenter();
    }

    /**
     * Which switch in `minecraft:game_event_movement_tracking` decides whether this event is raised.
     *
     * Null for every other event, which the component says nothing about. The game event itself
     * became a holder in 1.21, which is the whole difference between the two forms.
     */
    //? if >=1.21 {
    public static String movementEvent(
        net.minecraft.core.Holder<net.minecraft.world.level.gameevent.GameEvent> event) {
        net.minecraft.world.level.gameevent.GameEvent raised = event.value();
        if (raised == net.minecraft.world.level.gameevent.GameEvent.STEP.value()) return "emit_move";
        if (raised == net.minecraft.world.level.gameevent.GameEvent.SWIM.value()) return "emit_swim";
        return raised == net.minecraft.world.level.gameevent.GameEvent.FLAP.value() ? "emit_flap" : null;
    }
    //?} else {
    /*public static String movementEvent(net.minecraft.world.level.gameevent.GameEvent event) {
        if (event == net.minecraft.world.level.gameevent.GameEvent.STEP) return "emit_move";
        if (event == net.minecraft.world.level.gameevent.GameEvent.SWIM) return "emit_swim";
        return event == net.minecraft.world.level.gameevent.GameEvent.FLAP ? "emit_flap" : null;
    }
    *///?}

    /**
     * The reason a chunk stays loaded and ticking while this mod is holding it.
     *
     * Deliberately not the one `/forceload` uses: that one is written into the saved world, so a
     * server that stopped while a chunk was held would keep it loaded for good, with nothing left in
     * the game able to say why. This one is forgotten when the server stops.
     */
    //? if >=26 {
    private static final net.minecraft.server.level.TicketType HELD =
        new net.minecraft.server.level.TicketType(net.minecraft.server.level.TicketType.NO_TIMEOUT,
            net.minecraft.server.level.TicketType.FLAG_LOADING
                | net.minecraft.server.level.TicketType.FLAG_SIMULATION);
    //?} else {
    /*private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos>
        HELD = net.minecraft.server.level.TicketType.create("mcaddon_held",
            java.util.Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
    *///?}

    /** Chunks a ticket covers around the one it names, matching what a forced chunk gets. */
    private static final int HELD_RADIUS = 2;

    /** Keeps one chunk loaded and ticking until it is let go of again. */
    public static void holdChunk(ServerLevel level, net.minecraft.world.level.ChunkPos at) {
        //? if >=26 {
        level.getChunkSource().addTicketWithRadius(HELD, at, HELD_RADIUS);
        //?} else {
        /*level.getChunkSource().addRegionTicket(HELD, at, HELD_RADIUS, at);
        *///?}
    }

    /** Lets go of a held chunk, which loads and ticks on its own terms again. */
    public static void releaseChunk(ServerLevel level, net.minecraft.world.level.ChunkPos at) {
        //? if >=26 {
        level.getChunkSource().removeTicketWithRadius(HELD, at, HELD_RADIUS);
        //?} else {
        /*level.getChunkSource().removeRegionTicket(HELD, at, HELD_RADIUS, at);
        *///?}
    }

    /**
     * Pushes a button through the block itself, so everything watching a button hears about it.
     *
     * Setting the powered state by hand would light the button and nothing else: the sound, the
     * neighbour update and the scheduled pop back out all live in here. 1.21 added the pusher.
     */
    public static void pressButton(net.minecraft.world.level.block.ButtonBlock button,
        net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level,
        net.minecraft.core.BlockPos at, net.minecraft.world.entity.player.Player pusher) {
        //? if >=1.21 {
        button.press(state, level, at, pusher);
        //?} else {
        /*button.press(state, level, at);
        *///?}
    }

    /** Flips a lever the same way, which 1.21 also gave a puller and a void return. */
    public static void pullLever(net.minecraft.world.level.block.LeverBlock lever,
        net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level,
        net.minecraft.core.BlockPos at, net.minecraft.world.entity.player.Player puller) {
        //? if >=1.21 {
        lever.pull(state, level, at, puller);
        //?} else {
        /*lever.pull(state, level, at);
        *///?}
    }

    /** Opens a screen, or closes whatever is open when given null. Renamed in 26. */
    public static void setScreen(net.minecraft.client.gui.screens.Screen screen) {
        //? if >=26 {
        net.minecraft.client.Minecraft.getInstance().setScreenAndShow(screen);
        //?} else {
        /*net.minecraft.client.Minecraft.getInstance().setScreen(screen);
        *///?}
    }

    /**
     * Where the player is looking from.
     *
     * Both accessors lost their `get` prefix on the way to 26, but not in the same release: the
     * camera's own did first and the renderer's followed in 26.2.
     */
    public static net.minecraft.world.phys.Vec3 cameraPosition() {
        //? if >=26.2 {
        return net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position();
        //?} elif >=26 {
        /*return net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().position();
        *///?} else {
        /*return net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        *///?}
    }

    /** The resource pack format this version reads, taken from the game rather than a table. */
    public static int resourcePackFormat() {
        //? if >=26 {
        return net.minecraft.SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
        //?} else {
        /*return net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES);
        *///?}
    }

    /**
     * Sets the weather outright and how long it lasts, in ticks.
     *
     * The level held the four numbers itself until 26 moved them into saved data of their own.
     */
    public static void setWeather(ServerLevel level, boolean raining, boolean thundering, int duration) {
        //? if >=26 {
        net.minecraft.world.level.saveddata.WeatherData weather = level.getWeatherData();
        weather.setRaining(raining);
        weather.setThundering(thundering);
        weather.setRainTime(raining ? duration : 0);
        weather.setThunderTime(thundering ? duration : 0);
        weather.setClearWeatherTime(raining ? 0 : duration);
        //?} else {
        /*level.setWeatherParameters(raining ? 0 : duration, duration, raining, thundering);
        *///?}
    }

    /** The buildable height range, lowest block and one past the highest. */
    public static int[] heightRange(ServerLevel level) {
        //? if >=26 {
        return new int[] {level.getMinY(), level.getMaxY() + 1};
        //?} else {
        /*return new int[] {level.getMinBuildHeight(), level.getMaxBuildHeight()};
        *///?}
    }

    /** Who a tamed animal belongs to. What it stores became an entity reference in 26. */
    public static UUID ownerOf(net.minecraft.world.entity.TamableAnimal animal) {
        //? if >=26 {
        return animal.getOwnerReference() == null ? null : animal.getOwnerReference().getUUID();
        //?} else {
        /*return animal.getOwnerUUID();
        *///?}
    }

    /**
     * Whether a creaking still has the heart that put it there, and null where the game has neither.
     *
     * The creaking and its heart arrived in 1.21.4, so the two older versions have no entity this
     * can be asked about and a pack asking is told the answer is not available rather than no.
     */
    public static Boolean boundToCreakingHeart(Entity entity) {
        //? if >=1.21.4 {
        return entity instanceof net.minecraft.world.entity.monster.creaking.Creaking creaking
            ? creaking.isHeartBound() : null;
        //?} else {
        /*return null;
        *///?}
    }

    /** Where a mob is kept near, or null when it is kept nowhere. A restriction until 26. */
    public static net.minecraft.core.BlockPos homeOf(net.minecraft.world.entity.Mob mob) {
        //? if >=26 {
        return mob.hasHome() ? mob.getHomePosition() : null;
        //?} else {
        /*return mob.hasRestriction() ? mob.getRestrictCenter() : null;
        *///?}
    }

    /** The enchantments on a stack, by name. They became data-driven holders in 1.21. */
    public static Set<String> enchantmentsOf(net.minecraft.world.item.ItemStack stack) {
        //? if >=1.21 {
        return EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet().stream()
            .map(holder -> holder.unwrapKey().map(key -> idOf(key).getPath()).orElse(""))
            .collect(Collectors.toSet());
        //?} else {
        /*return EnchantmentHelper.getEnchantments(stack).keySet().stream()
            .map(BuiltInRegistries.ENCHANTMENT::getKey)
            .filter(java.util.Objects::nonNull)
            .map(Identifier::getPath)
            .collect(Collectors.toSet());
        *///?}
    }

    /**
     * The key a loot table is asked for by, which became a registry key in 1.21.
     *
     * 1.20.1 names a table by its id alone, so there is nothing for it to be turned into there.
     */
    //? if >=1.21 {
    public static ResourceKey<net.minecraft.world.level.storage.loot.LootTable>
        lootTableKey(Identifier table) {
        return ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, table);
    }
    //?}

    /** The stand-in player a game test acts through. 1.21 made it say which mode it is playing in. */
    public static net.minecraft.world.entity.player.Player mockPlayer(
        net.minecraft.gametest.framework.GameTestHelper helper) {
        //? if >=1.21 {
        return helper.makeMockPlayer(GameType.SURVIVAL);
        //?} else {
        /*return helper.makeMockPlayer();
        *///?}
    }

    /**
     * Whether mobs are allowed to change the world around them.
     *
     * A Bedrock behaviour that takes or places a block says whether it obeys this rule, so a goal
     * doing either has to ask. 26 moved the rules into their own package and typed them by value.
     */
    public static boolean mobGriefing(ServerLevel level) {
        //? if >=26 {
        return level.getGameRules().get(GameRules.MOB_GRIEFING);
        //?} else {
        /*return level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
        *///?}
    }

    /**
     * What a loot table hands out, rolled without anything having to die for it.
     *
     * Bedrock names a table on a behaviour as readily as on a death, which is how a sneeze drops an
     * item and a swallowed mob leaves one behind. The table is still an entity's, so it is rolled
     * with what an entity table asks for: who it is about, where it stands and what hurt it. 1.21
     * moved the tables out of their own manager and into the reloadable registries, and started
     * naming them by registry key.
     */
    public static java.util.List<net.minecraft.world.item.ItemStack> rollLoot(ServerLevel level,
        Identifier table, Entity about) {
        //? if >=1.21 {
        net.minecraft.world.level.storage.loot.LootTable found =
            level.getServer().reloadableRegistries().getLootTable(lootTableKey(table));
        //?} else {
        /*net.minecraft.world.level.storage.loot.LootTable found =
            level.getServer().getLootData().getLootTable(table);
        *///?}
        net.minecraft.world.level.storage.loot.LootParams asked =
            new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                .withParameter(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParams.THIS_ENTITY, about)
                .withParameter(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParams.ORIGIN, about.position())
                .withParameter(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
                .create(net.minecraft.world.level.storage.loot.parameters
                    .LootContextParamSets.ENTITY);
        return java.util.List.copyOf(found.getRandomItems(asked));
    }

    /** A boss bar the server draws. 26 gave it the id every other bar already carried. */
    public static net.minecraft.server.level.ServerBossEvent bossBar(Component name) {
        //? if >=26 {
        return new net.minecraft.server.level.ServerBossEvent(UUID.randomUUID(), name,
            net.minecraft.world.BossEvent.BossBarColor.PURPLE,
            net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
        //?} else {
        /*return new net.minecraft.server.level.ServerBossEvent(name,
            net.minecraft.world.BossEvent.BossBarColor.PURPLE,
            net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS);
        *///?}
    }

    /** Whether bone meal would do anything here. 1.21 dropped the flag naming which side is asking. */
    public static boolean canBonemeal(net.minecraft.world.level.block.BonemealableBlock crop,
        ServerLevel level, net.minecraft.core.BlockPos at,
        net.minecraft.world.level.block.state.BlockState state) {
        //? if >=1.21 {
        return crop.isValidBonemealTarget(level, at, state);
        //?} else {
        /*return crop.isValidBonemealTarget(level, at, state, false);
        *///?}
    }

    /**
     * How far a wolf is through shaking the water off, which runs from nothing to two.
     *
     * 26 answers the count itself. The older versions keep the count private and offer only the
     * shade the wolf is drawn at, which is that count and nothing else, so reading the shade
     * backwards gives the count up to the moment it reaches full.
     */
    public static double wolfShakeAngle(Wolf wolf, float partialTick) {
        //? if >=26 {
        return wolf.getShakeAnim(partialTick);
        //?} elif >=1.21 {
        /*return (wolf.getWetShade(partialTick) - 0.75) * 8;
        *///?} else {
        /*return (wolf.getWetShade(partialTick) - 0.5) * 4;
        *///?}
    }

    /**
     * The atlas sprite a block's own particles are cut from.
     *
     * 26 moved the question from the block renderer to the model manager, and answers it with the
     * material the sprite belongs to rather than with the sprite.
     */
    public static net.minecraft.client.renderer.texture.TextureAtlasSprite blockParticleSprite(
        net.minecraft.world.level.block.state.BlockState state) {
        //? if >=26 {
        return net.minecraft.client.Minecraft.getInstance().getModelManager()
            .getBlockStateModelSet().getParticleMaterial(state).sprite();
        //?} else {
        /*return net.minecraft.client.Minecraft.getInstance().getBlockRenderer()
            .getBlockModelShaper().getParticleIcon(state);
        *///?}
    }

    /**
     * Whether the entity is a lingering effect rather than one that lands and is gone.
     *
     * 26 gave the two kinds of thrown potion a class each. Before that one class carried both, and
     * the item it was thrown as is what tells them apart.
     */
    public static boolean isLingering(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.AreaEffectCloud) return true;

        //? if >=26 {
        return entity instanceof net.minecraft.world.entity.projectile.throwableitemprojectile
            .ThrownLingeringPotion;
        //?} else {
        /*return entity instanceof net.minecraft.world.entity.projectile.ThrownPotion potion
            && potion.getItem().is(net.minecraft.world.item.Items.LINGERING_POTION);
        *///?}
    }

    /**
     * How many swings a second the held item allows, before anything acting on whoever holds it.
     *
     * A weapon states its speed as an attribute modifier on the stack, which 1.21 moved from a
     * multimap the item answers with into a component the stack carries.
     */
    public static double baseAttackSpeed(LivingEntity living, net.minecraft.world.item.ItemStack held) {
        double speed = living.getAttributeBaseValue(
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);

        //? if >=1.21 {
        return speed + held.getOrDefault(
                net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY)
            .modifiers().stream()
            .filter(entry -> entry.attribute()
                .equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)
                && entry.slot().test(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                && entry.modifier().operation()
                    == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE)
            .mapToDouble(entry -> entry.modifier().amount())
            .sum();
        //?} else {
        /*return speed + held
            .getAttributeModifiers(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
            .get(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED).stream()
            .filter(modifier -> modifier.getOperation()
                == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADDITION)
            .mapToDouble(net.minecraft.world.entity.ai.attributes.AttributeModifier::getAmount)
            .sum();
        *///?}
    }

    /** The colour an effect paints its swirls, which 1.21 reached through a holder. */
    public static int effectColour(MobEffectInstance active) {
        //? if >=1.21 {
        return active.getEffect().value().getColor();
        //?} else {
        /*return active.getEffect().getColor();
        *///?}
    }

    /**
     * How long the cooldown a use puts the item under lasts, in seconds, or nothing while it has none.
     *
     * 26 carries the length on the stack, which is where the game's own items keep theirs. The older
     * versions hold a cooldown while it runs and nowhere say how long it was.
     */
    public static float cooldownSeconds(net.minecraft.world.item.ItemStack stack) {
        //? if >=26 {
        net.minecraft.world.item.component.UseCooldown cooldown =
            stack.get(net.minecraft.core.component.DataComponents.USE_COOLDOWN);
        return cooldown == null ? 0f : cooldown.seconds();
        //?} else {
        /*return 0f;
        *///?}
    }
}
