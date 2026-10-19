package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.bedrock.ui.UiDefinitions;
import at.ioexception.mcaddon.registry.AddonEntities;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of a pack's own directories and index files a reader opens, and which it does not.
 *
 * Every other coverage measurement this repository makes is about the vocabulary inside a file kind
 * the mod already reads. This is about the file kinds themselves: a whole directory an add-on ships
 * that nothing here opens draws no complaint from any of them. `READ` names the part of what Bedrock
 * lets a pack ship that this mod converts, and `UNREAD` names the rest with the reason Java cannot
 * take it. A pack shipping one of the second is told so by `/mcaddon entities`, reason included,
 * rather than left to work it out from a world that came up short. A directory outside `READ` may still
 * be opened to name what is in it, which is what tells an author which setting of theirs is dropped
 * rather than only which directory.
 *
 * A name here is a path relative to the pack root: a top level directory, a top level file, or an
 * index file inside a directory whose other contents are not JSON. `sounds/` holds a pack's audio
 * and `sounds/sound_definitions.json` is the index naming it, and the two are answered separately.
 */
public final class PackFiles {
    private PackFiles() {
    }

    /** The behaviour pack paths Bedrock defines and no reader opens, each with the reason. */
    private static final Map<String, String> BEHAVIOUR_UNREAD = Map.of();

    /** The behaviour pack paths a reader opens. */
    private static final Set<String> BEHAVIOUR_READ = Set.of(
        "manifest.json", "pack_icon.png", "aim_assist", "animation_controllers", "animations", "biomes", "blocks",
        "camera", "cameras", "dialogue", "dimensions", "entities", "feature_rules", "features", "functions",
        "item_catalog", "items", "loot_tables", "recipes", "scripts", "shapes", "spawn_rules",
        "structures", "subpacks", "texts", "trading", "texts/language_names.json",
        "texts/languages.json");

    /** The resource pack paths Bedrock defines and no reader opens, each with the reason. */
    private static final Map<String, String> RESOURCE_UNREAD = Map.ofEntries(
        Map.entry("water", "read by Vibrant Visuals alone, whose engine turns the three particle "
            + "concentrations into the extinction and albedo its shaders take by a model Mojang does "
            + "not publish, and whose default caustics texture is built into the game"));

    /** The resource pack paths a reader opens. */
    private static final Set<String> RESOURCE_READ = Set.of(
        "manifest.json", "pack_icon.png", "animations", "animation_controllers", "atmospherics",
        "attachables", "biomes", "biomes_client.json", "blocks.json", "color_grading", "entity", "fogs", "font",
        "lighting", "materials", "models", "particles", "render_controllers", "sounds",
        "sounds.json", "subpacks", "texts", "textures", "ui",
        "sounds/music_definitions.json", "sounds/sound_definitions.json",
        "textures/flipbook_textures.json", "textures/item_texture.json",
        "textures/terrain_texture.json", "textures/texture_list.json",
        "texts/language_names.json", "texts/languages.json");

    /**
     * Names every path the pack ships that Bedrock defines and no reader here opens.
     *
     * A pack is free to ship a readme, a licence and a build script beside its content, so only a
     * path Bedrock itself defines is worth a word: anything else is the author's own and its absence
     * from the output is no surprise to them.
     */
    public static void report(BedrockPack pack) {
        PackRole role = pack.role();
        if (role == PackRole.OTHER) return;

        boolean behaviour = role == PackRole.BEHAVIOUR;
        Map<String, String> unread = behaviour ? BEHAVIOUR_UNREAD : RESOURCE_UNREAD;
        String label = behaviour ? "behaviour pack " : "resource pack ";

        unread.entrySet().stream().filter(entry -> Files.exists(pack.file(entry.getKey())))
            .forEach(entry -> AddonEntities.unsupported(
                label + entry.getKey() + ", " + entry.getValue()));
        if (!behaviour) UiDefinitions.read(List.of(pack)).report(AddonEntities::unsupported);
    }
}
