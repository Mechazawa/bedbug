package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a dedicated server's `config/` folder tells the scripts of each behaviour pack.
 *
 * `config/default/` applies to every script module and `config/<script module uuid>/` to one
 * pack's. Either may hold `variables.json`, `secrets.json` and `permissions.json`, and a key the
 * module's folder states replaces the same key from the default folder, so a module's
 * `allowed_modules` is its whole list. The files are read when the scripts start and again on
 * `/reloadconfig`, and a script sees the values of the last read.
 */
public final class ScriptConfig {
    private static final String DEFAULT = "default";
    private static final List<String> FILES = List.of("variables.json", "secrets.json", "permissions.json");

    private final Path root;

    /** Each pack's header uuid against its script module's uuid, which names its folder. */
    private final Map<UUID, String> folders = new HashMap<>();

    /** Each folder's files as last read, by folder name and then by file name. */
    private Map<String, Map<String, JsonObject>> read = Map.of();

    ScriptConfig(Path root, List<PackManifest> manifests) {
        this.root = root;
        manifests.forEach(manifest -> manifest.modules().stream()
            .filter(module -> module.type() == PackManifest.ModuleType.SCRIPT && module.uuid() != null)
            .findFirst()
            .ifPresent(module -> folders.put(manifest.uuid(), module.uuid().toString())));
        reload();
    }

    public void reload() {
        read = Stream.concat(Stream.of(DEFAULT), folders.values().stream())
            .distinct()
            .collect(Collectors.toMap(folder -> folder, this::readFolder));
    }

    /** Every variable the pack whose header uuid is given may read, as its JSON values. */
    public JsonObject variables(UUID pack) {
        return merged(pack, "variables.json");
    }

    /** The names of the secrets the pack may ask for. Their values never leave this class. */
    public Set<String> secretNames(UUID pack) {
        return merged(pack, "secrets.json").keySet();
    }

    /**
     * The first beta script API the pack depends on that its `allowed_modules` leaves out, or null.
     *
     * Bedrock's list governs the modules a world reaches through the Beta APIs experiment, so a
     * dependency on a released version is never refused, and a server whose folders state no list
     * refuses nothing.
     */
    String refusedModule(PackManifest manifest) {
        JsonElement allowed = merged(manifest.uuid(), "permissions.json").get("allowed_modules");
        if (allowed == null || !allowed.isJsonArray()) return null;

        Set<String> names = allowed.getAsJsonArray().asList().stream()
            .filter(JsonElement::isJsonPrimitive)
            .map(JsonElement::getAsString)
            .collect(Collectors.toSet());
        return manifest.moduleDependencies().stream()
            .filter(PackManifest.ModuleDependency::beta)
            .map(PackManifest.ModuleDependency::moduleName)
            .filter(name -> !names.contains(name))
            .findFirst()
            .orElse(null);
    }

    private JsonObject merged(UUID pack, String file) {
        JsonObject merged = new JsonObject();
        Stream.of(DEFAULT, folders.get(pack))
            .filter(Objects::nonNull)
            .map(folder -> read.getOrDefault(folder, Map.of()).get(file))
            .filter(Objects::nonNull)
            .forEach(object -> object.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue())));
        return merged;
    }

    private Map<String, JsonObject> readFolder(String folder) {
        Map<String, JsonObject> files = new HashMap<>();
        for (String name : FILES) {
            Path file = root.resolve(folder).resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try {
                files.put(name, Json.parseObject(file));
            } catch (IOException | RuntimeException unreadable) {
                McAddon.LOGGER.error("The script configuration {} could not be read: {}", file,
                    unreadable.getMessage());
            }
        }
        return files;
    }
}
