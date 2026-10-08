package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.pack.BedrockPack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which add-on's function a Bedrock call by path reaches.
 *
 * Two add-ons are installed, `first` before `second`, and both ship `shared`. A call is answered by
 * the add-on making it where that add-on has the function, and by the earliest that has it where it
 * does not, which is how Bedrock finds a function in whichever loaded pack ships it.
 */
class FunctionNamesTest {
    @TempDir
    Path directory;

    private FunctionNames functions;

    @BeforeEach
    void install() throws IOException {
        Map<String, List<BedrockPack>> installed = new LinkedHashMap<>();
        installed.put("first", List.of(pack("one", "shared", "tools/only_first")));
        installed.put("second", List.of(pack("two", "shared", "only_second")));
        functions = FunctionNames.of(installed);
    }

    @Test
    void answersACallWithTheCallersOwnFunctionFirst() {
        assertEquals("second:shared", functions.issuedBy("second").qualify("shared"));
        assertEquals("first:shared", functions.issuedBy("first").qualify("shared"));
    }

    @Test
    void findsAFunctionOnlyAnotherAddOnShips() {
        assertEquals("first:tools/only_first", functions.issuedBy("second").qualify("tools/only_first"));
        assertEquals("second:only_second", functions.issuedBy("first").qualify("only_second"));
    }

    /** A script shared by several add-ons says nothing about which one wrote a line. */
    @Test
    void asksTheAddOnsInTheirOrderWhenNoneIsTheCaller() {
        assertEquals("first:shared", functions.issuedBy(null).qualify("shared"));
    }

    @Test
    void readsTheNameTheWayTheFileWasWritten() {
        assertEquals("first:tools/only_first", functions.issuedBy("first").qualify("Tools/Only_First"));
    }

    @Test
    void answersNothingForAFunctionNoAddOnShips() {
        assertNull(functions.issuedBy("first").qualify("missing"));
    }

    /**
     * A converted line runs with nobody known to have written it, so a call the caller's own add-on
     * would answer differently is written as the id it reaches, in each place a line names a function.
     */
    @Test
    void writesTheIdOfACallTheCallerAnswersDifferently() {
        FunctionNames second = functions.issuedBy("second");
        assertEquals("execute as @a run function second:shared", second.pinned("execute as @a run function shared"));
        assertEquals("schedule delay add second:shared 20 append", second.pinned("schedule delay add shared 20 append"));
        assertEquals("schedule clear second:shared", second.pinned("schedule clear shared"));
    }

    @Test
    void leavesACallAnyAddOnWouldAnswerTheSameWayAsItWasWritten() {
        assertEquals("function shared", functions.issuedBy("first").pinned("function shared"));
        assertEquals("function tools/only_first", functions.issuedBy("second").pinned("function tools/only_first"));
        assertEquals("function missing", functions.issuedBy("second").pinned("function missing"));
        assertEquals("say shared", functions.issuedBy("second").pinned("say shared"));
    }

    /** A converted file names other add-ons' functions, so a change to their list has to show. */
    @Test
    void stampsTheFunctionsOfEveryAddOn() throws IOException {
        Map<String, List<BedrockPack>> fewer = new LinkedHashMap<>();
        fewer.put("first", List.of(pack("three", "shared", "tools/only_first")));
        assertNotEquals(functions.stamp(), FunctionNames.of(fewer).stamp());
    }

    private BedrockPack pack(String name, String... functionPaths) throws IOException {
        Path root = directory.resolve(name);
        for (String path : functionPaths) {
            Path file = root.resolve("functions").resolve(path + ".mcfunction");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "say " + path);
        }
        return new BedrockPack(root, null, name + ".mcaddon");
    }
}
