package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangExpression;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.convert.BedrockBlockNames;
import at.ioexception.mcaddon.convert.BedrockItemTags;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A block or an item a pack names, by its name, its states or a Molang test of its tags.
 *
 * Mojang's `Block Descriptor` and `Item Descriptor` schemas allow a bare name, an object carrying
 * `name` (an item's older spelling is `item`) and, for a block, `states`, or an object carrying
 * `tags`: an expression `query.all_tags` and `query.any_tag` answer about the block or the item.
 * A block's tags are Java's block tags under the name the pack wrote. An item's go through
 * `BedrockItemTags`, which knows Bedrock's own names for Java's sets.
 */
public final class Descriptor {
    private final String name;
    private final Map<String, String> states;
    private final MolangExpression tags;

    private BedrockBlockNames.Resolved resolved;

    private Descriptor(String name, Map<String, String> states, MolangExpression tags) {
        this.name = name;
        this.states = states;
        this.tags = tags;
    }

    /**
     * The descriptor a pack wrote, or null where it names nothing, which is reported.
     *
     * `randomly_placeable_blocks` wraps the descriptor in an entry under `block`, so that is read
     * through as well.
     */
    public static Descriptor tryFrom(JsonElement written) {
        if (written == null) return null;
        if (written.isJsonPrimitive()) return new Descriptor(written.getAsString(), Map.of(), null);
        if (!written.isJsonObject()) return refused(written);

        JsonObject body = written.getAsJsonObject();
        if (!body.has("name") && !body.has("item") && !body.has("tags") && body.has("block")) {
            return tryFrom(body.get("block"));
        }

        String named = Json.text(body, "name", Json.text(body, "item", null));
        String tagged = Json.text(body, "tags", null);
        if (named == null && tagged == null) return refused(written);

        Map<String, String> states = new LinkedHashMap<>();
        JsonObject stated = body.has("states") && body.get("states").isJsonObject()
            ? body.getAsJsonObject("states") : new JsonObject();
        stated.entrySet().forEach(state -> states.put(state.getKey(),
            state.getValue().isJsonPrimitive() ? state.getValue().getAsString() : state.getValue().toString()));
        return new Descriptor(named, Map.copyOf(states), tagged == null ? null : MolangParser.parse(tagged));
    }

    /** Every descriptor a list field holds, leaving out and reporting the ones naming nothing. */
    public static List<Descriptor> listed(JsonObject body, String field) {
        return Json.list(body, field).stream().map(Descriptor::tryFrom).filter(Objects::nonNull).toList();
    }

    private static Descriptor refused(JsonElement written) {
        AddonEntities.unsupported("descriptor " + written);
        return null;
    }

    public boolean matches(BlockState state) {
        return namesBlock(state)
            && (tags == null || tagged(tag -> carries(state.getBlock().builtInRegistryHolder(), tag)));
    }

    /**
     * Whether this can match the block in one of its states, asking `tagged` about each Java block
     * tag. A tool's rules are built while the tags are still being loaded, which is why the caller
     * says what a tag holds.
     */
    public boolean names(Holder<Block> block, Predicate<TagKey<Block>> tagged) {
        if (name != null && !named().contains(block.value())) return false;

        return tags == null || tagged(tag -> {
            Identifier id = Identifier.tryParse(tag);
            return id != null && tagged.test(TagKey.create(Registries.BLOCK, id));
        });
    }

    /** Every block this can match in one of its states, with the block tags as they are bound now. */
    public Stream<Block> blocks() {
        Stream<Block> candidates = name != null ? named().stream() : BuiltInRegistries.BLOCK.stream();
        return candidates.filter(block -> names(block.builtInRegistryHolder(), block.builtInRegistryHolder()::is));
    }

    /** The block the name stands for, or none where no block answers it. */
    private List<Block> named() {
        Block block = states.isEmpty() ? AddonBlocks.named(name)
            : resolved() == null ? null : resolved.state().getBlock();
        return block == null ? List.of() : List.of(block);
    }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (name != null && !AddonItems.identifierOf(name).equals(AddonItems.identifierOf(stack.getItem()))) {
            return false;
        }
        return tags == null || tagged(tag -> BedrockItemTags.has(stack, tag));
    }

    private boolean namesBlock(BlockState state) {
        if (name == null) return true;
        if (states.isEmpty()) return AddonBlocks.named(name) == state.getBlock();

        return resolved() != null && resolved.state().getBlock() == state.getBlock() && resolved.stated().stream()
            .allMatch(property -> state.getValue(property).equals(resolved.state().getValue(property)));
    }

    private BedrockBlockNames.Resolved resolved() {
        if (resolved == null) resolved = BedrockBlockNames.state(name, states, AddonEntities::unsupported);
        return resolved;
    }

    private static boolean carries(Holder<Block> block, String tag) {
        Identifier id = Identifier.tryParse(tag);
        return id != null && block.is(TagKey.create(Registries.BLOCK, id));
    }

    private boolean tagged(Predicate<String> carried) {
        MolangEnvironment asked = new MolangEnvironment();
        asked.setQueries((query, arguments) -> switch (query) {
            case "all_tags" -> MolangValue.of(!arguments.isEmpty()
                && arguments.stream().allMatch(tag -> tag.isText() && carried.test(tag.text())));
            case "any_tag" -> MolangValue.of(arguments.stream()
                .anyMatch(tag -> tag.isText() && carried.test(tag.text())));
            default -> MolangValue.ZERO;
        });
        return tags.evaluate(asked).truthy();
    }
}
