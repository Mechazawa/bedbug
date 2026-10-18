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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Bedrock command line becomes in the dialect the Bedrock command tree reads, and what a
 * Bedrock `.mcfunction` file becomes as a Java function.
 *
 * The two languages share most of their spelling, so the cases here are the ones where they differ
 * and a copied line would be rejected by Java's parser, which is why a command with no Java
 * counterpart has to be named rather than handed on.
 *
 * Every case writes its lines as one function of a pack on disk, so a call by name has a function to
 * find. The entity name lookup asks the registry, hence the bootstrap.
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
        "'kill @e[hasitem={item=stone,quantity=2..}]', 'kill @e[hasitem={item=stone,quantity=2..}]'",
    })
    void rewritesTheSelectorArgumentsJavaSpellsDifferently(String source, String expected) throws IOException {
        assertEquals(expected, only(source));
    }

    @ParameterizedTest
    @CsvSource({
        "'testfor @p', 'execute if entity @p'",
        "'testforblock ~ ~ ~ stone', 'execute if block ~ ~ ~ minecraft:stone'",
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
            + "'item replace entity @s weapon.mainhand with minecraft:stone'",
        "'replaceitem block ~ ~ ~ slot.container 3 stone 2', "
            + "'item replace block ~ ~ ~ container.3 with minecraft:stone 2'",
        "'ride @s start_riding @e[type=pig]', "
            + "'execute as @s at @s run ride @s mount @e[type=minecraft:pig,limit=1]'",
        "'ride @s stop_riding', 'execute as @s run ride @s dismount'",
        "'save hold', 'save-off'",
        "'tickingarea add 0 0 0 32 0 32 named', 'forceload add 0 0 32 32'",
        "'tickingarea remove_all', 'forceload remove all'",
        "'time set sunrise', 'time set 23000'",
        "'time set day', 'time set day'",
        "'changesetting difficulty h', 'difficulty hard'",
        "'damage @s 4 fall', 'damage @s 4 minecraft:fall'",
        "'damage @s 4 fire_tick', 'damage @s 4 minecraft:on_fire'",
        "'damage @s 4 entity_attack entity @p', 'damage @s 4 minecraft:mob_attack by @p'",
        "'execute in nether run say hi', 'execute in the_nether run say hi'",
        "'tickingarea remove 10 64 20', 'forceload remove 10 20'",
        "'replaceitem entity @s slot.armor 2 iron_leggings', "
            + "'item replace entity @s armor.legs with minecraft:iron_leggings'",
        "'replaceitem entity @s slot.chest 0 chest', 'item replace entity @s horse.chest with minecraft:chest'",
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
        "'dialogue open @e[type=tp:ember,c=1] @p tp:greeting', "
            + "'dialogue open @e[type=tp:ember,limit=1] @p \"tp:greeting\"'",
        "'dialogue open @e @p', 'dialogue open @e @p'",
        "'dialogue change @e[type=tp:ember] tp:greeting', "
            + "'dialogue change @e[type=tp:ember] \"tp:greeting\"'",
        "'dialogue change @e tp:greeting @a', 'dialogue change @e \"tp:greeting\"'",
        "'schedule delay add tp:tick 20 append', 'schedule function tp:tick 20 append'",
        "'schedule delay clear tp:tick', 'schedule clear tp:tick'",
        "'schedule clear tp:tick', 'schedule clear tp:tick'",
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
        "'schedule on_area_loaded add 0 0 0 8 8 8 tp:tick', "
            + "'command schedule on_area_loaded, which Java has no form of'",
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
        assertEquals("item replace entity @s " + Platform.saddleSlot() + " with minecraft:saddle",
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

    /**
     * A vanilla item or block named by Bedrock's id, which Java's parser rejects along with the
     * whole function, written as the Java id it is.
     */
    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "give @p record_13 -> give @p minecraft:music_disc_13",
        "give @p minecraft:muttonRaw 3 -> give @p minecraft:mutton 3",
        "give @p wool 2 14 -> give @p minecraft:red_wool 2",
        "clear @p golden_rail -> clear @p minecraft:powered_rail",
        "clear @p dye -1 5 -> clear @p minecraft:ink_sac 5",
        "clear @p wool 14 -> clear @p minecraft:red_wool",
        "clear @p -> clear @p",
        "setblock ~ ~1 ~ lit_pumpkin -> setblock ~ ~1 ~ minecraft:jack_o_lantern",
        "setblock ~ ~ ~ wool [\"color\"=\"red\"] replace -> setblock ~ ~ ~ minecraft:red_wool replace",
        "setblock ~ ~ ~ wool 14 -> setblock ~ ~ ~ minecraft:red_wool",
        "setblock 0 64 0 lit_furnace [\"facing_direction\"=3] -> "
            + "setblock 0 64 0 minecraft:furnace[lit=true,facing=south]",
        "fill 0 0 0 1 1 1 golden_rail -> fill 0 0 0 1 1 1 minecraft:powered_rail",
        "fill 0 0 0 1 1 1 stone 1 replace grass_path -> fill 0 0 0 1 1 1 minecraft:granite replace minecraft:dirt_path",
        "fill 0 0 0 1 1 1 air 0 hollow -> fill 0 0 0 1 1 1 minecraft:air hollow",
        "testforblock 0 64 0 log [\"old_log_type\":\"spruce\"] -> execute if block 0 64 0 minecraft:spruce_log",
        "execute if block ~ ~-1 ~ grass_path run say on a path -> "
            + "execute if block ~ ~-1 ~ minecraft:dirt_path run say on a path",
        "execute unless block ~ ~ ~ stained_glass [\"color\"=\"silver\"] run say clear -> "
            + "execute unless block ~ ~ ~ minecraft:light_gray_stained_glass run say clear",
        "execute @p ~ ~ ~ detect ~ ~-1 ~ wool 14 say red -> "
            + "execute as @p at @s positioned ~ ~ ~ if block ~ ~-1 ~ minecraft:red_wool run say red",
        "execute @p ~ ~ ~ detect ~ ~-1 ~ golden_rail -1 say rail -> "
            + "execute as @p at @s positioned ~ ~ ~ if block ~ ~-1 ~ minecraft:powered_rail run say rail",
        "clone 0 0 0 1 1 1 5 5 5 filtered move magma -> clone 0 0 0 1 1 1 5 5 5 filtered minecraft:magma_block move",
        "clone 0 0 0 1 1 1 5 5 5 masked force -> clone 0 0 0 1 1 1 5 5 5 masked force",
        "replaceitem entity @s slot.weapon.mainhand 0 frame -> "
            + "item replace entity @s weapon.mainhand with minecraft:item_frame",
    })
    void writesBedrocksItemAndBlockIdsAsJavasOwn(String source, String expected) throws IOException {
        assertEquals(expected.strip(), only(source.strip()));
    }

    @ParameterizedTest
    @CsvSource({
        "'give @p minecraft:border_block', 'command give, which names an item Java does not have'",
        "'setblock ~ ~ ~ element_3', 'command setblock, which names a block Java does not have'",
        "'testforblock ~ ~ ~ furnace 2', 'command testforblock with a block data value'",
        "'execute if block ~ ~ ~ minecraft:allow run say no', "
            + "'command execute, which names a block Java does not have'",
    })
    void dropsALineNamingAnItemOrBlockJavaCannotMatch(String source, String reported) throws IOException {
        assertEquals(List.of(), translate(source));
        assertTrue(problems.contains(reported), problems.toString());
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

    /**
     * Every link of a chain is translated, so a Bedrock selector anywhere in it and a Bedrock command
     * behind its last `run` reach the tree the way the pack meant them.
     */
    @Test
    void translatesEveryLinkOfAnExecuteChain() throws IOException {
        assertEquals("execute as @e[type=minecraft:villager,hasitem={item=apple}] at @s run execute "
                + "if entity @s[distance=..3] run particle \"tp:burst\" ~ ~1 ~",
            only("execute as @e[type=villager_v2,hasitem={item=apple}] at @s run execute if entity @s[r=3] "
                + "run particle tp:burst ~ ~1 ~"));
        assertEquals("execute as @a at @s positioned ~ ~ ~ run playanimation @s animation.wave",
            only("execute @a ~ ~ ~ playanimation @s animation.wave"));
    }

    @Test
    void dropsAnExecuteWhoseOwnCommandJavaHasNoCounterpartFor() throws IOException {
        assertEquals(List.of(), translate("execute as @a run controlscheme @s clear"));
        assertTrue(problems.contains("command controlscheme"), problems.toString());
    }

    @Test
    void dropsACommandNeitherEditionShares() throws IOException {
        assertEquals(List.of(), translate("aimassist @a set preset"));
        assertTrue(problems.contains("command aimassist"), problems.toString());
    }

    @Test
    void dropsAGameRuleJavaDoesNotHave() throws IOException {
        assertEquals(List.of(), translate("gamerule showcoordinates true"));
        assertTrue(problems.contains("game rule showcoordinates, which Java keeps no rule for"),
            problems.toString());
    }

    /** Bedrock caps a function's commands with a rule of its own; Java caps them with its chain rule. */
    @Test
    void setsTheLimitOnAFunctionsCommandsThroughJavasChainRule() throws IOException {
        assertEquals(only("gamerule maxcommandchainlength 20000"),
            only("gamerule functioncommandlimit 20000"));
    }

    /** `clearspawnpoint` is registered here, and takes the line as Bedrock writes it. */
    @Test
    void passesAClearedSpawnPointThroughToTheCommandRegisteredForIt() throws IOException {
        assertEquals("clearspawnpoint @a[tag=lost]", only("clearspawnpoint @a[tag=lost]"));
        assertEquals("clearspawnpoint", only("clearspawnpoint"));
    }

    /** A score list carries commas of its own, which the argument split has to leave alone. */
    @Test
    void keepsASelectorWhoseArgumentHasCommasInIt() throws IOException {
        assertEquals("kill @e[scores={wins=1,losses=0},limit=1]", only("kill @e[scores={wins=1,losses=0},c=1]"));
    }

    @Test
    void dropsALineSelectingOnAnArgumentBedrockDoesNotHave() throws IOException {
        assertEquals(List.of(), translate("kill @e[mood=calm]"));
        assertTrue(problems.contains("the selector argument mood"), problems.toString());
    }

    /** Answered by a tag once the line runs, so the translation leaves each whole for that. */
    @ParameterizedTest
    @CsvSource({
        "'kill @e[haspermission={movement=enabled},c=1]', 'kill @e[haspermission={movement=enabled},limit=1]'",
        "'kill @e[has_property={tp:mood=\"calm\",property=!tp:lit}]', "
            + "'kill @e[has_property={tp:mood=\"calm\",property=!tp:lit}]'",
    })
    void keepsAnArgumentAnsweredAsTheLineRuns(String bedrock, String java) throws IOException {
        assertEquals(java, only(bedrock));
    }

    /**
     * Bedrock names a function by its path alone and Java reads a bare name as one under
     * `minecraft`, where no add-on's function is written, so a call left alone runs nothing.
     */
    @ParameterizedTest
    @CsvSource({
        "'function greet', 'function ns:greet'",
        "'function GREET', 'function ns:greet'",
        "'execute as @a run function greet', 'execute as @a run function ns:greet'",
        "'execute @a ~ ~ ~ function greet', 'execute as @a at @s positioned ~ ~ ~ run function ns:greet'",
        "'schedule delay add greet 20 append', 'schedule function ns:greet 20 append'",
        "'schedule clear greet', 'schedule clear ns:greet'",
        "'function other:greet', 'function other:greet'",
    })
    void namesTheAddOnShippingTheFunctionACallNames(String source, String expected) throws IOException {
        assertEquals(expected, only(source));
    }

    @Test
    void dropsACallToAFunctionNoPackShips() throws IOException {
        assertEquals(List.of("say before"), translate("function nowhere", "say before"));
        assertTrue(problems.contains("function nowhere, which no installed pack ships"), problems.toString());
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
        assertEquals(List.of(), translate("wsserver ws://127.0.0.1:19131"));
        assertTrue(problems.contains("command wsserver"), problems.toString());
    }

    /** The commands acting on a player's own view and input keep the arguments a pack writes them with. */
    @ParameterizedTest
    @CsvSource({
        "'camera @a set minecraft:free ease 2 in_out_quad pos 1 2 3 rot 30 90'",
        "'camera @a clear'",
        "'camerashake add @a 0.5 3 rotational'",
        "'camerashake stop @a'",
        "'fog @a push minecraft:fog_crimson_forest my_fog'",
        "'fog @a pop my_fog'",
        "'fog @a remove my_fog'",
        "'inputpermission set @a movement disabled'",
        "'inputpermission query @s camera enabled'",
    })
    void keepsAViewLineWhole(String source) throws IOException {
        assertEquals(source, only(source));
    }

    @ParameterizedTest
    @CsvSource({
        "'hud @a hide'",
        "'hud @a reset all'",
        "'hud @a[tag=watching] hide crosshair'",
        "'hud @s reset progress_bar'",
    })
    void keepsAHudLineNamingAPieceJavaDrawsWhole(String source) throws IOException {
        assertEquals(source, only(source));
    }

    /** No Java client draws the paper doll, the touch controls or Bedrock's interaction hints. */
    @ParameterizedTest
    @CsvSource({"paperdoll", "touch_controls", "tooltips"})
    void dropsAHudLineNamingAPieceNoJavaClientDraws(String element) throws IOException {
        assertEquals(List.of(), translate("hud @a hide " + element));
        assertTrue(problems.contains("command hud " + element + ", which Java has no form of"),
            problems.toString());
    }

    @Test
    void keepsTheRestOfAFunctionThatHasOneDroppedLine() throws IOException {
        assertEquals(List.of("say before", "say after"),
            translate("say before", "controlscheme @a clear", "say after"));
    }

    /**
     * A function file hands every line to `/bedrock` as the pack wrote it, so Java's parser reads a
     * line it could not translate as readily as any other and the file loads whole.
     */
    @Test
    void writesEveryLineOfAFunctionForBedrockToRun() throws IOException {
        assertEquals(
            List.of("bedrock say before", "# a note", "bedrock controlscheme @a clear", "bedrock kill @e[r=5]"),
            converted("say before", "# a note", "", "controlscheme @a clear", "/kill @e[r=5]"));
        assertTrue(problems.isEmpty(), problems.toString());
    }

    /** A script names the commands it declares with its namespace, and the tree holds them so. */
    @Test
    void passesACommandAPackDeclaredThrough() throws IOException {
        assertEquals("tp:summon_guard @s 3", only("tp:summon_guard @s 3"));
    }

    /**
     * A command this mod registers reaches the Bedrock tree unchanged.
     *
     * Java's own tree knows none of these names, so a line using one is only valid because
     * `BedrockCommands` registered it. Dropping one here would cost a pack the line without saying so.
     */
    @ParameterizedTest
    @MethodSource("registeredCommands")
    void keepsACommandThisModRegisters(String name) throws IOException {
        assertEquals(name + " @a", only(name + " @a"));
    }

    static Set<String> registeredCommands() {
        return Functions.REGISTERED_WHOLE_LINE;
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
        assertEquals(List.of("ns:greet"), Functions.convert(packAt(pack), output, "ns"));

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
        FunctionNames functions = FunctionNames.of(Map.of("ns", List.of(packAt(writeFunction(source)))))
            .issuedBy("ns");
        return Arrays.stream(source)
            .map(line -> Functions.line(line.strip(), functions, problems))
            .filter(Objects::nonNull)
            .toList();
    }

    private List<String> converted(String... source) throws IOException {
        Path output = directory.resolve("out");
        Functions.convert(packAt(writeFunction(source)), output, "ns");

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
