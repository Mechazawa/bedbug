package at.ioexception.mcaddon.convert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockEffectNamesTest {
    @Test
    void renamesTheEffectsTheEditionsSpellApart() {
        assertEquals("hero_of_the_village", BedrockEffectNames.toJava("village_hero"));
        assertEquals("village_hero", BedrockEffectNames.toBedrock("hero_of_the_village"));
        assertEquals("speed", BedrockEffectNames.toJava("speed"));
    }
}
