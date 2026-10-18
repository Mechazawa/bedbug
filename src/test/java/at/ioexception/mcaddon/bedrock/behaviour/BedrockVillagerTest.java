package at.ioexception.mcaddon.bedrock.behaviour;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Java villager read the way Bedrock's `villager_v2` numbers and announces itself. */
class BedrockVillagerTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000007");

    @ParameterizedTest
    @CsvSource({
        "none, plains, 0, 0",
        "farmer, snow, 1, 4",
        "shepherd, desert, 3, 1",
        "mason, taiga, 13, 6",
        "nitwit, swamp, 14, 5",
        "some_mod_job, some_mod_biome, 0, 0",
    })
    void numbersItsProfessionAndBiomeTheWayBedrockDoes(String profession, String type, int variant, int mark) {
        BedrockVillager villager = new BedrockVillager(profession, type, 1, ID);
        assertEquals(variant, villager.component("variant"));
        assertEquals(mark, villager.component("mark_variant"));
        assertNull(villager.component("color"));
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "3, 2", "5, 4"})
    void tradesAtTheTierItsBadgeIsIndexedBy(int level, int tier) {
        assertEquals(tier, new BedrockVillager("farmer", "plains", level, ID).tradeTier());
    }

    @Test
    void wearsOneOfSixFacesThatStaysWithIt() {
        IntStream.range(0, 50).mapToObj(seed -> new BedrockVillager("none", "plains", 1, new UUID(seed, seed * 31L)))
            .forEach(villager -> {
                int skin = villager.component("skin_id");
                assertTrue(skin >= 0 && skin < 6, "skin " + skin);
                assertEquals(skin, new BedrockVillager("farmer", "snow", 1, villager.id()).component("skin_id"));
            });
    }

    @ParameterizedTest
    @CsvSource({
        "none, farmer, minecraft:become_farmer",
        "farmer, none, minecraft:become_unskilled",
        "none, shepherd, minecraft:become_sheperd",
        "none, weaponsmith, minecraft:become_weaponsmith",
    })
    void announcesANewProfessionByTheEventBedrockRaises(String before, String after, String event) {
        assertEquals(event, new BedrockVillager(after, "plains", 1, ID)
            .eventFrom(new BedrockVillager(before, "plains", 1, ID)));
    }

    @Test
    void announcesNothingWhenOnlyTheBiomeOrNothingChanged() {
        BedrockVillager farmer = new BedrockVillager("farmer", "plains", 1, ID);
        assertNull(new BedrockVillager("farmer", "snow", 1, ID).eventFrom(farmer));
        assertNull(farmer.eventFrom(farmer));
    }

    @Test
    void announcesNothingForANitwitOrAJobBedrockHasNot() {
        BedrockVillager unskilled = new BedrockVillager("none", "plains", 1, ID);
        assertNull(new BedrockVillager("nitwit", "plains", 1, ID).eventFrom(unskilled));
        assertNull(new BedrockVillager("some_mod_job", "plains", 1, ID).eventFrom(unskilled));
    }
}
