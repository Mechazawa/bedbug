// The @minecraft/vanilla-data surface.
//
// A pack imports these tables to name a vanilla block, item or entity without writing the string
// out. They are read from the game's own registries the first time the module is imported, so each
// one holds what this version of the game ships and no list has to be kept in step by hand. Only
// `minecraft:` ids are taken: a pack's own blocks and items belong to that pack.
//
// A key is the id with its namespace dropped and each underscore turned into a capital, which is
// how the Bedrock package spells it, and the value is that id. Where Bedrock and Java name the same
// block, item or entity differently, both are here, `GoldenRail` beside `PoweredRail` and
// `VillagerV2` beside `Villager`, because the surface accepts either spelling and reports the
// Bedrock one.

const bridge = globalThis.__mcaddon_bridge__;

const registries = JSON.parse(bridge.registries.vanillaTypes());

function key(id) {
    return id.slice(id.indexOf(":") + 1).split("_")
        .map(word => word.charAt(0).toUpperCase() + word.slice(1))
        .join("");
}

function table(ids) {
    return Object.freeze(Object.fromEntries(ids.map(id => [key(id), id])));
}

export const MinecraftBlockTypes = table(registries.blocks);
export const MinecraftItemTypes = table(registries.items);
export const MinecraftEntityTypes = table(registries.entities);
export const MinecraftEffectTypes = table(registries.effects);
export const MinecraftEnchantmentTypes = table(registries.enchantments);
export const MinecraftBiomeTypes = table(registries.biomes);
export const MinecraftPotionEffectTypes = table(registries.potionEffects);
export const MinecraftFeatureTypes = table(registries.features);

const dimensions = table(registries.dimensions);

// Bedrock's key for the nether is `Nether` where this game's id for it is `the_nether`, so the
// worked-out key and the one a pack writes both reach the same dimension.
export const MinecraftDimensionTypes = Object.freeze({ Nether: dimensions.TheNether, ...dimensions });

// The three tables with no registry behind them. Bedrock keeps each as a fixed set rather than as
// data, so there is nothing to read them from and nothing about them that moves between versions.
export const MinecraftPotionDeliveryTypes = Object.freeze({
    Lingering: "lingering",
    Splash: "splash",
});

export const MinecraftPotionLiquidTypes = Object.freeze({
    Lingering: "Lingering",
    Regular: "Regular",
    Splash: "Splash",
});

export const MinecraftPotionModifierTypes = Object.freeze({
    Long: "Long",
    Normal: "Normal",
    Strong: "Strong",
});

export const MinecraftCooldownCategoryTypes = Object.freeze({
    Chorusfruit: "minecraft:chorusfruit",
    EnderPearl: "minecraft:ender_pearl",
    GoatHorn: "minecraft:goat_horn",
    Shield: "minecraft:shield",
    Spear: "minecraft:spear",
    WindCharge: "minecraft:wind_charge",
});

// A camera preset names a view Java's renderer has no form of. The ids are here so a pack that
// imports one links, and the `camera` command it would pass them to reports itself unimplemented.
export const MinecraftCameraPresetsTypes = Object.freeze({
    ControlSchemeCamera: "minecraft:control_scheme_camera",
    FirstPerson: "minecraft:first_person",
    FixedBoom: "minecraft:fixed_boom",
    FollowOrbit: "minecraft:follow_orbit",
    Free: "minecraft:free",
    ThirdPerson: "minecraft:third_person",
    ThirdPersonFront: "minecraft:third_person_front",
});
