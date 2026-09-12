package at.ioexception.mcaddon.convert;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Java entity a Bedrock entity id names.
 *
 * The registry decides, not the list, because a name Java once had may be gone: boats became one
 * type per wood, so `boat` has to fall through to `oak_boat` on a version that split them. A name no
 * candidate matches has no Java entity at all, and the caller drops the command rather than writing
 * a line Java's parser rejects.
 */
class BedrockEntityNamesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @CsvSource({
        "zombie, minecraft:zombie",
        "minecraft:zombie, minecraft:zombie",
        "zombie_pigman, minecraft:zombified_piglin",
        "minecraft:zombie_pigman, minecraft:zombified_piglin",
        "Villager_V2, minecraft:villager",
        "evocation_illager, minecraft:evoker",
        "tropicalfish, minecraft:tropical_fish",
        "xp_orb, minecraft:experience_orb",
    })
    void namesTheJavaEntity(String bedrock, String expected) {
        assertEquals(expected, BedrockEntityNames.java(bedrock));
    }

    /** Boats are one type per wood from 1.21.2 on, so the answer depends on the running version. */
    @Test
    void fallsThroughToTheSpellingTheRunningVersionHas() {
        assertTrue(List.of("minecraft:boat", "minecraft:oak_boat").contains(BedrockEntityNames.java("boat")),
            String.valueOf(BedrockEntityNames.java("boat")));
    }

    /** An add-on's own id belongs to whichever packs are loaded, so the list has no say over it. */
    @ParameterizedTest
    @ValueSource(strings = {"vn:reporter", "oreville_vn:townsfolk"})
    void leavesAnAddOnsOwnIdAlone(String bedrock) {
        assertEquals(bedrock, BedrockEntityNames.java(bedrock));
    }

    @ParameterizedTest
    @ValueSource(strings = {"balloon", "agent", "npc", "zombie_pigman_v9"})
    void answersNullWhenJavaHasNoSuchEntity(String bedrock) {
        assertNull(BedrockEntityNames.java(bedrock));
    }
}
