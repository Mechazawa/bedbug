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
        "'allowlist add Steve', 'whitelist add Steve'",
        "'difficulty p', 'difficulty peaceful'",
        "'gamemode 0 @s', 'gamemode survival @s'",
        "'effect @s speed 30 2', 'effect give @s speed 30 2'",
        "'effect @s clear', 'effect clear @s'",
        "'effect @s clear speed', 'effect clear @s speed'",
        "'xp 40 @s', 'xp add @s 40 points'",
        "'xp 3L @s', 'xp add @s 3 levels'",
        "'playsound note.harp @a', 'playsound note.harp master @a'",
        "'stopsound @a note.harp', 'stopsound @a * note.harp'",
        "'spreadplayers 0 0 10 40 @a', 'spreadplayers 0 0 10 40 false @a'",
        "'replaceitem entity @s slot.weapon.mainhand 0 stone', "
            + "'item replace entity @s weapon.mainhand with stone'",
        "'replaceitem block ~ ~ ~ slot.container 3 stone 2', "
            + "'item replace block ~ ~ ~ container.3 with stone 2'",
        "'ride @s start_riding @e[type=pig]', 'ride @s mount @e[type=minecraft:pig]'",
        "'ride @s stop_riding', 'ride @s dismount'",
        "'save hold', 'save-off'",
        "'tickingarea add 0 0 0 32 0 32 named', 'forceload add 0 0 32 32'",
        "'tickingarea remove_all', 'forceload remove all'",
        "'time set sunrise', 'time set 23000'",
        "'time set day', 'time set day'",
    })
    void rewritesTheCommandsJavaSpellsDifferently(String source, String expected) throws IOException {
        assertEquals(expected, only(source));
    }

    /**
     * Bedrock writes a game rule in lower case and Java's parser is case sensitive.
     *
     * Which name Java answers to differs between the supported versions, so what is checked is that
     * both spellings a pack may write reach the same one.
     */
    @Test
    void findsAGameRuleWhateverCaseThePackWroteItIn() throws IOException {
        assertEquals(only("gamerule doDayLightCycle false"), only("gamerule dodaylightcycle false"));
    }

    /** Locking the day is Bedrock's way of stopping the clock, which Java states as a rule. */
    @Test
    void turnsALockedDayIntoTheRuleThatHoldsTheClock() throws IOException {
        assertEquals(only("gamerule dodaylightcycle false"), only("daylock true"));
        assertEquals(only("gamerule dodaylightcycle true"), only("alwaysday false"));
    }

    /** Bedrock's own text commands take plain text where Java takes a component. */
    @Test
    void quotesThePlainTextOfATitle() throws IOException {
        assertEquals("title @a title \"hello there\"", only("title @a title hello there"));
    }

    @Test
    void unwrapsTheRawTextOfATellraw() throws IOException {
        assertEquals("tellraw @a [{\"text\":\"hi\"}]",
            only("tellraw @a {\"rawtext\":[{\"text\":\"hi\"}]}"));
    }

    /** Bedrock ran a command as an entity and at a place by writing both before the command. */
    @Test
    void rewritesTheExecuteBedrockHadBeforeTheModernOne() throws IOException {
        assertEquals("execute as @e[type=minecraft:pig] at @s positioned ~ ~ ~ run say oink",
            only("execute @e[type=pig] ~ ~ ~ say oink"));
    }

    @Test
    void translatesTheCommandBehindAModernExecute() throws IOException {
        assertEquals("execute as @a run effect give @s speed", only("execute as @a run effect @s speed"));
    }

    @Test
    void dropsAnExecuteWhoseOwnCommandJavaHasNoCounterpartFor() throws IOException {
        assertEquals(List.of(), translate("execute as @a run fog @s push x setting"));
        assertTrue(problems.contains("command fog"), problems.toString());
    }

    @Test
    void dropsACommandNeitherEditionShares() throws IOException {
        assertEquals(List.of(), translate("aimassist @a set preset"));
        assertTrue(problems.contains("command aimassist"), problems.toString());
    }

    @Test
    void dropsAGameRuleJavaDoesNotHave() throws IOException {
        assertEquals(List.of(), translate("gamerule showcoordinates true"));
        assertTrue(problems.contains("game rule showcoordinates"), problems.toString());
    }

    /** A score list carries commas of its own, which the argument split has to leave alone. */
    @Test
    void keepsASelectorWhoseArgumentHasCommasInIt() throws IOException {
        assertEquals("kill @e[scores={wins=1,losses=0},limit=1]", only("kill @e[scores={wins=1,losses=0},c=1]"));
    }

    @Test
    void dropsALineSelectingOnSomethingJavaCannotSelectOn() throws IOException {
        assertEquals(List.of(), translate("kill @e[hasitem={item=stone}]"));
        assertTrue(problems.contains("the selector argument hasitem, which Java has no form of"),
            problems.toString());
    }

    /** Java rejects a selector naming the same argument twice, so the two halves become one range. */
    @Test
    void putsTheTwoHalvesOfARotationRangeTogether() throws IOException {
        assertEquals("kill @e[x_rotation=-45..45]", only("kill @e[rx=45,rxm=-45]"));
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
        assertEquals(List.of(), translate("fog @a push minecraft:fog_crimson_forest setting"));
        assertTrue(problems.contains("command fog"), problems.toString());
    }

    @Test
    void keepsTheRestOfAFunctionThatHasOneDroppedLine() throws IOException {
        assertEquals(List.of("say before", "say after"),
            translate("say before", "camerashake add @a 0.1 1", "say after"));
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
            .resolve(Platform.singularDataDirectories() ? "tags/function" : "tags/functions")
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
            .resolve(Platform.singularDataDirectories() ? "function" : "functions")
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
