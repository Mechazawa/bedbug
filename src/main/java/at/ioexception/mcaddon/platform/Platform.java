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
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
        Pack.Metadata metadata = new Pack.Metadata(name, PackCompatibility.COMPATIBLE,
            FeatureFlagSet.of(), List.of());
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
     */
    public static Item buildWearable(Item.Properties properties, Identifier id,
        net.minecraft.world.entity.EquipmentSlot slot) {
        //? if >=1.21.2 {
        return buildItem(properties.component(net.minecraft.core.component.DataComponents.EQUIPPABLE,
            net.minecraft.world.item.equipment.Equippable.builder(slot).build()), id);
        //?} else {
        /*net.minecraft.world.item.ArmorItem.Type type = armourType(slot);
        if (type == null) return buildItem(properties, id);

        return new net.minecraft.world.item.ArmorItem(costume(), type, properties.stacksTo(1));
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
     * Whether this version uses the newer data pack layout.
     *
     * 1.21.2 renamed the directories from `recipes` and `loot_tables` to their singular forms, made
     * a recipe ingredient a bare item id rather than an object, and renamed a result's `item` field
     * to `id`.
     */
    public static boolean modernDataPaths() {
        //? if >=1.21.2 {
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /**
     * A game rule, by the name Bedrock calls it, as text.
     *
     * The three supported versions disagree about everything here: the rule constants were renamed
     * in 26 and the map they live in was rewritten, so the mapping is written out per version rather
     * than abstracted. Null for a rule Java does not have, such as whether coordinates are shown.
     */
    public static String gameRule(net.minecraft.server.MinecraftServer server, String bedrock) {
        //? if >=26 {
        net.minecraft.world.level.gamerules.GameRules rules = server.getGameRules();
        return switch (bedrock) {
            case "doDayLightCycle" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME));
            case "doWeatherCycle" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER));
            case "keepInventory" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY));
            case "mobGriefing" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING));
            case "naturalRegeneration" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.NATURAL_HEALTH_REGENERATION));
            case "sendCommandFeedback" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.SEND_COMMAND_FEEDBACK));
            case "showDeathMessages" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.SHOW_DEATH_MESSAGES));
            case "doImmediateRespawn" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.IMMEDIATE_RESPAWN));
            case "doEntityDrops" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.ENTITY_DROPS));
            case "doTileDrops" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.BLOCK_DROPS));
            case "commandBlockOutput" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.COMMAND_BLOCK_OUTPUT));
            case "randomTickSpeed" -> String.valueOf(rules.get(
                net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED));
            default -> null;
        };
        //?} else {
        /*net.minecraft.world.level.GameRules rules = server.getGameRules();
        return switch (bedrock) {
            case "doDayLightCycle" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_DAYLIGHT));
            case "doWeatherCycle" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE));
            case "keepInventory" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY));
            case "mobGriefing" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_MOBGRIEFING));
            case "naturalRegeneration" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION));
            case "sendCommandFeedback" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_SENDCOMMANDFEEDBACK));
            case "showDeathMessages" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_SHOWDEATHMESSAGES));
            case "doImmediateRespawn" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_DO_IMMEDIATE_RESPAWN));
            case "doEntityDrops" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_DOENTITYDROPS));
            case "doTileDrops" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS));
            case "commandBlockOutput" -> String.valueOf(rules.getBoolean(
                net.minecraft.world.level.GameRules.RULE_COMMANDBLOCKOUTPUT));
            case "randomTickSpeed" -> String.valueOf(rules.getInt(
                net.minecraft.world.level.GameRules.RULE_RANDOMTICKING));
            default -> null;
        };
        *///?}
    }

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
     * A goal that follows whoever is holding what the pack named.
     *
     * Bedrock names the items in the component and Java asks a different question in each version:
     * an ingredient until 1.21.2 and a test on the stack after. A pack naming nothing is tempted by
     * anything edible, which is what such a list amounts to.
     */
    public static net.minecraft.world.entity.ai.goal.Goal temptGoal(
        net.minecraft.world.entity.PathfinderMob mob, double speed, Set<String> items) {
        //? if >=1.21.2 {
        return new net.minecraft.world.entity.ai.goal.TemptGoal(mob, speed,
            stack -> items.isEmpty()
                ? stack.has(net.minecraft.core.component.DataComponents.FOOD)
                : tempts(items, stack), false);
        //?} else {
        /*return new net.minecraft.world.entity.ai.goal.TemptGoal(mob, speed,
            ingredient(items), false);
        *///?}
    }

    /** Whether a stack is one of the items a pack's goal named, under either spelling of the id. */
    public static boolean tempts(Set<String> items, net.minecraft.world.item.ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return items.stream().anyMatch(name -> name.equals(id) || ("minecraft:" + name).equals(id));
    }

    //? if <1.21.2 {
    /*private static net.minecraft.world.item.crafting.Ingredient ingredient(Set<String> items) {
        net.minecraft.world.item.ItemStack[] stacks = items.stream()
            .map(name -> Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name))
            .filter(java.util.Objects::nonNull)
            .flatMap(id -> BuiltInRegistries.ITEM.getOptional(id).stream())
            .map(net.minecraft.world.item.ItemStack::new)
            .toArray(net.minecraft.world.item.ItemStack[]::new);
        return stacks.length == 0
            ? net.minecraft.world.item.crafting.Ingredient.of(net.minecraft.world.item.Items.WHEAT)
            : net.minecraft.world.item.crafting.Ingredient.of(stacks);
    }
    *///?}

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

    /** Whether an effect is one an entity would want. What the instance carries became a holder in 1.21. */
    public static boolean beneficial(net.minecraft.world.effect.MobEffectInstance active) {
        //? if >=1.21 {
        return active.getEffect().value().isBeneficial();
        //?} else {
        /*return active.getEffect().isBeneficial();
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

    /** The buildable height range, lowest block and one past the highest. */
    public static int[] heightRange(ServerLevel level) {
        //? if >=26 {
        return new int[] {level.getMinY(), level.getMaxY() + 1};
        //?} else {
        /*return new int[] {level.getMinBuildHeight(), level.getMaxBuildHeight()};
        *///?}
    }
}
