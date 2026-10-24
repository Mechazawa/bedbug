package at.ioexception.mcaddon.structure;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Every `.mcstructure` the behaviour packs ship, held by the world's own structure manager.
 *
 * Bedrock places a pack's structure by the name its path gives it, through `/structure load` and a
 * script's `StructureManager`, and Java places a template through `/place template` and the same
 * manager. Holding the pack's structures there under those names is what lets every one of those
 * reach them. A reload empties the manager, so they are put back after one. A structure the world
 * or a data pack already holds under the same name is the one that stays.
 */
public final class PackStructures {
    private static final String DIRECTORY = "structures";
    private static final String EXTENSION = ".mcstructure";

    private PackStructures() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(PackStructures::install);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> install(server));
    }

    public static void install(MinecraftServer server) {
        StructureTemplateManager templates = Platform.structureTemplates(server);
        for (BedrockPack pack : ActiveAddons.active().packs().packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;

            for (Path file : pack.list(DIRECTORY, EXTENSION)) {
                Identifier id = Identifier.tryParse(nameOf(pack, file));
                if (id == null) {
                    AddonEntities.unsupported("structure file name " + pack.relative(file));
                    continue;
                }
                if (templates.get(id).isPresent()) continue;

                McStructure structure = read(pack, file);
                if (structure == null) {
                    AddonEntities.unsupported("structure file that does not read as one");
                    continue;
                }
                Platform.loadTemplate(templates.getOrCreate(id),
                    structure.javaTemplate(AddonEntities::unsupported));
            }
        }
    }

    /**
     * The name a structure takes from its path: the first folder under `structures/` is the
     * namespace and the rest is the path, and a file with no folder takes Bedrock's default one.
     */
    private static String nameOf(BedrockPack pack, Path file) {
        String relative = pack.relative(file).toString().replace('\\', '/');
        String inside = relative.substring(DIRECTORY.length() + 1, relative.length() - EXTENSION.length());
        int slash = inside.indexOf('/');
        String named = slash < 0 ? inside : inside.substring(0, slash) + ":" + inside.substring(slash + 1);
        return McStructure.javaName(named);
    }

    private static McStructure read(BedrockPack pack, Path file) {
        try (InputStream stream = pack.open(pack.relative(file).toString())) {
            return McStructure.read(stream.readAllBytes());
        } catch (IOException error) {
            McAddon.LOGGER.error("Could not read the structure {} from {}.", file, pack.displayName());
            return null;
        }
    }
}
