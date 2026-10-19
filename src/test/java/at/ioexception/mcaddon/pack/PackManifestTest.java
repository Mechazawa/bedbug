package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a `manifest.json` is read as, including the shapes strict JSON would refuse.
 *
 * A pack's manifest is written by hand or by a pack tool, so it arrives with byte order marks,
 * comments and trailing commas in it, and a reader that rejects any of those rejects working packs.
 * The dependency list is the other trap: one entry shape names another pack by uuid and the other
 * names a script API by module name, and the two mean entirely different things to load order.
 */
class PackManifestTest {
    private static final String SELF = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000002";

    @Test
    void tellsAPackDependencyApartFromAScriptApiOne() throws IOException {
        PackManifest manifest = manifest("""
            {
              "header": {"uuid": "%s", "version": [1, 2, 3]},
              "dependencies": [
                {"uuid": "%s", "version": [2, 0, 0]},
                {"module_name": "@minecraft/server", "version": "2.6.0"}
              ]
            }
            """.formatted(SELF, OTHER));

        assertEquals(
            List.of(new PackManifest.PackDependency(UUID.fromString(OTHER), new SemVer(2, 0, 0))),
            manifest.packDependencies());
        assertEquals(
            List.of(new PackManifest.ModuleDependency("@minecraft/server", new SemVer(2, 6, 0), false, false)),
            manifest.moduleDependencies());
    }

    @Test
    void readsAManifestCarryingAByteOrderMarkAndComments() throws IOException {
        PackManifest manifest = manifest("\ufeff" + """
            {
              // the half of the add-on the client draws
              "header": {"uuid": "%s", "name": "Oreville", "version": [1, 0, 0]},
              "modules": [{"type": "resources", "uuid": "%s", "version": [1, 0, 0]},]
            }
            """.formatted(SELF, OTHER));

        assertEquals(UUID.fromString(SELF), manifest.uuid());
        assertEquals("Oreville", manifest.name());
        assertEquals(List.of(PackManifest.ModuleType.RESOURCES),
            manifest.modules().stream().map(PackManifest.Module::type).toList());
    }

    @Test
    void readsTheScriptModulesEntryFile() throws IOException {
        PackManifest manifest = manifest("""
            {
              "header": {"uuid": "%s", "version": [1, 0, 0]},
              "modules": [
                {"type": "data", "uuid": "%s", "version": [1, 0, 0]},
                {"type": "script", "language": "javascript", "entry": "scripts/main.js",
                 "uuid": "%s", "version": [1, 0, 0]}
              ],
              "capabilities": ["script_eval"]
            }
            """.formatted(SELF, OTHER, OTHER));

        assertEquals("scripts/main.js", manifest.scriptEntry());
        assertEquals(Set.of("script_eval"), manifest.capabilities());
    }

    @Test
    void hasNoScriptEntryWhenThePackShipsNoScript() throws IOException {
        PackManifest manifest = manifest("""
            {
              "header": {"uuid": "%s", "version": [1, 0, 0]},
              "modules": [{"type": "data", "uuid": "%s", "version": [1, 0, 0]}]
            }
            """.formatted(SELF, OTHER));

        assertNull(manifest.scriptEntry());
    }

    /** Bedrock writes a version as a string or as three numbers, sometimes with a label after it. */
    @ParameterizedTest
    @CsvSource({
        "'[1, 2, 3]', 1.2.3",
        "'\"1.2.3\"', 1.2.3",
        "'\"1.2.3-beta\"', 1.2.3",
        "'[1]', 1.0.0",
    })
    void readsEitherVersionSpelling(String version, String expected) throws IOException {
        PackManifest manifest = manifest("""
            {"header": {"uuid": "%s", "version": %s}}
            """.formatted(SELF, version));

        assertEquals(expected, manifest.version().toString());
    }

    @Test
    void fallsBackToZeroWhenTheHeaderNamesNoVersion() throws IOException {
        assertEquals(SemVer.ZERO, manifest("{\"header\": {\"uuid\": \"%s\"}}".formatted(SELF)).version());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"header\": {}}",
        "{\"header\": {\"uuid\": \"not a uuid\"}}",
        "{\"header\": {\"uuid\": 7}}",
    })
    void answersNullWithoutAUsableUuid(String json) throws IOException {
        assertNull(manifest(json));
    }

    /**
     * Mojang's own bands, which say which platform tier a `memory_tier` falls in.
     *
     * A `memory_tier` counts 250 MB of device memory and a `memory_performance_tier` names one of
     * five platforms. The published table gives 11 and below as tier 1, 12 to 16 as tier 2, 18 to
     * 20 as tier 3, 24 to 28 as tier 4, and 32 and above as tier 5, and the numbers it skips belong
     * to the band below them. Both are plain integers, so a band read one out puts a pack's low-end
     * form on every machine and nothing about it is an error.
     */
    @ParameterizedTest
    @CsvSource({
        "1, 1", "6, 1", "11, 1",
        "12, 2", "16, 2", "17, 2",
        "18, 3", "20, 3", "23, 3",
        "24, 4", "28, 4", "31, 4",
        "32, 5", "64, 5",
    })
    void readsAMemoryTierAsThePlatformTierItFallsIn(int memoryTier, int expected) {
        assertEquals(expected, new PackManifest.Subpack("sp", "SP", memoryTier, 0).tier(2));
    }

    /**
     * `memory_performance_tier` is the manifest version 3 spelling and is what the engine reads
     * there. An older manifest states `memory_tier`, and one stating only the newer spelling is
     * taken at its word rather than left with no tier at all.
     */
    @ParameterizedTest
    @CsvSource({
        "2, 64, 3, 5",
        "3, 64, 3, 3",
        "2, 0, 3, 3",
        "3, 0, 3, 3",
        "2, 0, 0, 0",
    })
    void prefersTheSpellingTheManifestVersionReads(int formatVersion, int memoryTier,
        int performanceTier, int expected) {
        assertEquals(expected,
            new PackManifest.Subpack("sp", "SP", memoryTier, performanceTier).tier(formatVersion));
    }

    @Test
    void takesTheHighestSubpackAndTheLastOfTwoAtOneTier() throws IOException {
        PackManifest manifest = manifest("""
            {
              "header": {"uuid": "%s", "version": [1, 0, 0]},
              "subpacks": [
                {"folder_name": "high", "name": "High", "memory_tier": 64},
                {"folder_name": "mid", "name": "Mid", "memory_tier": 12},
                {"folder_name": "also_high", "name": "Also high", "memory_performance_tier": 5}
              ]
            }
            """.formatted(SELF));

        assertEquals("also_high", manifest.subpack().folderName());
    }

    @Test
    void hasNoSubpackWhenThePackShipsOnlyItself() throws IOException {
        assertNull(manifest("{\"header\": {\"uuid\": \"%s\"}}".formatted(SELF)).subpack());
    }

    @Test
    void readsEachSettingAtTheValueItShipsWith() throws IOException {
        PackManifest manifest = manifest("""
            {
              "header": {"uuid": "%s", "version": [1, 0, 0]},
              "settings": [
                {"type": "label", "text": "A paragraph, which names no setting"},
                {"type": "toggle", "name": "tp:glow", "text": "Glow", "default": true},
                {"type": "slider", "name": "tp:heat", "text": "Heat", "min": 1, "max": 8,
                 "step": 1, "default": 5},
                {"type": "dropdown", "name": "tp:mood", "text": "Mood", "default": "restless",
                 "options": [{"name": "calm", "text": "Calm"},
                             {"name": "restless", "text": "Restless"}]}
              ]
            }
            """.formatted(SELF));

        Map<String, PackManifest.Setting> settings = manifest.settings().stream()
            .collect(Collectors.toMap(PackManifest.Setting::name, setting -> setting));

        assertEquals(Set.of("tp:glow", "tp:heat", "tp:mood"), settings.keySet());
        assertTrue(settings.get("tp:glow").enabled());
        assertEquals(5.0, settings.get("tp:heat").number());
        assertTrue(settings.get("tp:mood").selected("restless"));
        assertFalse(settings.get("tp:mood").selected("calm"));
        assertEquals(Boolean.TRUE, settings.get("tp:glow").plain());

        // The three queries are three names for one index, so each has to survive being asked about
        // a setting of another kind: `true` read as a slider is not a number to parse.
        assertEquals(0.0, settings.get("tp:glow").number());
        assertFalse(settings.get("tp:heat").enabled());
        assertFalse(settings.get("tp:heat").selected("5"));
    }

    @Test
    void bringsASlidersDefaultInsideItsRangeAndOntoItsStep() throws IOException {
        PackManifest manifest = manifest("""
            {
              "header": {"uuid": "%s", "version": [1, 0, 0]},
              "settings": [
                {"type": "slider", "name": "tp:high", "text": "", "min": 1, "max": 8, "step": 1, "default": 40},
                {"type": "slider", "name": "tp:low", "text": "", "min": 1, "max": 8, "step": 1, "default": -3},
                {"type": "slider", "name": "tp:off_step", "text": "", "min": 0, "max": 10, "step": 4,
                 "default": 5},
                {"type": "slider", "name": "tp:top", "text": "", "min": 0, "max": 10, "step": 4,
                 "default": 10}
              ]
            }
            """.formatted(SELF));

        Map<String, Double> numbers = manifest.settings().stream()
            .collect(Collectors.toMap(PackManifest.Setting::name, PackManifest.Setting::number));

        assertEquals(8.0, numbers.get("tp:high"));
        assertEquals(1.0, numbers.get("tp:low"));
        assertEquals(4.0, numbers.get("tp:off_step"));
        assertEquals(8.0, numbers.get("tp:top"));
    }

    @Test
    void fallsBackToTheFirstOptionWhenADropdownsDefaultIsNotOneOfThem() throws IOException {
        PackManifest manifest = manifest("""
            {
              "header": {"uuid": "%s", "version": [1, 0, 0]},
              "settings": [
                {"type": "dropdown", "name": "tp:mood", "text": "", "default": "furious",
                 "options": [{"name": "calm", "text": "Calm"}, {"name": "restless", "text": "Restless"}]}
              ]
            }
            """.formatted(SELF));

        PackManifest.Setting mood = manifest.settings().get(0);

        assertTrue(mood.selected("calm"));
        assertFalse(mood.selected("furious"));
        assertEquals("calm", mood.plain());
    }

    @Test
    void namesTheFieldsItStatesThatNothingReads() throws IOException {
        PackManifest manifest = manifest("""
            {
              "format_version": 2,
              "has_education_metadata": true,
              "header": {"uuid": "%s", "version": [1, 0, 0], "pack_scope": "world",
                         "platform_locked": true},
              "modules": [{"type": "data", "description": "the data half", "uuid": "%s",
                           "version": [1, 0, 0]}],
              "metadata": {"authors": ["someone"], "product_type": "addon"}
            }
            """.formatted(SELF, OTHER));

        assertEquals(List.of("has_education_metadata", "header.pack_scope", "header.platform_locked",
            "metadata.product_type", "module.description", "module.version"),
            manifest.unread().stream().sorted().toList());
    }

    @Test
    void readsWhoWroteThePack() throws IOException {
        PackManifest manifest = manifest("""
            {
              "header": {"uuid": "%s", "version": [1, 0, 0]},
              "metadata": {"authors": ["one", "two"], "license": "MIT", "url": "https://example.test"}
            }
            """.formatted(SELF));

        assertEquals(new PackManifest.Metadata(List.of("one", "two"), "MIT", "https://example.test"),
            manifest.metadata());
    }

    private static PackManifest manifest(String json) throws IOException {
        return PackManifest.tryFrom(
            Json.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).getAsJsonObject());
    }
}
