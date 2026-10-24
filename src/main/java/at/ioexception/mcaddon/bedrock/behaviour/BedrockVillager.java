package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A Java villager as Bedrock's `villager_v2` states itself: its profession as `minecraft:variant`, the
 * biome it comes from as `minecraft:mark_variant`, its face as `minecraft:skin_id` and its level as
 * `query.trade_tier`.
 *
 * A pack replacing the game's villagers reads all three off it, and Java holds a profession and a
 * type by name rather than by number. Each list is in the order Bedrock's own `villager_v2.json` and
 * its render controllers number them in. Bedrock rolls the skin once, as the villager spawns, and
 * keeps it; Java has nowhere to keep it, so it is worked out from the villager's UUID, which lasts as
 * long as the villager does.
 */
public record BedrockVillager(String profession, String type, int level, UUID id) {
    private static final List<String> PROFESSIONS = List.of("none", "farmer", "fisherman", "shepherd",
        "fletcher", "librarian", "cartographer", "cleric", "armorer", "weaponsmith", "toolsmith", "butcher",
        "leatherworker", "mason", "nitwit");

    private static final List<String> TYPES = List.of("plains", "desert", "jungle", "savanna", "snow", "swamp",
        "taiga");

    private static final int SKINS = 6;

    /**
     * The family Bedrock's villager states beside `villager` for each profession, from the component
     * group that profession switches on. A profession whose family is its own name is left out.
     */
    private static final Map<String, String> PROFESSION_FAMILIES = Map.of("none", "unskilled",
        "mason", "stone_mason");

    /** The wider trade each profession's group adds as a family of its own, where it adds one. */
    private static final Map<String, String> GUILDS = Map.ofEntries(
        Map.entry("none", "peasant"), Map.entry("nitwit", "peasant"), Map.entry("farmer", "peasant"),
        Map.entry("fisherman", "peasant"), Map.entry("fletcher", "peasant"), Map.entry("shepherd", "peasant"),
        Map.entry("armorer", "blacksmith"), Map.entry("weaponsmith", "blacksmith"),
        Map.entry("toolsmith", "blacksmith"), Map.entry("butcher", "artisan"),
        Map.entry("leatherworker", "artisan"), Map.entry("mason", "artisan"), Map.entry("cleric", "priest"));

    /** The villager or zombie villager this entity is, null for any other entity. */
    public static BedrockVillager tryFrom(Entity entity) {
        return entity instanceof VillagerDataHolder holder ? of(holder.getVillagerData(), entity.getUUID()) : null;
    }

    public static BedrockVillager of(VillagerData data, UUID id) {
        return new BedrockVillager(Platform.villagerProfession(data), Platform.villagerType(data),
            Platform.villagerLevel(data), id);
    }

    /**
     * The value of the component Bedrock names `minecraft:<name>`, null for one a villager has no
     * number for. A profession or a type another mod added counts as the first of its list.
     */
    public Integer component(String name) {
        return switch (name) {
            case "variant" -> Math.max(0, PROFESSIONS.indexOf(profession));
            case "mark_variant" -> Math.max(0, TYPES.indexOf(type));
            case "skin_id" -> (int) Math.floorMod(id.getLeastSignificantBits(), (long) SKINS);
            default -> null;
        };
    }

    /**
     * The families the profession's component group adds: `villager_v2` states the profession's own
     * family and, for most, the trade it belongs to, and `zombie_villager_v2` states the profession's
     * family alone. A profession another mod added adds nothing.
     */
    public Set<String> families(boolean zombie) {
        if (!PROFESSIONS.contains(profession)) return Set.of();

        String family = PROFESSION_FAMILIES.getOrDefault(profession, profession);
        String guild = GUILDS.get(profession);
        return zombie || guild == null ? Set.of(family) : Set.of(family, guild);
    }

    /**
     * Bedrock numbers trade tiers from nought, a novice being 0 and a master 4, which is how its own
     * villager indexes the five badges it wears. Java numbers the same levels from one.
     */
    public int tradeTier() {
        return level - 1;
    }

    /**
     * The event Bedrock raises on a villager that was `before` and is now this, null where it raises
     * none.
     *
     * Bedrock raises one only when the profession changes. Mojang's own file spells the shepherd's
     * `minecraft:become_sheperd`, so that is the name a pack listens for, and a nitwit is one from the
     * day it spawns and has no event at all.
     */
    public String eventFrom(BedrockVillager before) {
        if (before != null && before.profession.equals(profession)) return null;

        return switch (profession) {
            case "none" -> "minecraft:become_unskilled";
            case "shepherd" -> "minecraft:become_sheperd";
            case "nitwit" -> null;
            default -> PROFESSIONS.contains(profession) ? "minecraft:become_" + profession : null;
        };
    }
}
