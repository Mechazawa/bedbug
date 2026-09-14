package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition.Action;
import at.ioexception.mcaddon.pack.BedrockPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is read out of the player definition a behaviour pack may write.
 *
 * The numbers only mean anything on `minecraft:player`, because that is the only entity Bedrock
 * reads them on, and a pack states each exhaustion price against Java's own constant for the same
 * moment, so what the call site is handed is the price scaled by what Java was about to spend.
 */
class PlayerDefinitionTest {
    @TempDir
    Path directory;

    private static final String FULL = """
        {
          "minecraft:player.hunger": {"value": 17, "max": 20},
          "minecraft:player.saturation": {"value": 3, "max": 8},
          "minecraft:player.exhaustion": {"value": 1, "max": 6},
          "minecraft:player.level": {"value": 2, "max": 30},
          "minecraft:player.experience": {"value": 0.25, "max": 1},
          "minecraft:exhaustion_values": {
            "attack": 0.25, "damage": 0.2, "heal": 3, "jump": 0.15, "lunge": 2,
            "mine": 0.02, "sprint": 0.3, "sprint_jump": 0.4, "swim": 0.05, "walk": 0.01
          }
        }
        """;

    @Test
    void readsAValueAndACeilingForEachAttribute() throws IOException {
        PlayerDefinition player = definition("minecraft:player", FULL);

        assertNotNull(player);
        assertEquals(17f, player.hunger().value());
        assertEquals(20f, player.hunger().max());
        assertEquals(3f, player.saturation().value());
        assertEquals(8f, player.saturation().max());
        assertEquals(1f, player.exhaustion().value());
        assertEquals(6f, player.exhaustion().max());
        assertEquals(2f, player.level().value());
        assertEquals(30f, player.level().max());
        assertEquals(0.25f, player.experience().value());
        assertEquals(1f, player.experience().max());
    }

    /** A field the pack leaves out stays Java's own, so nothing is invented to put in its place. */
    @Test
    void leavesAFieldThePackDidNotWriteUnanswered() throws IOException {
        PlayerDefinition player = definition("minecraft:player",
            "{\"minecraft:player.hunger\": {\"max\": 12}}");

        assertNull(player.hunger().value());
        assertEquals(12f, player.hunger().max());
        assertNull(player.saturation().value());
        assertNull(player.saturation().max());
    }

    @Test
    void namesOnlyTheComponentsItTook() throws IOException {
        assertEquals(PlayerDefinition.COMPONENTS, definition("minecraft:player", FULL).taken());
        assertEquals(java.util.Set.of("minecraft:player.level"),
            definition("minecraft:player", "{\"minecraft:player.level\": {\"value\": 4}}").taken());
    }

    /**
     * A price this game has no moment to spend is named rather than dropped.
     *
     * `lunge` prices the enchantment of that name, which 26 added and the older versions have not,
     * so what belongs in the report is whatever this build cannot charge.
     */
    @Test
    void namesThePricesThisGameCannotCharge() throws IOException {
        //? if >=26 {
        assertTrue(definition("minecraft:player", FULL).unread().isEmpty());
        //?} else {
        /*assertEquals(java.util.Set.of("minecraft:exhaustion_values lunge"),
            definition("minecraft:player", FULL).unread());
        *///?}
        assertTrue(definition("minecraft:player",
            "{\"minecraft:exhaustion_values\": {\"mine\": 0.02}}").unread().isEmpty());
    }

    @Test
    void ignoresTheComponentsOnAnythingButThePlayer() throws IOException {
        assertNull(definition("tp:ember", FULL));
    }

    @Test
    void ignoresAPlayerDefinitionWritingNoneOfThem() throws IOException {
        assertNull(definition("minecraft:player", "{\"minecraft:health\": {\"value\": 30}}"));
    }

    /**
     * A price is met by scaling what the call site was about to spend.
     *
     * Java pushes 0.1 before the attack charge and 0.005 before the mining one, so those arrive as
     * the constant itself and come back as the price. A movement charge arrives with the distance
     * already multiplied in, which is why scaling is what keeps it.
     */
    @ParameterizedTest
    @CsvSource({
        "ATTACK, 0.1, 0.25",
        "MINE, 0.005, 0.02",
        "JUMP, 0.05, 0.15",
        "SPRINT_JUMP, 0.2, 0.4",
        "HEAL, 6.0, 3.0",
        "HEAL, 3.0, 1.5",
        "DAMAGE, 0.1, 0.2",
        "DAMAGE, 0.0, 0.0",
        "SPRINT, 0.02, 0.06",
        "SWIM, 0.01, 0.05",
        "LUNGE, 8.0, 4.0",
    })
    void scalesWhatTheCallSiteWasAboutToSpend(Action action, float amount, float expected)
        throws IOException {
        assertEquals(expected, definition("minecraft:player", FULL).priced(action, amount), 0.0001f);
    }

    @Test
    void leavesAPriceThePackDidNotStateAlone() throws IOException {
        PlayerDefinition player = definition("minecraft:player",
            "{\"minecraft:exhaustion_values\": {\"mine\": 0.02}}");

        assertEquals(0.02f, player.priced(Action.MINE, 0.005f), 0.0001f);
        assertEquals(0.1f, player.priced(Action.ATTACK, 0.1f), 0.0001f);
        assertEquals(6.0f, player.priced(Action.HEAL, 6.0f), 0.0001f);
    }

    /**
     * Walking is charged against the distance, because Java charges nothing for it.
     *
     * The game counts the distance in centimetres and charges the price per hundred of them, so two
     * blocks at a hundredth each cost two hundredths.
     */
    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "2.0 -> 0.02",
        "0.5 -> 0.005",
        "0.0 -> 0.0",
    })
    void chargesWalkingAgainstTheDistance(double blocks, float expected) throws IOException {
        assertEquals(expected, definition("minecraft:player", FULL).walkPrice(blocks), 0.0001f);
    }

    /**
     * A sprint jump and a plain one differ only in what Java was about to spend.
     *
     * One method charges for both, 0.2 for the first and 0.05 for the second, so the amount is the
     * only thing the call site offers to tell them apart by.
     */
    @ParameterizedTest
    @CsvSource({"0.2, 0.4", "0.05, 0.15"})
    void tellsASprintJumpFromAPlainOneByWhatJavaSpends(float amount, float expected)
        throws IOException {
        definition("minecraft:player", FULL).install();

        assertEquals(expected, PlayerDefinition.jumpCost(amount), 0.0001f);
    }

    @Test
    void chargesNothingForWalkingWhenThePackPricedNone() throws IOException {
        PlayerDefinition player = definition("minecraft:player",
            "{\"minecraft:exhaustion_values\": {\"mine\": 0.02}}");

        assertEquals(0.0f, player.walkPrice(10.0), 0.0001f);
    }

    private PlayerDefinition definition(String identifier, String components) throws IOException {
        BedrockPack pack = BehaviourPackFixture.holding(directory, "entities/player.json", """
            {
              "format_version": "1.21.0",
              "minecraft:entity": {
                "description": {"identifier": "%s"},
                "components": %s
              }
            }
            """.formatted(identifier, components));

        List<ServerEntityDefinition> definitions = ServerEntityDefinition.readAll(pack);
        assertEquals(1, definitions.size());
        return PlayerDefinition.tryFrom(definitions.getFirst());
    }
}
