package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.bedrock.behaviour.DataValues;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What the two editions call the same vanilla item, in both directions.
 *
 * An item placing a block is mostly spelled the way its block is, so {@link BedrockBlockNames}
 * answers for those. What is left here is what only items have: a few renames of their own, the
 * families {@link DataValues} picks a member of by a data value, and the spellings Bedrock still
 * reads from packs written before its items were renamed (`muttonRaw`, `record_13`), which it reads
 * without regard to case.
 *
 * A Bedrock id may carry its data value after a second colon, `minecraft:wool:14`, which is how a
 * trade table and an equipment table write one.
 */
public final class BedrockItemNames {
    /** Item names of Bedrock's own, which turn round cleanly into the way back. */
    private static final Map<String, String> RENAMED = Map.ofEntries(
        Map.entry("empty_map", "map"),
        Map.entry("frame", "item_frame"),
        Map.entry("glow_frame", "glow_item_frame"),
        Map.entry("netherbrick", "nether_brick"),
        Map.entry("zombie_pigman_spawn_egg", "zombified_piglin_spawn_egg"));

    /** Spellings Bedrock still reads and no longer writes, so the way back never answers with one. */
    private static final Map<String, String> LEGACY = Map.ofEntries(
        Map.entry("appleenchanted", "enchanted_golden_apple"),
        Map.entry("carrotonastick", "carrot_on_a_stick"),
        Map.entry("chain", "iron_chain"),
        Map.entry("chorus_fruit_popped", "popped_chorus_fruit"),
        Map.entry("clownfish", "tropical_fish"),
        Map.entry("cooked_fish", "cooked_cod"),
        Map.entry("darkoak_sign", "dark_oak_sign"),
        Map.entry("emptymap", "map"),
        Map.entry("fireball", "fire_charge"),
        Map.entry("fireworks", "firework_rocket"),
        Map.entry("fireworkscharge", "firework_star"),
        Map.entry("fish", "cod"),
        Map.entry("grass", "grass_block"),
        Map.entry("horsearmordiamond", "diamond_horse_armor"),
        Map.entry("horsearmorgold", "golden_horse_armor"),
        Map.entry("horsearmoriron", "iron_horse_armor"),
        Map.entry("horsearmorleather", "leather_horse_armor"),
        Map.entry("light_block", "light"),
        Map.entry("lodestone_compass", "compass"),
        Map.entry("lodestonecompass", "compass"),
        Map.entry("melon", "melon_slice"),
        Map.entry("muttoncooked", "cooked_mutton"),
        Map.entry("muttonraw", "mutton"),
        Map.entry("netherstar", "nether_star"),
        Map.entry("reeds", "sugar_cane"),
        Map.entry("scute", "turtle_scute"),
        Map.entry("sealantern", "sea_lantern"),
        Map.entry("sign", "oak_sign"),
        Map.entry("speckled_melon", "glistering_melon_slice"),
        Map.entry("totem", "totem_of_undying"),
        Map.entry("turtle_shell_piece", "turtle_scute"),
        Map.entry("yellow_flower", "dandelion"));

    private static final Set<String> ITEMLESS = Set.of("frosted_ice", "spawn_egg");

    private static final Map<String, String> BEDROCK = RENAMED.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    private static final Pattern DATA = Pattern.compile("(.+):(-?\\d+)");
    private static final Pattern LIGHT_LEVEL = Pattern.compile("light_block_\\d+");

    private BedrockItemNames() {
    }

    /**
     * The Java item id for a Bedrock one, or null when no version of Java has that item.
     *
     * An id outside the `minecraft` namespace belongs to an add-on and comes back as the registry
     * holds it. A data value written after the id picks a family member as {@link #java(String, int)}
     * does.
     */
    public static String java(String bedrock) {
        Matcher data = DATA.matcher(bedrock);
        return data.matches() ? java(data.group(1), Integer.parseInt(data.group(2))) : java(bedrock, 0);
    }

    /**
     * The Java item a Bedrock id and a data value name together.
     *
     * The value picks a member of a family Java split, and is no answer outside it. Any other id
     * keeps its item whatever number stands beside it, the way Bedrock ignores one there.
     */
    public static String java(String bedrock, int data) {
        String name = DataValues.vanilla(bedrock);
        if (name == null) return BedrockBlockNames.packOwn(bedrock);

        name = name.toLowerCase(Locale.ROOT);
        String built = BedrockBlockNames.BUILT_HERE.get(name);
        if (built != null) return built;
        if (DataValues.split(name)) {
            String member = DataValues.item(name, data);
            return member == null ? null : shipped(DataValues.vanilla(member));
        }
        return shipped(javaName(name));
    }

    /**
     * Whether Bedrock has this item and Java has nothing to stand in for it.
     *
     * Beyond the blocks Java lacks, Java keeps no item for frosted ice, and Bedrock's one
     * `spawn_egg` names its mob by a number from before Bedrock's mobs had ids.
     */
    public static boolean bedrockOnly(String bedrock) {
        String name = DataValues.vanilla(bedrock);
        return name != null && (ITEMLESS.contains(name.toLowerCase(Locale.ROOT))
            || BedrockBlockNames.bedrockOnly(bedrock));
    }

    /**
     * The Bedrock id for a Java item. Every colour of banner and of bed is Bedrock's one `banner`
     * and one `bed`, told apart by a data value. An id outside `minecraft` comes back untouched,
     * unless it is one of Bedrock's blocks this mod builds.
     */
    public static String bedrock(String java) {
        if (!java.startsWith("minecraft:")) return BedrockBlockNames.BUILT_BACK.getOrDefault(java, java);

        String name = BedrockBlockNames.newest(java.substring("minecraft:".length()));
        if (name.endsWith("_banner") && !name.endsWith("_banner_pattern")) return "minecraft:banner";
        if (name.endsWith("_bed")) return "minecraft:bed";
        if (name.equals("light")) return "minecraft:light_block_15";

        String renamed = BEDROCK.get(name);
        return "minecraft:" + (renamed != null ? renamed : BedrockBlockNames.renamedBack(name));
    }

    private static String javaName(String name) {
        String renamed = RENAMED.getOrDefault(name, LEGACY.get(name));
        if (renamed != null) return renamed;
        if (name.startsWith("record_")) return "music_disc_" + name.substring("record_".length());
        if (LIGHT_LEVEL.matcher(name).matches()) return "light";

        String wood = name.replace("darkoak_", "dark_oak_");
        return BedrockBlockNames.RENAMED.getOrDefault(wood, wood);
    }

    /** The first of a Java name's spellings this version has as an item, as a whole id. */
    private static String shipped(String java) {
        for (String candidate : BedrockBlockNames.JAVA_SPELLINGS.getOrDefault(java, List.of(java))) {
            if (BuiltInRegistries.ITEM.containsKey(Platform.id("minecraft", candidate))) {
                return "minecraft:" + candidate;
            }
        }
        return null;
    }
}
