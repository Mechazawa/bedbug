package at.ioexception.mcaddon.server;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Bedrock's `hasitem` selector argument asks for, read the way `SelectorTags` hands
 * it over: from just after the `=`, leaving whatever follows the value for the parser.
 */
class HasItemTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void readsOneItemAndStopsWhereTheSelectorGoesOn() throws CommandSyntaxException {
        StringReader reader = new StringReader("{item=diamond, location=slot.weapon.mainhand},c=1]");
        List<HasItem> read = HasItem.readAll(reader);

        assertEquals(1, read.size());
        assertEquals(Items.DIAMOND, read.get(0).item());
        assertEquals("slot.weapon.mainhand", read.get(0).location());
        assertEquals(",c=1]", reader.getRemaining());
    }

    @Test
    void readsAListOfItemsEveryOneOfWhichIsAsked() throws CommandSyntaxException {
        List<HasItem> read = HasItem.readAll(new StringReader("[{item=apple},{item=minecraft:stone}]"));
        assertEquals(List.of(Items.APPLE, Items.STONE), read.stream().map(HasItem::item).toList());
    }

    /** Leaving the quantity out asks for at least one, so an empty inventory does not match. */
    @Test
    void asksForAtLeastOneWhenNoQuantityIsGiven() throws CommandSyntaxException {
        HasItem.Range quantity = HasItem.readAll(new StringReader("{item=apple}")).get(0).quantity();
        assertFalse(quantity.contains(0));
        assertTrue(quantity.contains(1));
    }

    @Test
    void readsBedrocksRanges() {
        assertTrue(HasItem.Range.parse("2..4", null).contains(3));
        assertFalse(HasItem.Range.parse("2..4", null).contains(5));
        assertTrue(HasItem.Range.parse("..4", null).contains(-1));
        assertTrue(HasItem.Range.parse("3", null).contains(3));
        assertFalse(HasItem.Range.parse("!0", null).contains(0));
        assertTrue(HasItem.Range.parse("!0", null).contains(2));
        assertFalse(HasItem.Range.parse("!1..", null).contains(1));
    }

    /** An item this version has not got is refused, rather than matching nothing in silence. */
    @Test
    void refusesAnItemTheGameDoesNotHave() {
        assertThrows(CommandSyntaxException.class,
            () -> HasItem.readAll(new StringReader("{item=tp:not_an_item}")));
    }

    @Test
    void refusesALocationItHasNoSlotFor() {
        assertThrows(CommandSyntaxException.class,
            () -> HasItem.readAll(new StringReader("{item=apple,location=slot.saddle}")));
    }
}
