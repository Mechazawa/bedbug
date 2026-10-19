package at.ioexception.mcaddon.script;

import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which beta script API `allowed_modules` keeps a pack from, the module's folder over the default. */
class ScriptConfigTest {
    private static final String MODULE = "00000000-0000-0000-0000-0000000000aa";

    @TempDir
    Path config;

    @Test
    void aModulesListReplacesTheDefaultListAndOnlyABetaDependencyIsRefused() throws IOException {
        write("default/permissions.json", "{\"allowed_modules\": [\"@minecraft/server\"]}");
        PackManifest admin = manifest("00000000-0000-0000-0000-000000000001", MODULE,
            "{\"module_name\": \"@minecraft/server-admin\", \"version\": \"1.0.0-beta\"}");
        PackManifest released = manifest("00000000-0000-0000-0000-000000000002",
            "00000000-0000-0000-0000-0000000000bb",
            "{\"module_name\": \"@minecraft/server-ui\", \"version\": \"2.0.0\"}");
        ScriptConfig read = new ScriptConfig(config, List.of(admin, released));

        assertEquals("@minecraft/server-admin", read.refusedModule(admin));
        assertNull(read.refusedModule(released));

        write(MODULE + "/permissions.json", "{\"allowed_modules\": [\"@minecraft/server-admin\"]}");
        read.reload();
        assertNull(read.refusedModule(admin));
    }

    private void write(String file, String json) throws IOException {
        Path path = config.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, json);
    }

    private static PackManifest manifest(String header, String module, String dependency) {
        return PackManifest.tryFrom(Json.parse("""
            {
              "header": {"uuid": "%s", "name": "Probe", "version": [1, 0, 0]},
              "modules": [{"type": "script", "uuid": "%s", "entry": "scripts/main.js"}],
              "dependencies": [%s]
            }
            """.formatted(header, module, dependency)).getAsJsonObject());
    }
}
