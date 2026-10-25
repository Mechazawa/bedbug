package at.ioexception.mcaddon.convert;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which Java sound event a Bedrock sound name plays. */
class BedrockSoundNamesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @ParameterizedTest
    @CsvSource({
        "mob.sheep.shear, minecraft:entity.sheep.shear",
        "mob.horse.wood, minecraft:entity.horse.step_wood",
    })
    void mapsAMobSoundToTheEntitySound(String bedrock, String java) {
        assertEquals(java, BedrockSoundNames.java(bedrock));
    }

    /** No add-on namespace is what a script has while several add-ons ship one. */
    @ParameterizedTest
    @CsvSource(value = {"tp", "null"}, nullValues = "null")
    void aScriptPlaysABedrockSoundNameWithOrWithoutItsAddOn(String namespace) {
        assertEquals("minecraft:entity.horse.step_wood",
            BedrockSoundNames.played("mob.horse.wood", namespace).toString());
    }
}
