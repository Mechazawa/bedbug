package at.ioexception.mcaddon.server;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A translated line's `hasitem` arguments, each turned into a tag Java's own selector reads.
 *
 * The item lookup asks the registry, hence the bootstrap.
 */
class CarriedItemsTest {
    private static final Pattern TAG = Pattern.compile("mcaddon\\.hasitem\\.\\d+\\.\\d+");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void asksJavasSelectorForATagInPlaceOfTheArgument() throws CommandSyntaxException {
        String line = CarriedItems.tryFrom(
            "execute as @e[type=minecraft:pig,hasitem={item=apple,quantity=2..},limit=1] run say hi").line();

        Matcher tag = TAG.matcher(line);
        assertTrue(tag.find(), line);
        assertEquals("execute as @e[type=minecraft:pig,tag=" + tag.group() + ",limit=1] run say hi", line);
    }

    /** Each selector asks its own question, so each gets a tag of its own. */
    @Test
    void givesEachArgumentInTheLineATagOfItsOwn() throws CommandSyntaxException {
        String line = CarriedItems.tryFrom(
            "execute if entity @s[hasitem=[{item=apple},{item=stone}]] run kill @e[hasitem={item=apple}]").line();

        Matcher tags = TAG.matcher(line);
        assertTrue(tags.find(), line);
        String first = tags.group();
        assertTrue(tags.find(), line);
        assertNotEquals(first, tags.group());
        assertEquals("execute if entity @s[tag=" + first + "] run kill @e[tag=" + tags.group() + "]", line);
    }

    /** A line a function runs while its caller's line is still open must not share the caller's tags. */
    @Test
    void namesEveryRunOfOneLineDifferently() throws CommandSyntaxException {
        String line = "kill @e[hasitem={item=apple}]";
        assertNotEquals(CarriedItems.tryFrom(line).line(), CarriedItems.tryFrom(line).line());
    }

    @Test
    void leavesALineAskingAboutNoInventoryToJava() throws CommandSyntaxException {
        assertNull(CarriedItems.tryFrom("kill @e[type=minecraft:pig,tag=hasitem]"));
        assertNull(CarriedItems.tryFrom("say hasitem={item=apple}"));
    }

    @Test
    void refusesAnArgumentItCannotRead() {
        assertThrows(CommandSyntaxException.class, () -> CarriedItems.tryFrom("kill @e[hasitem={item=tp:nothing}]"));
    }
}
