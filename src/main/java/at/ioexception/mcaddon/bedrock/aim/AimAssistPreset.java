package at.ioexception.mcaddon.bedrock.aim;

import at.ioexception.mcaddon.bedrock.behaviour.Families;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Which category aim assist uses for what the player holds, and what it never targets.
 *
 * `excluded` holds block and entity ids together, as `exclusion_list` does, each kept the way
 * `AddonEntities.identifierOf` or `AddonBlocks.identifierOf` reports it; `excludedFamilies` and
 * `excludedBlockTags` are the script's two further lists. Every item id is kept as
 * `AddonItems.identifierOf` reports it, and every category name with a namespace.
 */
public record AimAssistPreset(String identifier, Map<String, String> itemSettings, String defaultItemSettings,
    String handSettings, Set<String> excluded, Set<String> excludedFamilies, Set<String> excludedBlockTags,
    Set<String> liquidItems) {

    /** What `/aimassist` uses when it names no preset: every category the default and nothing left out. */
    public static final AimAssistPreset DEFAULT = new AimAssistPreset(AimAssistSettings.DEFAULT_PRESET, Map.of(),
        null, null, Set.of(), Set.of(), Set.of(), Set.of());

    /** A `minecraft:aim_assist_preset` body, or null where it has no identifier. */
    public static AimAssistPreset tryFrom(JsonObject body) {
        String identifier = Json.text(body, "identifier", null);
        if (identifier == null) return null;

        Map<String, String> items = new LinkedHashMap<>();
        if (body.has("item_settings") && body.get("item_settings").isJsonObject()) {
            body.getAsJsonObject("item_settings").entrySet().forEach(entry -> items.put(
                AddonItems.identifierOf(entry.getKey()), AimAssistSettings.namespaced(entry.getValue().getAsString())));
        }
        String fallback = Json.text(body, "default_item_settings", null);
        String hand = Json.text(body, "hand_settings", null);
        return new AimAssistPreset(AimAssistSettings.namespaced(identifier), items,
            fallback == null ? null : AimAssistSettings.namespaced(fallback),
            hand == null ? null : AimAssistSettings.namespaced(hand),
            ids(body, "exclusion_list", AimAssistPreset::targetId),
            ids(body, "excluded_entity_type_families", UnaryOperator.identity()),
            ids(body, "excluded_block_tags", UnaryOperator.identity()),
            ids(body, "liquid_targeting_list", AddonItems::identifierOf));
    }

    /** An entity id where an entity answers to it, and a block id otherwise. */
    private static String targetId(String written) {
        return AddonEntities.typeOf(written) != null ? AddonEntities.identifierOf(written)
            : AddonBlocks.identifierOf(written);
    }

    private static Set<String> ids(JsonObject body, String key, UnaryOperator<String> id) {
        return Json.list(body, key).stream().map(JsonElement::getAsString).map(id)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The shape `tryFrom` reads. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("identifier", identifier);
        JsonObject items = new JsonObject();
        itemSettings.forEach(items::addProperty);
        json.add("item_settings", items);
        if (defaultItemSettings != null) json.addProperty("default_item_settings", defaultItemSettings);
        if (handSettings != null) json.addProperty("hand_settings", handSettings);
        json.add("exclusion_list", array(excluded));
        json.add("excluded_entity_type_families", array(excludedFamilies));
        json.add("excluded_block_tags", array(excludedBlockTags));
        json.add("liquid_targeting_list", array(liquidItems));
        return json;
    }

    private static JsonArray array(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    /**
     * The category in force for what the player holds: the item's own, then the preset's default for
     * any item, or the empty hand's, falling back to the game's default category where the preset
     * names none.
     */
    public String categoryFor(ItemStack held) {
        String named = held.isEmpty() ? handSettings
            : itemSettings.getOrDefault(AddonItems.identifierOf(held.getItem()), defaultItemSettings);
        return named == null ? AimAssistCategory.DEFAULT_ID : named;
    }

    public boolean targetsLiquidsWith(ItemStack held) {
        return !held.isEmpty() && liquidItems.contains(AddonItems.identifierOf(held.getItem()));
    }

    public boolean excludes(Entity entity) {
        return excluded.contains(AddonEntities.identifierOf(entity.getType()))
            || Families.of(entity).stream().anyMatch(excludedFamilies::contains);
    }

    public boolean excludes(BlockState state) {
        return excluded.contains(AddonBlocks.identifierOf(state))
            || excludedBlockTags.stream().anyMatch(tag -> AimAssistCategory.inTag(state, tag));
    }

    /** Every category the preset names, so the client is sent each one it may need. */
    public Set<String> categories() {
        return Stream.concat(Stream.of(AimAssistCategory.DEFAULT_ID, defaultItemSettings, handSettings),
                itemSettings.values().stream())
            .filter(name -> name != null)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
