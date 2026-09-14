package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.pack.BedrockPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
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
 * every duration in seconds, every distance in blocks and every seat in sixteenths of a block, so
 * each of those crossings is asserted against a number worked out by hand.
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

    /** A pack writes `true` and a Molang expression where a number belongs, and neither may throw. */
    @Test
    void readsANumberOutOfWhateverShapeThePackWroteIt() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:leashable": {"can_be_stolen": true},
            "minecraft:scale": {"value": "2.5"},
            "minecraft:timer": {"time": [3, 9]}
            """);

        assertEquals(1, entity.number("minecraft:leashable", "can_be_stolen", 0));
        assertEquals(2.5, entity.number("minecraft:scale", "value", 0));
        assertEquals(3, entity.number("minecraft:timer", "time", 0));
        assertEquals(7, entity.number("minecraft:timer", "nothing_here", 7));
    }

    /**
     * The experience is a Molang expression, and the one every entity uses asks about the killer.
     *
     * Java pays experience only when a player made the kill, so the query is answered yes and the
     * expression gives the amount that kill is worth.
     */
    @Test
    void readsTheExperienceOutOfTheMolangThatStatesIt() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:experience_reward": {"on_death": "query.last_hit_by_player ? 7 : 0", "on_bred": 3}
            """);

        assertEquals(7, entity.experienceOnDeath(0));
        assertEquals(3, entity.experienceWhenBred());
        assertEquals(5, entity("\"minecraft:type_family\": {}").experienceOnDeath(5));
    }

    /** A trigger is a bare event name or the object carrying it, and both appear in the same pack. */
    @Test
    void readsAnEventOutOfEitherFormOfATrigger() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:angry": {"calm_event": {"event": "tp:settle", "target": "self"}},
            "minecraft:timer": {"time_down_event": "tp:ring"}
            """);

        assertEquals("tp:settle",
            ServerEntityDefinition.event(entity.component("minecraft:angry"), "calm_event"));
        assertEquals("tp:ring",
            ServerEntityDefinition.event(entity.component("minecraft:timer"), "time_down_event"));
        assertNull(ServerEntityDefinition.event(entity.component("minecraft:timer"), "calm_event"));
    }

    /** A goal's priority and speed live in its body, so the body has to survive being collected. */
    @Test
    void keepsTheBodyOfEveryGoalTheEntityDeclares() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:behavior.random_stroll": {"priority": 3, "speed_multiplier": 0.7}
            """);

        assertEquals(List.of("minecraft:behavior.random_stroll"),
            List.copyOf(entity.behaviours().keySet()));
        assertEquals(3, ServerEntityDefinition.number(
            entity.behaviours().get("minecraft:behavior.random_stroll"), "priority", 0));
        assertEquals(0.7, ServerEntityDefinition.number(
            entity.behaviours().get("minecraft:behavior.random_stroll"), "speed_multiplier", 1));
    }

    /**
     * `random_teleport_cube` is blocks and `max_random_teleport_time` is seconds.
     *
     * Reading the time as the reach gives an entity that jumps as many blocks as the pack meant to
     * give it seconds of quiet, which for Mojang's own enderman is thirty blocks several times a
     * second instead of thirty-two blocks every half minute.
     */
    @Test
    void readsTheTeleportReachInBlocksAndItsWaitInTicks() throws IOException {
        Teleport teleport = Teleport.tryFrom(entity("""
            "minecraft:teleport": {
              "random_teleports": true,
              "min_random_teleport_time": 2,
              "max_random_teleport_time": 30,
              "random_teleport_cube": [32, 16, 32],
              "light_teleport_chance": 0.25,
              "dark_teleport_chance": 0.75
            }
            """));

        assertEquals(32, teleport.reachX());
        assertEquals(16, teleport.reachY());
        assertEquals(40, teleport.shortestIntervalTicks());
        assertEquals(600, teleport.longestIntervalTicks());
        assertEquals(40, teleport.nextDelayTicks(0));
        assertEquals(600, teleport.nextDelayTicks(1));
        assertEquals(0.25, teleport.chanceAt(15));
        assertEquals(0.75, teleport.chanceAt(0));
    }

    @Test
    void teleportsInsideTheCubeBedrockDefaultsToWhenThePackNamesNone() throws IOException {
        Teleport teleport = Teleport.tryFrom(entity("\"minecraft:teleport\": {}"));

        assertEquals(32, teleport.reachX());
        assertEquals(16, teleport.reachY());
        assertEquals(32, teleport.reachZ());
        assertEquals(400, teleport.longestIntervalTicks());
    }

    @Test
    void hasNoTeleportWhenThePackSwitchedItOff() throws IOException {
        assertNull(Teleport.tryFrom(entity("\"minecraft:teleport\": {\"random_teleports\": false}")));
        assertNull(Teleport.tryFrom(entity("\"minecraft:type_family\": {}")));
    }

    /** `duration` is seconds, and a pack that never sees it stays angry for the rest of its life. */
    @Test
    void readsTheAngerDurationInTicks() throws IOException {
        Anger anger = Anger.tryFrom(entity("""
            "minecraft:angry": {
              "duration": 25,
              "duration_delta": 5,
              "broadcast_anger": true,
              "broadcast_range": 12,
              "calm_event": {"event": "minecraft:on_calm", "target": "self"}
            }
            """));

        assertEquals(500, anger.durationTicks());
        assertEquals(100, anger.spreadTicks());
        assertEquals(400, anger.lifetimeTicks(0));
        assertEquals(600, anger.lifetimeTicks(1));
        assertEquals("minecraft:on_calm", anger.calmEvent());
        assertEquals(12, anger.broadcastRange());
        assertTrue(anger.broadcastAnger());
    }

    /** Mojang's own bee spells the same two fields without their underscores. */
    @Test
    void readsTheOlderSpellingOfTheAngerBroadcast() throws IOException {
        Anger anger = Anger.tryFrom(entity("""
            "minecraft:angry": {"duration": 25, "broadcastAnger": true, "broadcastRange": 20}
            """));

        assertTrue(anger.broadcastAnger());
        assertEquals(20, anger.broadcastRange());
    }

    @Test
    void staysAngryForeverWhenThePackAsksForANegativeDuration() throws IOException {
        assertEquals(-1, Anger.tryFrom(entity("\"minecraft:angry\": {\"duration\": -1}")).lifetimeTicks(0.5));
    }

    /**
     * The despawn distances are blocks and the inactivity is seconds.
     *
     * Bedrock removes a mob outright past the outer distance and rolls one in `min_range_random_chance`
     * past the inner one, which is the pair of questions Java asks of a vanilla mob.
     */
    @Test
    void answersTheDespawnRulesInBlocksAndTicks() throws IOException {
        Despawn despawn = Despawn.tryFrom(entity("""
            "minecraft:despawn": {
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
        Despawn despawn = Despawn.tryFrom(entity("\"minecraft:despawn\": {\"despawn_from_distance\": {}}"));

        assertEquals(32, despawn.minDistance());
        assertEquals(128, despawn.maxDistance());
    }

    /** A pack writing its own filters replaces the standard rules rather than adding to them. */
    @Test
    void leavesTheDistanceRulesAloneWhenThePackWroteFilters() throws IOException {
        Despawn despawn = Despawn.tryFrom(entity("""
            "minecraft:despawn": {"filters": {"test": "is_daytime", "value": true}}
            """));

        assertTrue(despawn.filtered());
        assertFalse(despawn.despawnsAt(1000, 100000, 0));
    }

    /** `entity_types` says what a goal is about, and carries its reach in the same list. */
    @Test
    void readsWhatAGoalHuntsAndHowFarItReaches() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:behavior.nearest_attackable_target": {
              "priority": 2,
              "entity_types": [
                {"filters": {"test": "is_family", "subject": "other", "value": "player"}, "max_dist": 24}
              ]
            }
            """);

        TargetSelection hunted = TargetSelection.from(
            entity.behaviours().get("minecraft:behavior.nearest_attackable_target"),
            net.minecraft.world.entity.LivingEntity.class, 0);

        assertEquals(net.minecraft.world.entity.player.Player.class, hunted.kind());
        assertEquals(24, hunted.maxDistance());
    }

    /** A goal naming two families needs one class covering both, which is the wider of the two. */
    @Test
    void widensToTheKindThatCoversEveryFamilyTheGoalNames() throws IOException {
        ServerEntityDefinition entity = entity("""
            "minecraft:behavior.avoid_mob_type": {
              "entity_types": [
                {"filters": {"test": "is_family", "value": "player"}, "max_dist": 6},
                {"filters": {"test": "is_family", "value": "monster"}, "max_dist": 12}
              ]
            }
            """);

        TargetSelection avoided = TargetSelection.from(
            entity.behaviours().get("minecraft:behavior.avoid_mob_type"),
            net.minecraft.world.entity.monster.Monster.class, 8);

        assertEquals(net.minecraft.world.entity.monster.Monster.class, avoided.kind());
        assertEquals(12, avoided.maxDistance());
    }

    /** A goal naming nobody keeps what the caller chose rather than hunting everything alive. */
    @Test
    void keepsTheFallbackWhenAGoalNamesNoFamily() throws IOException {
        TargetSelection hunted = TargetSelection.from(null,
            net.minecraft.world.entity.player.Player.class, 16);

        assertEquals(net.minecraft.world.entity.player.Player.class, hunted.kind());
        assertEquals(16, hunted.maxDistance());
    }

    private ServerEntityDefinition entity(String components) throws IOException {
        Path file = directory.resolve("entities/thing.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            {"minecraft:entity": {"description": {"identifier": "tp:thing"}, "components": {%s}}}
            """.formatted(components));

        List<ServerEntityDefinition> read =
            ServerEntityDefinition.readAll(new BedrockPack(directory, null, "test.mcaddon"));
        assertEquals(1, read.size(), components);
        return read.get(0);
    }
}
