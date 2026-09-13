package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.registry.AddonEntities;

import java.nio.file.Files;
import java.util.Set;

/**
 * Which of a pack's own directories and index files a reader opens, and which it does not.
 *
 * Every other coverage measurement this repository makes is about the vocabulary inside a file kind
 * the mod already reads. This is about the file kinds themselves: a whole directory an add-on ships
 * that nothing here opens draws no complaint from any of them. `DEFINED` names what Bedrock lets a
 * pack ship, `READ` names the part a reader opens, and the difference is reported against the pack
 * that shipped it, so `/mcaddon entities` says which directory went unread rather than leaving the
 * author to work it out from a world that came up short.
 *
 * A name here is a path relative to the pack root: a top level directory, a top level file, or an
 * index file inside a directory whose other contents are not JSON. `sounds/` holds a pack's audio
 * and `sounds/sound_definitions.json` is the index naming it, and the two are answered separately.
 */
public final class PackFiles {
    private PackFiles() {
    }

    /** Everything Bedrock lets a behaviour pack ship. */
    private static final Set<String> BEHAVIOUR_DEFINED = Set.of(
        "manifest.json", "pack_icon.png", "animations", "animation_controllers", "biomes", "blocks",
        "cameras", "dialogue", "dimensions", "entities", "feature_rules", "features", "functions",
        "item_catalog", "items", "loot_tables", "recipes", "scripts", "shapes", "spawn_rules",
        "structures", "texts", "trading",
        "texts/language_names.json", "texts/languages.json");

    /** The behaviour pack paths a reader opens. */
    private static final Set<String> BEHAVIOUR_READ = Set.of(
        "manifest.json", "pack_icon.png", "blocks", "entities", "functions", "item_catalog", "items",
        "loot_tables", "recipes", "scripts", "spawn_rules", "structures", "texts", "trading");

    /** Everything Bedrock lets a resource pack ship. */
    private static final Set<String> RESOURCE_DEFINED = Set.of(
        "manifest.json", "pack_icon.png", "animations", "animation_controllers", "atmospherics",
        "attachables", "biomes", "biomes_client.json", "blocks.json", "color_grading", "entity",
        "fogs", "font", "lighting", "materials", "models", "particles", "render_controllers",
        "sounds", "sounds.json", "texts", "textures", "ui", "water",
        "sounds/music_definitions.json", "sounds/sound_definitions.json",
        "textures/flipbook_textures.json", "textures/item_texture.json",
        "textures/terrain_texture.json", "textures/texture_list.json",
        "texts/language_names.json", "texts/languages.json");

    /** The resource pack paths a reader opens. */
    private static final Set<String> RESOURCE_READ = Set.of(
        "manifest.json", "pack_icon.png", "animations", "animation_controllers", "attachables",
        "blocks.json", "entity", "materials", "models", "particles", "render_controllers", "sounds",
        "sounds.json", "texts", "textures",
        "sounds/music_definitions.json", "sounds/sound_definitions.json",
        "textures/flipbook_textures.json", "textures/item_texture.json",
        "textures/terrain_texture.json");

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
        Set<String> defined = behaviour ? BEHAVIOUR_DEFINED : RESOURCE_DEFINED;
        Set<String> read = behaviour ? BEHAVIOUR_READ : RESOURCE_READ;
        String label = behaviour ? "behaviour pack " : "resource pack ";

        for (String path : defined) {
            if (!read.contains(path) && Files.exists(pack.file(path))) {
                AddonEntities.unsupported(label + path);
            }
        }
    }
}
