package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What comes out of the fields inside an entity component, and in which unit.
 *
 * A component whose name is handled and whose fields are read in the wrong unit is worse than one
 * that is not handled at all: nothing reports it and the entity behaves wrongly. Bedrock states
 * every duration in seconds and every distance in blocks, so each of those crossings is asserted
 * against a number worked out by hand.
 */
class ComponentFieldsTest {
    @TempDir
    Path directory;

    /**
     * `value` is the health the entity arrives at and `max` is the ceiling.
     *
     * Reading `value` as the ceiling gives an entity that can never be healed past what it spawned
     * with, which is what a pack writing the two differently is asking to avoid.
     */
    @Test
    void readsTheHealthCeilingFromMaxAndTheStartFromValue() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:health": {"value": 6, "max": 20}
            """);

        assertEquals(20f, entity.maxHealth());
        assertEquals(6f, entity.startingHealth());
    }

    @Test
    void fallsBackToWhicheverHealthTheEntityDeclares() throws IOException {
        assertEquals(12f, entity("\"minecraft:health\": {\"value\": 12}").maxHealth());
        assertEquals(12f, entity("\"minecraft:health\": {\"value\": 12}").startingHealth());
        assertEquals(9f, entity("\"minecraft:health\": {\"max\": 9}").startingHealth());
    }

    /**
     * A pack writes a number in four shapes, and Gson throws on two of them.
     *
     * `true` where a number belongs and a Molang expression where a constant belongs both answer
     * with an exception rather than a value, which costs the reader the whole component.
     */
    @Test
    void readsANumberOutOfWhateverShapeThePackWroteIt() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:leashable": {"can_be_stolen": true},
            "minecraft:scale": {"value": "2.5"},
            "minecraft:timer": {"time": [3, 9]},
            "minecraft:attack": {"damage": {"range_min": 2, "range_max": 6}}
            """);

        assertEquals(1, entity.number("minecraft:leashable", "can_be_stolen", 0));
        assertEquals(2.5, entity.number("minecraft:scale", "value", 0));
        assertEquals(3, entity.number("minecraft:timer", "time", 0));
        assertEquals(4, entity.number("minecraft:attack", "damage", 0));
        assertEquals(7, entity.number("minecraft:timer", "nothing_here", 7));
    }

    /** The same shapes read as a flag, where a pack writes a number for what Java asks as a switch. */
    @Test
    void readsAFlagOutOfANumberAsWellAsABoolean() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:navigation.walk": {"can_float": 1, "avoid_water": false, "can_swim": true}
            """);

        assertTrue(ServerEntityDefinition.flag(
            entity.component("minecraft:navigation.walk"), "can_float", false));
        assertTrue(ServerEntityDefinition.flag(
            entity.component("minecraft:navigation.walk"), "can_swim", false));
        assertFalse(ServerEntityDefinition.flag(
            entity.component("minecraft:navigation.walk"), "avoid_water", true));
        assertTrue(ServerEntityDefinition.flag(
            entity.component("minecraft:navigation.walk"), "not_written", true));
    }

    /**
     * The breeding reward is Molang, and the expression asks whether a player struck the last blow.
     *
     * Asking Gson for the number behind that expression throws, which would cost the birth rather
     * than the reward.
     */
    @Test
    void readsTheBreedingRewardOutOfTheMolangThatStatesIt() throws IOException {
        assertEquals(7, bredReward(entity("""
            "minecraft:experience_reward": {"on_bred": "query.last_hit_by_player ? 7 : 0"}
            """)));
        assertEquals(3, bredReward(entity("\"minecraft:experience_reward\": {\"on_bred\": 3}")));
        assertEquals(0, bredReward(entity("\"minecraft:type_family\": {}")));
    }

    private static int bredReward(ServerEntityDefinition definition) {
        return ServerEntityDefinition.experienceWhenBred(definition.components().get("minecraft:experience_reward"));
    }

    /** A trigger is a bare event name or the object carrying it, and both appear in the same pack. */
    @Test
    void readsAnEventOutOfEitherFormOfATrigger() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:leashable": {"on_leash": {"event": "tp:held", "target": "self"},
                                    "on_unleash": "tp:freed"}
            """);

        assertEquals("tp:held",
            ServerEntityDefinition.event(entity.component("minecraft:leashable"), "on_leash"));
        assertEquals("tp:freed",
            ServerEntityDefinition.event(entity.component("minecraft:leashable"), "on_unleash"));
        assertNull(ServerEntityDefinition.event(entity.component("minecraft:leashable"), "on_calm"));
    }

    /**
     * The despawn distances are blocks and the inactivity is seconds.
     *
     * Bedrock removes a mob outright past the outer distance and rolls one in `min_range_random_chance`
     * past the inner one, which is the pair of questions Java asks of a vanilla mob.
     */
    @Test
    void answersTheDespawnRulesInBlocksAndTicks() throws IOException {
        Despawn despawn = Despawn.from(body("""
            {
              "despawn_from_distance": {"min_distance": 32, "max_distance": 128},
              "min_range_inactivity_timer": 30,
              "min_range_random_chance": 800
            }
            """));

        assertEquals(600, despawn.inactivityTicks());
        assertTrue(despawn.despawnsAt(129, 1200, 0.9), "beyond the outer distance it goes at once");
        assertFalse(despawn.despawnsAt(20, 1200, 0), "inside the inner distance it stays");
        assertFalse(despawn.despawnsAt(64, 100, 0), "it has not been idle long enough");
        assertTrue(despawn.despawnsAt(64, 1200, 0.0001), "the roll came up");
        assertFalse(despawn.despawnsAt(64, 1200, 0.5), "the roll did not");
    }

    @Test
    void takesBedrocksOwnDespawnDistancesWhenThePackNamesNone() throws IOException {
        Despawn despawn = Despawn.from(body("{\"despawn_from_distance\": {}}"));

        assertEquals(32, despawn.minDistance());
        assertEquals(128, despawn.maxDistance());
    }

    /** A pack writing its own filters replaces the standard rules rather than adding to them. */
    @Test
    void leavesTheDistanceRulesAloneWhenThePackWroteFilters() throws IOException {
        Despawn despawn = Despawn.from(body("""
            {"filters": {"test": "is_daytime", "value": true}}
            """));

        assertTrue(despawn.filtered());
        assertFalse(despawn.despawnsAt(1000, 100000, 0));
    }

    /** `entity_types` says what a goal is about, and carries its reach in the same list. */
    @Test
    void readsWhatAGoalHuntsAndHowFarItReaches() throws IOException {
        TargetSelection hunted = TargetSelection.from(body("""
            {
              "priority": 2,
              "entity_types": [
                {"filters": {"test": "is_family", "subject": "other", "value": "player"}, "max_dist": 24}
              ]
            }
            """), net.minecraft.world.entity.LivingEntity.class, 0);

        assertEquals(net.minecraft.world.entity.player.Player.class, hunted.kind());
        assertEquals(24, hunted.maxDistance());
    }

    /** A goal naming two families needs one class covering both, which is the wider of the two. */
    @Test
    void widensToTheKindThatCoversEveryFamilyTheGoalNames() throws IOException {
        TargetSelection avoided = TargetSelection.from(body("""
            {
              "entity_types": [
                {"filters": {"test": "is_family", "value": "player"}, "max_dist": 6},
                {"filters": {"test": "is_family", "value": "monster"}, "max_dist": 12}
              ]
            }
            """), net.minecraft.world.entity.monster.Monster.class, 8);

        assertEquals(net.minecraft.world.entity.monster.Monster.class, avoided.kind());
        assertEquals(12, avoided.maxDistance());
    }

    /** A goal naming nobody keeps what the caller chose rather than hunting everything alive. */
    @Test
    void keepsTheFallbackWhenAGoalNamesNoFamily() {
        TargetSelection hunted = TargetSelection.from(null,
            net.minecraft.world.entity.player.Player.class, 16);

        assertEquals(net.minecraft.world.entity.player.Player.class, hunted.kind());
        assertEquals(16, hunted.maxDistance());
    }

    private static com.google.gson.JsonObject body(String json) {
        return Json.parse(json).getAsJsonObject();
    }

    private ServerEntityDefinition entity(String components) throws IOException {
        List<ServerEntityDefinition> read = ServerEntityDefinition.readAll(
            BehaviourPackFixture.holding(directory, "entities/thing.json", """
                {"minecraft:entity": {"description": {"identifier": "tp:thing"}, "components": {%s}}}
                """.formatted(components)));

        assertEquals(1, read.size(), components);
        return read.get(0);
    }
}
