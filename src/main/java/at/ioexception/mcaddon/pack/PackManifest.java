package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.registry.AddonEntities;
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
    int formatVersion,
    UUID uuid,
    SemVer version,
    String name,
    String description,
    SemVer minEngineVersion,
    List<Module> modules,
    List<PackDependency> packDependencies,
    List<ModuleDependency> moduleDependencies,
    Set<String> capabilities,
    List<Subpack> subpacks,
    List<Setting> settings,
    Metadata metadata,
    List<String> unread
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
    public record Module(ModuleType type, String entry, String language) {
    }

    /** One pack requiring another, by the required pack's header uuid. */
    public record PackDependency(UUID uuid, SemVer version) {
    }

    /** One pack requiring a script API, such as `@minecraft/server` 2.6.0. */
    public record ModuleDependency(String moduleName, SemVer version) {
    }

    /** Who wrote the pack, under what licence, and where it lives. */
    public record Metadata(List<String> authors, String license, String url) {
        static final Metadata NONE = new Metadata(List.of(), null, null);

        public boolean isEmpty() {
            return authors.isEmpty() && license == null && url == null;
        }
    }

    /**
     * One of the several forms of itself a pack ships, under `subpacks/<folder_name>/`.
     *
     * Files in that folder stand in for the pack's own files of the same path, which is how one
     * resource pack offers a player several texture resolutions.
     */
    public record Subpack(String folderName, String name, int memoryTier, int performanceTier) {
        /**
         * The lowest `memory_tier` in each of Mojang's five performance tiers.
         *
         * A `memory_tier` counts 250 MB of device memory, and Mojang's own table groups those
         * numbers into the five platform tiers: 11 and below is tier 1, 12 to 16 is tier 2, 18 to
         * 20 is tier 3, 24 to 28 is tier 4, and 32 and above is tier 5. The numbers between two
         * bands belong to the lower of the two.
         */
        private static final int[] BANDS = {0, 12, 18, 24, 32};

        /**
         * The tier this subpack asks for, on the one to five scale, whichever spelling it was
         * written in.
         *
         * `memory_performance_tier` is the manifest format version 3 spelling and is the one the
         * engine reads there. `memory_tier` is the older spelling and counts device memory rather
         * than a platform, so it is read through Mojang's bands. A pack stating only the newer
         * spelling in an older manifest is taken at its word, because a subpack with no tier can
         * never be chosen and losing a pack's best form to a version number helps nobody.
         */
        public int tier(int formatVersion) {
            if (performanceTier > 0 && (formatVersion >= 3 || memoryTier == 0)) return performanceTier;
            if (memoryTier <= 0) return performanceTier;

            int band = 0;
            while (band + 1 < BANDS.length && memoryTier >= BANDS[band + 1]) band++;
            return band + 1;
        }
    }

    /**
     * One slider, toggle or dropdown a pack offers, at the value it ships with.
     *
     * Bedrock shows these on a screen beside the pack and lets a player move them. Java has no such
     * screen, so every one of them stays where its author put it, which is what `default` holds.
     *
     * Each answer checks the kind it is being asked for, because the three Molang queries are three
     * names for one index: a slider read as a toggle answers no, and a toggle read as a slider
     * answers zero rather than being parsed as a number it never was.
     */
    public record Setting(String type, String name, JsonElement value) {
        public double number() {
            return "slider".equals(type) && value != null && value.isJsonPrimitive()
                ? value.getAsDouble() : 0.0;
        }

        public boolean enabled() {
            return "toggle".equals(type) && value != null && value.isJsonPrimitive()
                && value.getAsBoolean();
        }

        public boolean selected(String option) {
            return "dropdown".equals(type) && value != null && value.isJsonPrimitive()
                && value.getAsString().equals(option);
        }

        /** The value as one of the three kinds `World.getPackSettings` hands a script. */
        public Object plain() {
            if (value == null || !value.isJsonPrimitive()) return null;
            if (value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
            if (value.getAsJsonPrimitive().isNumber()) return value.getAsDouble();
            return value.getAsString();
        }
    }

    /**
     * The capabilities a pack may declare that this mod already answers.
     *
     * `pbr` and `raytraced` both ask for physically based rendering, which the texture set
     * conversion writes as labPBR sidecars for every pack that ships one. `script_eval` asks for
     * `eval` and the `Function` constructor, which the GraalJS host evaluates with no permission of
     * its own to grant. Every other capability is named to the author instead.
     */
    private static final Set<String> ANSWERED_CAPABILITIES = Set.of("pbr", "raytraced", "script_eval");

    /**
     * The top level fields Bedrock defines that nothing here acts on.
     *
     * `has_education_metadata` marks a pack carrying Minecraft Education material, and the Java game
     * has no Education Edition to carry it for.
     */
    private static final Set<String> MANIFEST_DROPPED = Set.of("has_education_metadata");

    /**
     * The header fields Bedrock defines that nothing here acts on.
     *
     * `base_game_version`, `allow_random_seed` and `lock_template_options` describe a world
     * template, which names a `world_template` module and is refused as neither a resource nor a
     * behaviour pack before any of this is reached. `platform_locked` keeps a pack from travelling
     * between a player's worlds and servers, and nothing here checks an entitlement to lock
     * against. `pack_scope` says whether a pack belongs to one world or to the installation, and
     * `addons/` is one directory per instance with no world to attach a pack to.
     * `pack_optimization_version` is published in the schema and in Mojang's own field table with
     * no description in either, so there is nothing stated for a reader to act on.
     */
    private static final Set<String> HEADER_DROPPED = Set.of(
        "allow_random_seed", "base_game_version", "lock_template_options", "pack_optimization_version",
        "pack_scope", "platform_locked");

    /**
     * The module fields Bedrock defines that nothing here acts on.
     *
     * `description` is the author's note about that half of the pack and names nothing to load.
     * `uuid` and `version` identify one module to Bedrock's content updater, and a pack is loaded
     * here from the directory it sits in: what names one installation of an add-on to another is
     * the header uuid and the header version.
     */
    private static final Set<String> MODULE_DROPPED = Set.of("description", "uuid", "version");

    /**
     * `optional` says a pack runs without the script API it names.
     *
     * A module is linked before any of the script runs, so a specifier `ScriptHost.MODULES` does
     * not map ends that pack's whole script whatever the flag says.
     */
    private static final Set<String> DEPENDENCY_DROPPED = Set.of("optional");

    /**
     * `product_type` marks a pack as an add-on, which on Bedrock decides whether a world keeps its
     * achievements and whether the resource pack half is downloaded with it. Java locks no
     * advancement behind a pack and installs both halves together. `generated_with` names the tool
     * that wrote the pack, which nothing here has to answer for.
     */
    private static final Set<String> METADATA_DROPPED = Set.of("generated_with", "product_type");

    /**
     * The setting fields that describe the screen rather than the value.
     *
     * `text` is the label beside a control, and `min`, `max` and `step` bound where a player may
     * drag a slider. With no screen to show any of them, nothing moves a setting off the value its
     * author shipped, so the range it could have moved through decides nothing. `options` lists
     * what a dropdown could have been set to, and the same holds.
     */
    private static final Set<String> SETTING_DROPPED = Set.of("max", "min", "options", "step", "text");

    /** A dropdown option's own fields, dropped with the list holding them. */
    private static final Set<String> SETTING_OPTIONS_DROPPED = Set.of("name", "text");

    /**
     * The subpack spelling Mojang's newest schema has dropped and its documentation still carries.
     *
     * `memory_tier` counts device memory in units of 250 MB and is what a pack written before
     * `memory_performance_tier` states. Mojang's own subpack example writes both, so a reader that
     * knew only the newer one would drop every subpack of every pack that has not been rewritten.
     */
    private static final Set<String> SUBPACK_LEGACY = Set.of("memory_tier");

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

        List<Subpack> subpacks = new ArrayList<>();
        for (JsonElement element : Json.list(json, "subpacks")) {
            if (!element.isJsonObject()) continue;
            JsonObject subpack = element.getAsJsonObject();
            subpacks.add(new Subpack(
                Json.text(subpack, "folder_name", null),
                Json.text(subpack, "name", null),
                (int) Json.number(subpack, "memory_tier", 0),
                (int) Json.number(subpack, "memory_performance_tier", 0)
            ));
        }

        List<Setting> settings = new ArrayList<>();
        for (JsonElement element : Json.list(json, "settings")) {
            if (!element.isJsonObject()) continue;
            JsonObject setting = element.getAsJsonObject();
            String settingName = Json.text(setting, "name", null);
            if (settingName == null) continue;
            settings.add(new Setting(Json.text(setting, "type", ""), settingName, setting.get("default")));
        }

        SemVer version = SemVer.tryFrom(header.get("version"));

        return new PackManifest(
            formatVersion(json.get("format_version")),
            uuid,
            version == null ? SemVer.ZERO : version,
            Json.text(header, "name", "pack.name"),
            Json.text(header, "description", ""),
            SemVer.tryFrom(header.get("min_engine_version")),
            List.copyOf(modules),
            List.copyOf(packDependencies),
            List.copyOf(moduleDependencies),
            Json.list(json, "capabilities").stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .collect(Collectors.toUnmodifiableSet()),
            List.copyOf(subpacks),
            List.copyOf(settings),
            metadata(json.getAsJsonObject("metadata")),
            unread(json)
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

    /**
     * The one of its forms this pack is loaded as, or null when it ships only itself.
     *
     * Bedrock picks the highest tier the player's device reaches. A Java client has no memory tier
     * to compare against, and Mojang's own table names a PC at tier 5 and at no other tier, so the
     * highest tier the pack declares is the one a Java player gets. Two subpacks at one tier go to
     * the last of them, which is the rule Bedrock states for that tie.
     */
    public Subpack subpack() {
        return subpacks.stream()
            .reduce((held, offered) -> offered.tier(formatVersion) >= held.tier(formatVersion) ? offered : held)
            .orElse(null);
    }

    /** Names every field and capability this manifest states that nothing here acts on. */
    public void report() {
        unread.forEach(field -> AddonEntities.unsupported("manifest " + field));
        capabilities.stream()
            .filter(capability -> !ANSWERED_CAPABILITIES.contains(capability))
            .forEach(capability -> AddonEntities.unsupported("manifest capability " + capability));
    }

    /**
     * The manifest version, or zero for anything that is not a whole number.
     *
     * Mojang's own subpack example writes a game version string here where the schema asks for a
     * number, and a version this reader cannot place decides nothing rather than deciding wrongly.
     */
    private static int formatVersion(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return 0;
        try {
            return value.getAsInt();
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static Metadata metadata(JsonObject metadata) {
        if (metadata == null) return Metadata.NONE;

        return new Metadata(
            Json.list(metadata, "authors").stream()
                .filter(JsonElement::isJsonPrimitive)
                .map(JsonElement::getAsString)
                .toList(),
            Json.text(metadata, "license", null),
            Json.text(metadata, "url", null));
    }

    /** Every field this manifest states that nothing here acts on, qualified by the level holding it. */
    private static List<String> unread(JsonObject json) {
        List<String> found = new ArrayList<>();
        dropped(json, "", MANIFEST_DROPPED, found);
        dropped(json.getAsJsonObject("header"), "header.", HEADER_DROPPED, found);
        dropped(json.getAsJsonObject("metadata"), "metadata.", METADATA_DROPPED, found);
        for (JsonElement module : Json.list(json, "modules")) {
            dropped(object(module), "module.", MODULE_DROPPED, found);
        }
        for (JsonElement dependency : Json.list(json, "dependencies")) {
            dropped(object(dependency), "dependency.", DEPENDENCY_DROPPED, found);
        }
        for (JsonElement element : Json.list(json, "settings")) {
            JsonObject control = object(element);
            dropped(control, "setting.", SETTING_DROPPED, found);
            if (control == null) continue;
            for (JsonElement option : Json.list(control, "options")) {
                dropped(object(option), "setting.options.", SETTING_OPTIONS_DROPPED, found);
            }
        }
        return List.copyOf(found);
    }

    private static void dropped(JsonObject body, String level, Set<String> fields, List<String> found) {
        if (body == null) return;

        fields.stream()
            .filter(body::has)
            .map(field -> level + field)
            .filter(name -> !found.contains(name))
            .forEach(found::add);
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
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
