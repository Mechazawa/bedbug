package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.resource.AddonPacks;

import java.util.LinkedHashMap;
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
    private static final Map<String, Binding> ATTACHABLES = new LinkedHashMap<>();

    private AddonClientAssets() {
    }

    public static void load() {
        BY_IDENTIFIER.clear();
        for (ConvertedPack converted : AddonPacks.all()) {
            BedrockAssets assets = BedrockAssets.read(converted.pack());
            AddonModels models = new AddonModels(assets, converted);
            assets.entities().forEach((identifier, definition) ->
                BY_IDENTIFIER.put(identifier, new Binding(models, definition)));
            assets.attachables().forEach((identifier, definition) ->
                ATTACHABLES.put(identifier, new Binding(models, definition)));
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

    /** The model worn or held for an item, which an attachable names by the item's own identifier. */
    public static Binding attachableFor(String itemIdentifier) {
        return ATTACHABLES.get(itemIdentifier);
    }
}
