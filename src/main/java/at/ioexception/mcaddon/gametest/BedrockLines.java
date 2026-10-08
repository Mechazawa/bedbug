package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.CommandReport;
import at.ioexception.mcaddon.server.PackCommand;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock lines read and run by the interpreter `/bedrock` hands them to, for a test to ask what
 * came of each.
 *
 * A line that does not translate or does not read fails the test that wrote it; one the game read
 * and then declined is an answer, which the test reads off what the line returned.
 */
final class BedrockLines implements CommandReport {
    private final List<String> failures = new ArrayList<>();

    private BedrockLines() {
    }

    static void parses(GameTestHelper helper, String line) {
        CommandSourceStack source = helper.getLevel().getServer().createCommandSourceStack();
        try {
            Platform.ReadLine read = PackCommand.tryFrom(null, line).read(source, new BedrockLines());
            read.finish();
        } catch (CommandSyntaxException unread) {
            helper.fail("/bedrock " + line + " does not read: " + unread.getMessage());
        }
    }

    static int run(GameTestHelper helper, String line) {
        return run(helper, helper.getLevel().getServer().createCommandSourceStack(), line);
    }

    static int run(GameTestHelper helper, CommandSourceStack source, String line) {
        BedrockLines report = new BedrockLines();
        int returned = PackCommand.tryFrom(null, line).run(source, report);
        helper.assertTrue(report.failures.isEmpty(), "/bedrock " + line + " did not run: " + report.failures);
        return returned;
    }

    @Override
    public void untranslatable(String what) {
        failures.add(what);
    }

    @Override
    public void unreadable(String command, String reason) {
        failures.add(command + ": " + reason);
    }

    @Override
    public void declined(String command, String reason) {
    }
}
