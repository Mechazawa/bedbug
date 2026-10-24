package at.ioexception.mcaddon.convert;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Bedrock's vanilla item tags, answered through the Java tag or the item property that is the same
 * set.
 *
 * A pack writes a tag as `minecraft:is_axe` or as `is_axe`, and a tag of its own under its own
 * namespace, which is a Java tag of that name. A Bedrock tag with no entry here is asked of Java as
 * written.
 */
public final class BedrockItemTags {
    private static final Map<String, Predicate<Item>> TABLE = new LinkedHashMap<>();
    private static final Map<String, String> JAVA = new LinkedHashMap<>();

    static {
        javaTag("sign", "signs");
        javaTag("hanging_sign", "hanging_signs");
        javaTag("is_axe", "axes");
        javaTag("is_pickaxe", "pickaxes");
        javaTag("is_shovel", "shovels");
        javaTag("is_hoe", "hoes");
        javaTag("is_sword", "swords");
        TABLE.put("is_tool", item -> java(item, "axes") || java(item, "pickaxes") || java(item, "shovels")
            || java(item, "hoes"));
        TABLE.put("is_food", item -> new ItemStack(item).has(DataComponents.FOOD));
        javaTag("is_fish", "fishes");
        javaTag("is_meat", "meat");
        javaTag("boat", "boats");
        javaTag("boats", "boats");
        javaTag("chest_boat", "chest_boats");
        javaTag("planks", "planks");
        javaTag("logs", "logs");
        javaTag("logs_that_burn", "logs_that_burn");
        javaTag("wooden_slabs", "wooden_slabs");
        javaTag("wool", "wool");
        javaTag("coals", "coals");
        javaTag("egg", "eggs");
        javaTag("arrow", "arrows");
        javaTag("banner", "banners");
        javaTag("metal_nuggets", "metal_nuggets");
        javaTag("stone_tool_materials", "stone_tool_materials");
        javaTag("stone_crafting_materials", "stone_crafting_materials");
        javaTag("trim_materials", "trim_materials");
        javaTag("trimmable_armors", "trimmable_armor");
        javaTag("soul_fire_base_blocks", "soul_fire_base_blocks");
    }

    private BedrockItemTags() {
    }

    private static void javaTag(String bedrock, String java) {
        JAVA.put(bedrock, java);
        TABLE.put(bedrock, item -> java(item, java));
    }

    private static boolean java(Item item, String path) {
        return item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, Identifier.parse("minecraft:" + path)));
    }

    private static String bare(String tag) {
        return tag.startsWith("minecraft:") ? tag.substring("minecraft:".length()) : tag;
    }

    /**
     * The Java tag, as `namespace:path`, that holds exactly the items of the Bedrock tag, or the
     * name as written where the table has no entry. Null for a Bedrock tag only a predicate answers,
     * which no data file can name.
     */
    public static String javaName(String tag) {
        String java = JAVA.get(bare(tag));
        if (java != null) return "minecraft:" + java;
        return TABLE.containsKey(bare(tag)) ? null : tag;
    }

    /** Whether the item is in the tag a pack named, in either edition's spelling. */
    public static boolean has(Item item, String tag) {
        if (item == null || tag == null) return false;

        Predicate<Item> bedrock = TABLE.get(bare(tag));
        if (bedrock != null) return bedrock.test(item);

        Identifier id = Identifier.tryParse(tag);
        return id != null && item.builtInRegistryHolder().is(TagKey.create(Registries.ITEM, id));
    }

    public static boolean has(ItemStack stack, String tag) {
        return !stack.isEmpty() && has(stack.getItem(), tag);
    }

    /**
     * The tags the item is in, under the names a pack reads them by: Bedrock's for the sets the
     * table covers, and the Java name for every other tag, a pack's own among them.
     */
    public static List<String> of(Item item) {
        List<String> tags = new ArrayList<>();
        TABLE.forEach((name, test) -> {
            if (test.test(item)) tags.add("minecraft:" + name);
        });
        item.builtInRegistryHolder().tags().map(tag -> tag.location().toString())
            .filter(name -> !tags.contains(name) && !TABLE.containsKey(bare(name)))
            .forEach(tags::add);
        return tags;
    }
}
