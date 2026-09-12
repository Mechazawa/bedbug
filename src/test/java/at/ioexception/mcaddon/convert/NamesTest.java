package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.platform.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Bedrock name becomes once a resource location has to accept it.
 *
 * Bedrock allows uppercase, spaces, colons and anything else a file name can hold, and a resource
 * location allows none of them. The substitution has to be one character for one character, because
 * dropping the rejected ones would let two different Bedrock names land on the same Java name.
 */
class NamesTest {
    @ParameterizedTest
    @CsvSource({
        "'Oreville VN', oreville_vn",
        "'vn:reporter', vn_reporter",
        "'Textures/Blocks/Stone.PNG', textures/blocks/stone.png",
        "'a-b_c.d/e', a-b_c.d/e",
        "'é', _",
        "'100%', 100_",
    })
    void replacesEveryCharacterAResourceLocationRejects(String source, String expected) {
        assertEquals(expected, Names.sanitise(source));
    }

    @Test
    void keepsTwoNamesApartThatDifferOnlyInRejectedCharacters() {
        assertNotEquals(Names.sanitise("a b"), Names.sanitise("a  b"));
        assertNotEquals(Names.sanitise("a:b"), Names.sanitise("ab"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Oreville VN", "vn:reporter", "Textures/Blocks/Stone.PNG", "spaces and CAPS!", "é"})
    void producesAPathAResourceLocationAccepts(String source) {
        String sanitised = Names.sanitise(source);

        assertTrue(sanitised.matches("[a-z0-9_./-]+"), sanitised);
        assertDoesNotThrow(() -> Platform.id("mcaddon", sanitised), sanitised);
    }
}
