package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A pack's `manifest.json`.
 *
 * `name` and `description` are usually the translation keys `pack.name` and `pack.description`
 * rather than literals, resolved later from the pack's own `texts/*.lang`.
 */
public record PackManifest(
    UUID uuid,
    SemVer version,
    String name,
    String description,
    SemVer minEngineVersion,
    String packScope,
    List<Module> modules,
    List<PackDependency> packDependencies,
    List<ModuleDependency> moduleDependencies,
    Set<String> capabilities
) {
    public enum ModuleType {
        RESOURCES, DATA, SCRIPT, CLIENT_DATA, INTERFACE, WORLD_TEMPLATE, SKIN_PACK, UNKNOWN;

        static ModuleType of(String name) {
            for (ModuleType type : values()) {
                if (type.name().toLowerCase(Locale.ROOT).equals(name)) return type;
            }
            return UNKNOWN;
        }
    }

    /** `entry` and `language` are set only on a script module. */
    public record Module(ModuleType type, UUID uuid, SemVer version, String entry, String language) {
    }

    /** One pack requiring another, by the required pack's header uuid. */
    public record PackDependency(UUID uuid, SemVer version) {
    }

    /** One pack requiring a script API, such as `@minecraft/server` 2.6.0. */
    public record ModuleDependency(String moduleName, SemVer version) {
    }

    public static PackManifest tryFrom(JsonObject json) {
        JsonObject header = json.getAsJsonObject("header");
        if (header == null) return null;

        UUID uuid = uuid(header.get("uuid"));
        if (uuid == null) return null;

        List<Module> modules = new ArrayList<>();
        for (JsonElement element : Json.list(json, "modules")) {
            if (!element.isJsonObject()) continue;
            JsonObject module = element.getAsJsonObject();
            modules.add(new Module(
                ModuleType.of(Json.text(module, "type", "").toLowerCase(Locale.ROOT)),
                uuid(module.get("uuid")),
                SemVer.tryFrom(module.get("version")),
                Json.text(module, "entry", null),
                Json.text(module, "language", null)
            ));
        }

        List<PackDependency> packDependencies = new ArrayList<>();
        List<ModuleDependency> moduleDependencies = new ArrayList<>();
        for (JsonElement element : Json.list(json, "dependencies")) {
            if (!element.isJsonObject()) continue;
            JsonObject dependency = element.getAsJsonObject();
            SemVer version = SemVer.tryFrom(dependency.get("version"));
            String moduleName = Json.text(dependency, "module_name", null);
            if (moduleName != null) {
                moduleDependencies.add(new ModuleDependency(moduleName, version));
                continue;
            }
            UUID required = uuid(dependency.get("uuid"));
            if (required != null) packDependencies.add(new PackDependency(required, version));
        }

        SemVer version = SemVer.tryFrom(header.get("version"));

        return new PackManifest(
            uuid,
            version == null ? SemVer.ZERO : version,
            Json.text(header, "name", "pack.name"),
            Json.text(header, "description", ""),
            SemVer.tryFrom(header.get("min_engine_version")),
            Json.text(header, "pack_scope", null),
            List.copyOf(modules),
            List.copyOf(packDependencies),
            List.copyOf(moduleDependencies),
            Json.list(json, "capabilities").stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .collect(Collectors.toUnmodifiableSet())
        );
    }

    public boolean has(ModuleType type) {
        return modules.stream().anyMatch(module -> module.type() == type);
    }

    /** The script module's entry file, or null when the pack ships no script. */
    public String scriptEntry() {
        return modules.stream()
            .filter(module -> module.type() == ModuleType.SCRIPT)
            .map(Module::entry)
            .filter(entry -> entry != null)
            .findFirst()
            .orElse(null);
    }

    private static UUID uuid(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        try {
            return UUID.fromString(value.getAsString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
