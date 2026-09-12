package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order packs are loaded in, and what is said about an order that cannot be satisfied.
 *
 * The two halves of one add-on normally require each other, so a single sort over all packs would
 * find a cycle in a perfectly ordinary pair. Splitting by role first is what makes that pair legal,
 * and it also means role beats name and beats dependency: a resource pack loads before a behaviour
 * pack whatever either of them says.
 */
class PackOrderTest {
    private static final String ONE = "00000000-0000-0000-0000-000000000001";
    private static final String TWO = "00000000-0000-0000-0000-000000000002";
    private static final String ABSENT = "00000000-0000-0000-0000-000000000009";

    private final List<String> problems = new ArrayList<>();

    @Test
    void putsResourcePacksBeforeBehaviourPacks() {
        PackSet set = PackOrder.sort(List.of(
            pack("Alpha", ONE, "data"),
            pack("Zulu", TWO, "resources")), problems);

        assertEquals(List.of("Zulu", "Alpha"), names(set));
        assertEquals(List.of(), problems);
    }

    @Test
    void loadsAPackAfterThePackItRequires() {
        PackSet set = PackOrder.sort(List.of(
            pack("Alpha", ONE, "resources", TWO),
            pack("Zulu", TWO, "resources")), problems);

        assertEquals(List.of("Zulu", "Alpha"), names(set));
        assertEquals(List.of(), problems);
    }

    /** The reference add-on: each half names the other's uuid, and both still load. */
    @Test
    void acceptsTheTwoHalvesOfAnAddOnRequiringEachOther() {
        PackSet set = PackOrder.sort(List.of(
            pack("Behaviour", ONE, "data", TWO),
            pack("Resources", TWO, "resources", ONE)), problems);

        assertEquals(List.of("Resources", "Behaviour"), names(set));
        assertEquals(List.of(), problems);
    }

    @Test
    void reportsACycleWithinOneRoleAndFallsBackToNameOrder() {
        PackSet set = PackOrder.sort(List.of(
            pack("Zulu", TWO, "resources", ONE),
            pack("Alpha", ONE, "resources", TWO)), problems);

        assertEquals(List.of("Alpha", "Zulu"), names(set));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("Dependency cycle between [Alpha, Zulu]"), problems.get(0));
    }

    @Test
    void reportsARequiredPackThatIsNotInstalled() {
        PackSet set = PackOrder.sort(List.of(pack("Alpha", ONE, "resources", ABSENT)), problems);

        assertEquals(List.of("Alpha"), names(set));
        assertEquals(List.of("Alpha requires a pack that is not installed: " + ABSENT), problems);
    }

    private static List<String> names(PackSet set) {
        return set.packs().stream().map(BedrockPack::displayName).toList();
    }

    private static BedrockPack pack(String name, String uuid, String module, String... requires) {
        String dependencies = Stream.of(requires)
            .map(required -> "{\"uuid\": \"%s\", \"version\": [1, 0, 0]}".formatted(required))
            .toList().toString();

        String json = """
            {
              "header": {"uuid": "%s", "name": "%s", "version": [1, 0, 0]},
              "modules": [{"type": "%s", "uuid": "%s", "version": [1, 0, 0]}],
              "dependencies": %s
            }
            """.formatted(uuid, name, module, uuid, dependencies);

        return new BedrockPack(Path.of(name), manifest(json), name + ".mcaddon");
    }

    private static PackManifest manifest(String json) {
        try {
            return PackManifest.tryFrom(
                Json.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).getAsJsonObject());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
