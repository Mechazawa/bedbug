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
        "'ride @s start_riding @e[type=pig]', "
            + "'execute as @s at @s run ride @s mount @e[type=minecraft:pig,limit=1]'",
        "'ride @s stop_riding', 'execute as @s run ride @s dismount'",
        "'save hold', 'save-off'",
        "'tickingarea add 0 0 0 32 0 32 named', 'forceload add 0 0 32 32'",
        "'tickingarea remove_all', 'forceload remove all'",
        "'time set sunrise', 'time set 23000'",
        "'time set day', 'time set day'",
        "'changesetting difficulty h', 'difficulty hard'",
        "'damage @s 4 fall', 'damage @s 4'",
        "'damage @s 4 fall entity @p', 'damage @s 4 minecraft:generic by @p'",
        "'execute in nether run say hi', 'execute in the_nether run say hi'",
        "'tickingarea remove 10 64 20', 'forceload remove 10 20'",
        "'replaceitem entity @s slot.armor 2 iron_leggings', "
            + "'item replace entity @s armor.legs with iron_leggings'",
        "'replaceitem entity @s slot.chest 0 chest', 'item replace entity @s horse.chest with chest'",
        "'loot replace entity @s slot.armor.head 0 loot tp:hat', "
            + "'loot replace entity @s armor.head loot tp:hat'",
        "'ride @e[type=zombie] start_riding @e[type=pig] teleport_rider', "
            + "'execute as @e[type=minecraft:zombie] at @s run ride @s mount @e[type=minecraft:pig,limit=1]'",
        "'gametest stopall', 'gametest stopall'",
        "'mobevent events_enabled false', 'mobevent events_enabled false'",
        "'music stop 2', 'music stop'",
        "'structure save tp:hut 0 0 0 4 4 4 true disk', 'structure save \"tp:hut\" 0 0 0 4 4 4'",
        "'structure load tp:hut 0 64 0 90_degrees x', "
            + "'place template tp:hut 0 64 0 clockwise_90 front_back'",
        "'structure delete tp:hut', 'structure delete \"tp:hut\"'",
        "'music play tp:theme 0.4', 'music play \"tp:theme\" 0.4'",
        "'particle tp:sparkle ~ ~ ~', 'particle \"tp:sparkle\" ~ ~ ~'",
        "'scriptevent tp:reset go', 'scriptevent \"tp:reset\" go'",
    })
    void rewritesTheCommandsJavaSpellsDifferently(String source, String expected) throws IOException {
        assertEquals(expected, only(source));
    }

    /**
     * Forms of a command that does translate, which Java has no way to say.
     *
     * Each has to leave the line rather than be handed to Java, because a line Java's parser
     * rejects costs the whole function.
     */
    @ParameterizedTest
    @CsvSource({
        "'gametest runthis', 'command gametest runthis, which Java has no form of'",
        "'music play tp:theme 1 0 loop', 'command music loop, which Java has no form of'",
        "'ride @e[type=pig] evict_riders', 'command ride evict_riders, which Java has no form of'",
        "'ride @s start_riding @e[type=pig] until_full', "
            + "'command ride until_full, which Java has no form of'",
        "'save query', 'command save query, which Java has no form of'",
        "'structure load tp:hut 0 64 0 0_degrees xz', 'command structure xz, which Java has no form of'",
        "'summon pig ~ ~ ~ facing ~ ~1 ~', 'command summon facing, which Java has no form of'",
        "'tickingarea preload 0 0 0', 'command tickingarea preload, which Java has no form of'",
        "'mobevent minecraft:ender_dragon_event true', "
            + "'command mobevent minecraft:ender_dragon_event, which Java has no form of'",
        "'place featurerule tp:rule ~ ~ ~', 'command place featurerule, which Java has no form of'",
    })
    void dropsAFormOfATranslatedCommandJavaCannotSay(String source, String reported) throws IOException {
        assertEquals(List.of(), translate(source));
        assertTrue(problems.contains(reported), problems.toString());
    }

    /** Bedrock switches fire spreading where Java from 26 measures how far a player carries it. */
    @Test
    void writesTheFireRuleTheWayThisVersionOfJavaHoldsIt() throws IOException {
        Integer radius = Platform.fireSpreadRadius(false);
        assertEquals("gamerule " + Platform.gameRuleName("doFireTick") + " "
            + (radius == null ? "false" : radius), only("gamerule dofiretick false"));
    }

    /** A saddle moved off the horse in 26, so which slot it is has to be asked for. */
    @Test
    void putsASaddleInTheSlotThisVersionOfJavaKeepsItIn() throws IOException {
        assertEquals("item replace entity @s " + Platform.saddleSlot() + " with saddle",
            only("replaceitem entity @s slot.saddle 0 saddle"));
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
