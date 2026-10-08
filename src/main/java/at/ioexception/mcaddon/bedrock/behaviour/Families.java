package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.registry.AddonEntities;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.Set;

/**
 * The type families an entity belongs to, which is what `is_family` and every `entity_types` list
 * a pack writes are asked against.
 *
 * A pack's own entity carries the families its definition declares. The game's own entities carry
 * the ones Mojang's behaviour pack declares for them, keyed by the Bedrock identifier, because
 * nothing on the Java side names a family: a pack's zombie hunting `villager` finds Java's
 * villager only through this table. Where Mojang states the family inside component groups alone,
 * the families every one of those groups shares are the ones listed.
 */
public final class Families {
    private static final Map<String, Set<String>> VANILLA = Map.ofEntries(
        Map.entry("minecraft:allay", Set.of("allay", "mob")),
        Map.entry("minecraft:armadillo", Set.of("armadillo", "mob")),
        Map.entry("minecraft:armor_stand", Set.of("armor_stand", "inanimate", "mob")),
        Map.entry("minecraft:arrow", Set.of("projectile", "arrow")),
        Map.entry("minecraft:axolotl", Set.of("aquatic", "axolotl", "mob")),
        Map.entry("minecraft:bat", Set.of("bat", "mob")),
        Map.entry("minecraft:bee", Set.of("bee", "mob", "arthropod")),
        Map.entry("minecraft:blaze", Set.of("blaze", "monster", "mob")),
        Map.entry("minecraft:boat", Set.of("boat", "inanimate")),
        Map.entry("minecraft:bogged", Set.of("bogged", "skeleton", "monster", "mob", "undead")),
        Map.entry("minecraft:breeze", Set.of("breeze", "monster", "mob")),
        Map.entry("minecraft:breeze_wind_charge_projectile", Set.of(
            "projectile", "wind_charge", "wind_charge_projectile", "breeze_wind_charge_projectile")),
        Map.entry("minecraft:camel", Set.of("camel", "mob")),
        Map.entry("minecraft:camel_husk", Set.of("camel_husk", "mob", "undead")),
        Map.entry("minecraft:cat", Set.of("cat", "mob")),
        Map.entry("minecraft:cave_spider", Set.of("cavespider", "monster", "arthropod", "mob")),
        Map.entry("minecraft:chest_boat", Set.of("boat", "inanimate")),
        Map.entry("minecraft:chest_minecart", Set.of("minecart", "inanimate")),
        Map.entry("minecraft:chicken", Set.of("chicken", "mob")),
        Map.entry("minecraft:command_block_minecart", Set.of("minecart", "inanimate")),
        Map.entry("minecraft:copper_golem", Set.of("copper_golem", "mob")),
        Map.entry("minecraft:cow", Set.of("cow", "mob")),
        Map.entry("minecraft:creaking", Set.of("creaking", "monster", "mob")),
        Map.entry("minecraft:creeper", Set.of("creeper", "monster", "mob")),
        Map.entry("minecraft:cushion", Set.of("cushion", "inanimate", "actor")),
        Map.entry("minecraft:dolphin", Set.of("aquatic", "dolphin", "mob")),
        Map.entry("minecraft:donkey", Set.of("donkey", "mob")),
        Map.entry("minecraft:dragon_fireball", Set.of("projectile", "dragon_fireball")),
        Map.entry("minecraft:drowned", Set.of("drowned", "zombie", "undead", "monster", "mob")),
        Map.entry("minecraft:egg", Set.of("projectile", "egg")),
        Map.entry("minecraft:elder_guardian", Set.of("aquatic", "guardian_elder", "monster", "mob")),
        Map.entry("minecraft:ender_dragon", Set.of("dragon", "mob")),
        Map.entry("minecraft:ender_pearl", Set.of("projectile", "ender_pearl")),
        Map.entry("minecraft:enderman", Set.of("enderman", "monster", "mob")),
        Map.entry("minecraft:endermite", Set.of("endermite", "arthropod", "monster", "mob")),
        Map.entry("minecraft:evocation_illager", Set.of("evocation_illager", "monster", "illager", "mob")),
        Map.entry("minecraft:fireball", Set.of("projectile", "fireball")),
        Map.entry("minecraft:fireworks_rocket", Set.of("projectile", "fireworks_rocket")),
        Map.entry("minecraft:cod", Set.of("aquatic", "cod", "fish")),
        Map.entry("minecraft:fishing_hook", Set.of("projectile", "fishing_hook")),
        Map.entry("minecraft:fox", Set.of("fox", "mob")),
        Map.entry("minecraft:frog", Set.of("frog", "mob")),
        Map.entry("minecraft:ghast", Set.of("ghast", "monster", "mob")),
        Map.entry("minecraft:glow_squid", Set.of("aquatic", "squid", "mob")),
        Map.entry("minecraft:goat", Set.of("goat", "animal")),
        Map.entry("minecraft:guardian", Set.of("aquatic", "guardian", "monster", "mob")),
        Map.entry("minecraft:happy_ghast", Set.of("happy_ghast", "mob")),
        Map.entry("minecraft:hoglin", Set.of("hoglin", "mob")),
        Map.entry("minecraft:hopper_minecart", Set.of("minecart", "inanimate")),
        Map.entry("minecraft:horse", Set.of("horse", "mob")),
        Map.entry("minecraft:husk", Set.of("husk", "zombie", "undead", "monster", "mob")),
        Map.entry("minecraft:iron_golem", Set.of("irongolem", "mob")),
        Map.entry("minecraft:lightning_bolt", Set.of("lightning")),
        Map.entry("minecraft:lingering_potion", Set.of("projectile", "lingering_potion")),
        Map.entry("minecraft:llama", Set.of("llama", "mob")),
        Map.entry("minecraft:llama_spit", Set.of("projectile", "llama_spit")),
        Map.entry("minecraft:magma_cube", Set.of("magmacube", "monster", "mob")),
        Map.entry("minecraft:minecart", Set.of("minecart", "inanimate")),
        Map.entry("minecraft:mooshroom", Set.of("mushroomcow", "mob")),
        Map.entry("minecraft:mule", Set.of("mule", "mob")),
        Map.entry("minecraft:nautilus", Set.of("aquatic", "nautilus", "mob")),
        Map.entry("minecraft:npc", Set.of("npc", "mob")),
        Map.entry("minecraft:ocelot", Set.of("ocelot", "mob")),
        Map.entry("minecraft:panda", Set.of("panda")),
        Map.entry("minecraft:parched", Set.of("parched", "skeleton", "monster", "mob", "undead")),
        Map.entry("minecraft:parrot", Set.of("mob")),
        Map.entry("minecraft:phantom", Set.of("phantom", "undead", "monster", "mob")),
        Map.entry("minecraft:pig", Set.of("pig", "mob")),
        Map.entry("minecraft:piglin", Set.of("piglin", "monster", "mob")),
        Map.entry("minecraft:piglin_brute", Set.of("piglin", "adult_piglin", "piglin_brute", "monster")),
        Map.entry("minecraft:pillager", Set.of("pillager", "monster", "illager", "mob")),
        Map.entry("minecraft:player", Set.of("player")),
        Map.entry("minecraft:polar_bear", Set.of("polarbear", "mob")),
        Map.entry("minecraft:pufferfish", Set.of("aquatic", "pufferfish", "fish")),
        Map.entry("minecraft:rabbit", Set.of("rabbit", "mob")),
        Map.entry("minecraft:ravager", Set.of("monster", "ravager", "mob")),
        Map.entry("minecraft:salmon", Set.of("aquatic", "salmon", "fish")),
        Map.entry("minecraft:sheep", Set.of("sheep", "mob")),
        Map.entry("minecraft:shulker", Set.of("shulker", "monster", "mob")),
        Map.entry("minecraft:shulker_bullet", Set.of("projectile", "shulker_bullet")),
        Map.entry("minecraft:silverfish", Set.of("silverfish", "monster", "mob", "arthropod")),
        Map.entry("minecraft:skeleton", Set.of("skeleton", "undead", "monster", "mob")),
        Map.entry("minecraft:skeleton_horse", Set.of("skeletonhorse", "undead", "mob")),
        Map.entry("minecraft:slime", Set.of("slime", "monster", "mob")),
        Map.entry("minecraft:small_fireball", Set.of("projectile", "small_fireball")),
        Map.entry("minecraft:sniffer", Set.of("sniffer", "mob")),
        Map.entry("minecraft:snow_golem", Set.of("snowgolem", "mob")),
        Map.entry("minecraft:snowball", Set.of("projectile", "snowball")),
        Map.entry("minecraft:spider", Set.of("spider", "monster", "mob", "arthropod")),
        Map.entry("minecraft:splash_potion", Set.of("projectile", "splash_potion")),
        Map.entry("minecraft:squid", Set.of("aquatic", "squid", "mob")),
        Map.entry("minecraft:stray", Set.of("stray", "skeleton", "monster", "mob", "undead")),
        Map.entry("minecraft:strider", Set.of("strider", "mob")),
        Map.entry("minecraft:sulfur_cube", Set.of("sulfur_cube", "animal", "mob")),
        Map.entry("minecraft:tadpole", Set.of("aquatic", "tadpole", "mob")),
        Map.entry("minecraft:thrown_trident", Set.of("projectile", "thrown_trident")),
        Map.entry("minecraft:tnt", Set.of("tnt", "inanimate")),
        Map.entry("minecraft:tnt_minecart", Set.of("minecart", "inanimate")),
        Map.entry("minecraft:trader_llama", Set.of("trader_llama", "llama", "mob")),
        Map.entry("minecraft:tripod_camera", Set.of("tripodcamera", "inanimate", "mob")),
        Map.entry("minecraft:tropicalfish", Set.of("aquatic", "tropicalfish", "fish")),
        Map.entry("minecraft:turtle", Set.of("aquatic", "turtle", "mob")),
        Map.entry("minecraft:vex", Set.of("vex", "monster", "mob")),
        Map.entry("minecraft:villager", Set.of("villager", "mob")),
        Map.entry("minecraft:villager_v2", Set.of("villager", "mob")),
        Map.entry("minecraft:vindicator", Set.of("vindicator", "monster", "illager", "mob")),
        Map.entry("minecraft:wandering_trader", Set.of("wandering_trader", "mob")),
        Map.entry("minecraft:warden", Set.of("warden", "monster", "mob")),
        Map.entry("minecraft:wind_charge_projectile", Set.of(
            "projectile", "wind_charge", "wind_charge_projectile")),
        Map.entry("minecraft:witch", Set.of("witch", "monster", "mob")),
        Map.entry("minecraft:wither", Set.of("wither", "skeleton", "monster", "undead", "mob")),
        Map.entry("minecraft:wither_skeleton", Set.of("wither", "monster", "undead", "skeleton", "mob")),
        Map.entry("minecraft:wither_skull", Set.of("projectile", "wither_skull")),
        Map.entry("minecraft:wither_skull_dangerous", Set.of("projectile", "wither_skull_dangerous")),
        Map.entry("minecraft:wolf", Set.of("wolf", "mob")),
        Map.entry("minecraft:xp_bottle", Set.of("projectile", "xp_bottle")),
        Map.entry("minecraft:xp_orb", Set.of("inanimate")),
        Map.entry("minecraft:zoglin", Set.of("zoglin", "undead", "monster", "mob")),
        Map.entry("minecraft:zombie", Set.of("zombie", "undead", "monster", "mob")),
        Map.entry("minecraft:zombie_horse", Set.of("zombiehorse", "undead", "mob")),
        Map.entry("minecraft:zombie_nautilus", Set.of(
            "aquatic", "zombie_nautilus", "undead", "monster", "mob")),
        Map.entry("minecraft:zombie_pigman", Set.of("undead", "monster", "mob")),
        Map.entry("minecraft:zombie_villager", Set.of(
            "zombie_villager", "zombie", "undead", "monster", "mob")),
        Map.entry("minecraft:zombie_villager_v2", Set.of(
            "zombie_villager", "zombie", "undead", "monster", "mob")));

    private Families() {
    }

    public static Set<String> of(Entity entity) {
        ServerEntityDefinition own = AddonEntities.definitionOf(entity.getType());
        if (own != null) return own.families();

        return VANILLA.getOrDefault(AddonEntities.identifierOf(entity.getType()), Set.of());
    }
}
