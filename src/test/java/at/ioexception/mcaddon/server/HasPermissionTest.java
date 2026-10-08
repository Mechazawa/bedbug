package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Bedrock's `haspermission` selector argument asks of a player, read from just after the `=`
 * the way `SelectorTags` hands it over.
 */
class HasPermissionTest {
    @Test
    void readsEachCategoryAndTheStateAskedForAndStopsWhereTheSelectorGoesOn() throws CommandSyntaxException {
        StringReader reader = new StringReader("{camera=disabled, movement=enabled},c=1]");
        HasPermission read = HasPermission.read(reader);

        assertEquals(Map.of(InputPermission.CAMERA, false, InputPermission.MOVEMENT, true), read.wanted());
        assertEquals(",c=1]", reader.getRemaining());
    }

    /** Each category answers for itself: a player whose movement is off still has its jump on. */
    @Test
    void asksEveryCategoryItNamesAndNoOther() throws CommandSyntaxException {
        HasPermission read = HasPermission.read(new StringReader("{jump=enabled,camera=disabled}"));
        Set<InputPermission> off = Set.of(InputPermission.CAMERA, InputPermission.MOVEMENT);

        assertTrue(read.answeredBy(permission -> !off.contains(permission)));
        assertFalse(read.answeredBy(permission -> permission != InputPermission.CAMERA
            && permission != InputPermission.JUMP));
        assertFalse(read.answeredBy(permission -> true));
    }

    @Test
    void refusesACategoryOrAStateBedrockDoesNotHave() {
        assertThrows(CommandSyntaxException.class, () -> HasPermission.read(new StringReader("{flying=enabled}")));
        assertThrows(CommandSyntaxException.class, () -> HasPermission.read(new StringReader("{camera=off}")));
    }
}
