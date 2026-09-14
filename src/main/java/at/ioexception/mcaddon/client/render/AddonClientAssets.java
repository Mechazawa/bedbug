package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.resource.AddonPacks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the client needs in order to draw an add-on's entities, indexed by the identifier a behaviour
 * pack and a resource pack agree on.
 *
 * Read once on the client, after conversion, because parsing 2500 animations per entity per frame is
 * not a thing anybody should do twice.
 */
public final class AddonClientAssets {
    /** A definition and the models of the pack it came from, which is what drawing needs together. */
    public record Binding(AddonModels models, ClientEntityDefinition definition) {
    }

    private static final Map<String, Binding> BY_IDENTIFIER = new LinkedHashMap<>();
    private static final Map<String, List<Binding>> ATTACHABLES = new LinkedHashMap<>();

    private AddonClientAssets() {
    }

    public static void load() {
        BY_IDENTIFIER.clear();
        ATTACHABLES.clear();
        for (ConvertedPack converted : AddonPacks.all()) {
            BedrockAssets assets = BedrockAssets.read(converted.pack());
            AddonModels models = new AddonModels(assets, converted);
            assets.entities().forEach((identifier, definition) ->
                BY_IDENTIFIER.put(identifier, new Binding(models, definition)));
            assets.attachables().forEach((identifier, definition) ->
                index(new Binding(models, definition), identifier));
        }
        if (!BY_IDENTIFIER.isEmpty()) {
            McAddon.LOGGER.info("{} add-on entities have a client definition, {} items are attachable.",
                BY_IDENTIFIER.size(), ATTACHABLES.size());
        }
    }

    /** Null when a behaviour pack declares an entity its resource pack never described. */
    public static Binding of(String identifier) {
        return BY_IDENTIFIER.get(identifier);
    }

    /**
     * Files one attachable under every item it is worn for.
     *
     * A pack lists those items in `item`, and an attachable that lists none is worn for the one
     * item its own identifier names.
     */
    private static void index(Binding binding, String identifier) {
        Collection<String> items = binding.definition().items().isEmpty()
            ? List.of(identifier)
            : binding.definition().items().keySet();
        items.forEach(item -> ATTACHABLES.computeIfAbsent(item, name -> new ArrayList<>()).add(binding));
    }

    /**
     * Every model offered for an item worn or held, in the order the packs were read.
     *
     * A pack may describe more than one for the same item and pick between them on a condition,
     * which is how an item is worn differently by a player than by anything else.
     */
    public static List<Binding> attachablesFor(String itemIdentifier) {
        return ATTACHABLES.getOrDefault(itemIdentifier, List.of());
    }

    /**
     * Whether any pack describes something worn or held.
     *
     * The layer that draws those hangs off every living entity renderer in the game, and the
     * pairing it reads is recorded for every entity a frame draws. With no pack describing one,
     * both cost a field read instead of a map write and a walk of six equipment slots per entity.
     */
    public static boolean anyAttachables() {
        return !ATTACHABLES.isEmpty();
    }
}
