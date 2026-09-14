package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Bedrock `.mcfunction` line becomes in Java's command dialect.
 *
 * The two languages share most of their spelling, so the cases here are the ones where they differ
 * and a copied line would be rejected by Java's parser. A rejected line takes the whole function
 * with it, which is why a command with no Java counterpart has to be left out and named instead.
 *
 * The translation runs over a pack on disk, so every case writes one function and reads back what
 * was produced. The entity name lookup asks the registry, hence the bootstrap.
 */
class FunctionsTest {
    @TempDir
    Path directory;

    private final Set<String> problems = new LinkedHashSet<>();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @CsvSource({
        "'kill @e[r=5]', 'kill @e[distance=..5]'",
        "'kill @e[rm=2]', 'kill @e[distance=2..]'",
        "'kill @e[r=5,rm=2]', 'kill @e[distance=2..5]'",
        "'kill @e[c=3]', 'kill @e[limit=3]'",
        "'kill @e[c=-3]', 'kill @e[limit=3,sort=furthest]'",
        "'kill @e[c=3,r=5]', 'kill @e[limit=3,distance=..5]'",
        "'kill @a[m=0]', 'kill @a[gamemode=survival]'",
        "'kill @a[m=c]', 'kill @a[gamemode=creative]'",
        "'kill @a[m=2]', 'kill @a[gamemode=adventure]'",
        "'kill @a[m=spectator]', 'kill @a[gamemode=spectator]'",
        "'kill @a[l=5]', 'kill @a[level=..5]'",
        "'kill @a[lm=2]', 'kill @a[level=2..]'",
        "'kill @e[rx=45]', 'kill @e[x_rotation=..45]'",
        "'kill @e[rxm=-45]', 'kill @e[x_rotation=-45..]'",
        "'kill @e[ry=90]', 'kill @e[y_rotation=..90]'",
        "'kill @e[rym=-90]', 'kill @e[y_rotation=-90..]'",
        "'kill @e[family=monster]', 'kill @e[tag=monster]'",
        "'kill @e[name=Steve]', 'kill @e[name=Steve]'",
        "'kill @e[]', 'kill @e'",
        "'kill @e[type=zombie]', 'kill @e[type=minecraft:zombie]'",
        "'kill @e[type=!zombie]', 'kill @e[type=!minecraft:zombie]'",
    })
    void rewritesTheSelectorArgumentsJavaSpellsDifferently(String source, String expected) throws IOException {
        assertEquals(expected, only(source));
    }

    @ParameterizedTest
    @CsvSource({
        "'testfor @p', 'execute if entity @p'",
        "'testforblock ~ ~ ~ stone', 'execute if block ~ ~ ~ stone'",
        "'testforblocks 0 0 0 1 1 1 2 2 2 all', 'execute if blocks 0 0 0 1 1 1 2 2 2 all'",
        "'/say hello', 'say hello'",
        "'say hello', 'say hello'",
    })
    void rewritesTheCommandsJavaSpellsDifferently(String source, String expected) throws IOException {
        assertEquals(expected, only(source));
    }

    /** Bedrock wraps a text component in an object Java's `title` does not know about. */
    @Test
    void unwrapsRawText() throws IOException {
        assertEquals("title @a title [{\"text\":\"hi\"}]",
            only("titleraw @a title {\"rawtext\":[{\"text\":\"hi\"}]}"));
    }

    @Test
    void keepsACommentLine() throws IOException {
        assertEquals(List.of("# a note the pack author left"), translate("# a note the pack author left"));
    }

    @Test
    void dropsBlankLines() throws IOException {
        assertEquals(List.of("say hello"), translate("", "say hello", "   "));
    }

    @Test
    void dropsABedrockOnlyCommandAndSaysWhichOne() throws IOException {
        assertEquals(List.of(), translate("dialogue open @e @p greeting"));
        assertTrue(problems.contains("command dialogue"), problems.toString());
    }

    /** The three that act on a player's own view keep the arguments a pack writes them with. */
    @ParameterizedTest
    @CsvSource({
        "'camera @a set minecraft:free ease 2 in_out_quad pos 1 2 3 rot 30 90'",
        "'camera @a clear'",
        "'camerashake add @a 0.5 3 rotational'",
        "'camerashake stop @a'",
        "'fog @a push minecraft:fog_crimson_forest my_fog'",
        "'fog @a pop my_fog'",
        "'fog @a remove my_fog'",
    })
    void keepsAViewLineWhole(String source) throws IOException {
        assertEquals(source, only(source));
    }

    @Test
    void keepsTheRestOfAFunctionThatHasOneDroppedLine() throws IOException {
        assertEquals(List.of("say before", "say after"),
            translate("say before", "hud @a hide all", "say after"));
    }

    /**
     * A command this mod registers reaches Java's parser unchanged.
     *
     * Java's own dispatcher knows none of these names, so a line using one is only valid because
     * `BedrockCommands` registered it. Dropping one here would cost a pack the line without saying so.
     */
    @ParameterizedTest
    @MethodSource("registeredCommands")
    void keepsACommandThisModRegisters(String name) throws IOException {
        assertEquals(name + " @a", only(name + " @a"));
    }

    static Set<String> registeredCommands() {
        return Functions.REGISTERED_HERE;
    }

    @Test
    void renamesASummonedEntity() throws IOException {
        assertEquals("summon minecraft:zombified_piglin ~ ~ ~", only("summon zombie_pigman ~ ~ ~"));
    }

    /** An add-on's own entity is left alone: whether it exists depends on which packs are loaded. */
    @Test
    void leavesAnAddOnsOwnEntityAlone() throws IOException {
        assertEquals("summon vn:reporter ~ ~ ~", only("summon vn:reporter ~ ~ ~"));
    }

    @Test
    void dropsASummonOfAnEntityJavaDoesNotHave() throws IOException {
        assertEquals(List.of(), translate("summon balloon ~ ~ ~"));
        assertTrue(problems.contains("command summon, which names an entity Java does not have"),
            problems.toString());
    }

    @Test
    void dropsALineWhoseSelectorNamesAnEntityJavaDoesNotHave() throws IOException {
        assertEquals(List.of(), translate("kill @e[type=balloon]"));
        assertTrue(problems.contains("a selector naming an entity Java does not have"), problems.toString());
    }

    /** Bedrock names its per-tick functions in a file; Java reads a function tag instead. */
    @Test
    void turnsTickJsonIntoAFunctionTag() throws IOException {
        Path pack = writeFunction("say hello");
        Files.writeString(pack.resolve("functions").resolve("tick.json"),
            "{\"values\": [\"greet\", \"never_converted\"]}");

        Path output = directory.resolve("out");
        assertEquals(List.of("ns:greet"), Functions.convert(packAt(pack), output, "ns", problems));

        Path tag = output.resolve("data/minecraft")
            .resolve(Platform.modernDataPaths() ? "tags/function" : "tags/functions")
            .resolve("tick.json");
        assertEquals("{\"values\":[\"ns:greet\"]}", Files.readString(tag));
    }

    private String only(String source) throws IOException {
        List<String> lines = translate(source);
        assertEquals(1, lines.size(), source);
        return lines.get(0);
    }

    private List<String> translate(String... source) throws IOException {
        Path pack = writeFunction(source);
        Path output = directory.resolve("out");
        Functions.convert(packAt(pack), output, "ns", problems);

        String written = Files.readString(output.resolve("data/ns")
            .resolve(Platform.modernDataPaths() ? "function" : "functions")
            .resolve("greet.mcfunction"));
        return written.isEmpty() ? List.of() : List.of(written.split("\n", -1));
    }

    private Path writeFunction(String... source) throws IOException {
        Path pack = directory.resolve("pack");
        Files.createDirectories(pack.resolve("functions"));
        Files.writeString(pack.resolve("functions").resolve("greet.mcfunction"), String.join("\n", source));
        return pack;
    }

    private static BedrockPack packAt(Path root) {
        return new BedrockPack(root, null, "test.mcaddon");
    }
}
