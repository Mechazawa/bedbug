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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
            List.of(new PackManifest.ModuleDependency("@minecraft/server", new SemVer(2, 6, 0))),
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

    private static PackManifest manifest(String json) throws IOException {
        return PackManifest.tryFrom(
            Json.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).getAsJsonObject());
    }
}
