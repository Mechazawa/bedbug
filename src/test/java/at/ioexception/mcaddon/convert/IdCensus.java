package at.ioexception.mcaddon.convert;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Bedrock item and block ids through this mod's own mapping, for `tools/census/ids.py`.
 *
 * The mapping asks the registry which spellings this version has, so the census has to run it
 * rather than read it, and this is the half that can. Each input line is a kind and an id; each
 * output line adds the Java id the mapping answered with and whether a miss is one Bedrock alone
 * has, which is a decided gap rather than an unanswered one.
 */
public final class IdCensus {
    private static final Pattern DATA = Pattern.compile("(.+):(-?\\d+)");

    private IdCensus() {
    }

    public static void main(String[] arguments) throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> answered = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(arguments[0]), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t");
            if (fields.length < 2) continue;

            String java = fields[0].equals("block") ? block(fields[1]) : BedrockItemNames.java(fields[1]);
            boolean own = fields[0].equals("block") ? BedrockBlockNames.bedrockOnly(fields[1])
                : BedrockItemNames.bedrockOnly(fields[1]);
            String status = java != null ? "java" : own ? "bedrock-only" : "none";
            answered.add(fields[0] + "\t" + fields[1] + "\t" + (java == null ? "" : java) + "\t" + status);
        }
        Files.write(Path.of(arguments[1]), answered, StandardCharsets.UTF_8);
        System.exit(0);
    }

    /** A block id, with a data value after it where a pack wrote one. */
    private static String block(String bedrock) {
        Matcher data = DATA.matcher(bedrock);
        BedrockBlockNames.Resolved resolved = data.matches()
            ? BedrockBlockNames.state(data.group(1), Integer.parseInt(data.group(2)), ignored -> { })
            : BedrockBlockNames.state(bedrock, Map.of(), ignored -> { });
        if (resolved != null) return resolved.command();
        // A block this mod builds is registered by the mod, which a bootstrapped registry lacks.
        String built = BedrockBlockNames.java(bedrock);
        return built != null && BedrockBlockNames.BUILT_BACK.containsKey(built) ? built : null;
    }
}
