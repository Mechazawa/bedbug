package at.ioexception.mcaddon.bedrock.aim;

import at.ioexception.mcaddon.bedrock.behaviour.Families;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * How much aim assist favours each kind of block and entity while the category is in force.
 *
 * A priority names a block or an entity by its Bedrock id, kept as `AddonBlocks.identifierOf` and
 * `AddonEntities.identifierOf` report it so that either edition's spelling matches. A family and a
 * block tag stand between an id and the default: the highest priority any of the target's
 * families or tags is given counts. A block tag is a Java block tag, a bare word being one of the
 * game's own, the way `Block.getTags` hands them to a script.
 */
public record AimAssistCategory(String identifier, int entityDefault, int blockDefault,
    Map<String, Integer> entities, Map<String, Integer> blocks, Map<String, Integer> families,
    Map<String, Integer> blockTags) {

    public static final String DEFAULT_ID = "minecraft:default";
    public static final AimAssistCategory DEFAULT =
        new AimAssistCategory(DEFAULT_ID, 1, 1, Map.of(), Map.of(), Map.of(), Map.of());

    /**
     * One entry of a `minecraft:aim_assist_categories` list, or null where it has no name.
     *
     * The defaults sit inside `priorities`, as the engine's own definition has them; Microsoft's
     * tutorial writes them beside it, with `block_default` spelt `block_dafault`, and both are read.
     * `entity_type_families` and `block_tags` are the script's two maps, which a file never carries.
     */
    public static AimAssistCategory tryFrom(JsonObject entry) {
        String name = Json.text(entry, "name", null);
        if (name == null) return null;

        JsonObject priorities = entry.has("priorities") && entry.get("priorities").isJsonObject()
            ? entry.getAsJsonObject("priorities") : new JsonObject();
        return new AimAssistCategory(AimAssistSettings.namespaced(name),
            (int) Json.number(priorities, "entity_default", Json.number(entry, "entity_default", 1)),
            (int) Json.number(priorities, "block_default", Json.number(entry, "block_default",
                Json.number(entry, "block_dafault", 1))),
            priorities(priorities, "entities", AddonEntities::identifierOf),
            priorities(priorities, "blocks", AddonBlocks::identifierOf),
            priorities(priorities, "entity_type_families", UnaryOperator.identity()),
            priorities(priorities, "block_tags", UnaryOperator.identity()));
    }

    private static Map<String, Integer> priorities(JsonObject priorities, String key, UnaryOperator<String> id) {
        Map<String, Integer> read = new LinkedHashMap<>();
        if (priorities.has(key) && priorities.get(key).isJsonObject()) {
            priorities.getAsJsonObject(key).entrySet()
                .forEach(entry -> read.put(id.apply(entry.getKey()), entry.getValue().getAsInt()));
        }
        return read;
    }

    /** The shape `tryFrom` reads. */
    public JsonObject toJson() {
        JsonObject priorities = new JsonObject();
        priorities.addProperty("entity_default", entityDefault);
        priorities.addProperty("block_default", blockDefault);
        priorities.add("entities", object(entities));
        priorities.add("blocks", object(blocks));
        priorities.add("entity_type_families", object(families));
        priorities.add("block_tags", object(blockTags));
        JsonObject json = new JsonObject();
        json.addProperty("name", identifier);
        json.add("priorities", priorities);
        return json;
    }

    private static JsonElement object(Map<String, Integer> values) {
        JsonObject json = new JsonObject();
        values.forEach(json::addProperty);
        return json;
    }

    public int priorityOf(Entity entity) {
        Integer named = entities.get(AddonEntities.identifierOf(entity.getType()));
        if (named != null) return named;

        return Families.of(entity).stream().filter(families::containsKey).map(families::get)
            .max(Integer::compare).orElse(entityDefault);
    }

    public int priorityOf(BlockState state) {
        Integer named = blocks.get(AddonBlocks.identifierOf(state));
        if (named != null) return named;

        return blockTags.entrySet().stream().filter(tag -> inTag(state, tag.getKey())).map(Map.Entry::getValue)
            .max(Integer::compare).orElse(blockDefault);
    }

    static boolean inTag(BlockState state, String tag) {
        Identifier id = Identifier.tryParse(tag.contains(":") ? tag : "minecraft:" + tag);
        return id != null && state.is(TagKey.create(Registries.BLOCK, id));
    }
}
