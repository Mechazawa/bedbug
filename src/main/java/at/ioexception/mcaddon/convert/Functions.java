package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bedrock `.mcfunction` files, rewritten into the dialect Java's parser reads.
 *
 * The two command languages are close but not the same. What differs and is worth translating is the
 * selector arguments: Bedrock says `r` where Java says `distance`, and `c` where Java says `limit`.
 * A command Java has no equivalent for is left out with its name reported, because a function that
 * half runs is worse than one that says which line it dropped.
 */
public final class Functions {
    /** Commands Bedrock has and Java does not. `scriptevent` is not among them: this mod adds it. */
    private static final Set<String> BEDROCK_ONLY = Set.of(
        "testfor", "testforblock", "testforblocks", "titleraw", "fog", "camerashake", "camera",
        "playanimation", "music", "mobevent", "structure", "event", "inputpermission", "hud",
        "dialogue", "script", "toggledownfall", "immutableworld", "wsserver", "connect");

    private static final Pattern SELECTOR = Pattern.compile("@[aeprsn]\\[([^\\]]*)\\]");

    private Functions() {
    }

    /** The names of the functions that ran, so the caller can build the tick tag from them. */
    public static List<String> convert(BedrockPack pack, Path root, String namespace,
        Set<String> unsupported) throws IOException {
        List<String> converted = new ArrayList<>();
        String directory = at.ioexception.mcaddon.platform.Platform.modernDataPaths() ? "function" : "functions";

        for (Path file : pack.list("functions", ".mcfunction")) {
            String relative = pack.root().relativize(file).toString().replace('\\', '/');
            String inside = Names.sanitise(relative.substring("functions/".length(), relative.length() - 11));

            List<String> lines = new ArrayList<>();
            for (String line : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
                String rewritten = line(line.strip(), unsupported);
                if (rewritten != null) lines.add(rewritten);
            }

            Path target = root.resolve("data").resolve(namespace).resolve(directory)
                .resolve(inside + ".mcfunction");
            Files.createDirectories(target.getParent());
            Files.writeString(target, String.join("\n", lines), StandardCharsets.UTF_8);
            converted.add(namespace + ":" + inside);
        }

        writeTickTag(pack, root, namespace, converted);
        return converted;
    }

    /** Null for a line Java would reject, which is a comment, a blank, or a Bedrock-only command. */
    private static String line(String source, Set<String> unsupported) {
        if (source.isEmpty() || source.startsWith("#")) return source.isEmpty() ? null : source;

        String command = source.startsWith("/") ? source.substring(1) : source;
        String name = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (BEDROCK_ONLY.contains(name)) {
            unsupported.add("command " + name);
            return null;
        }
        return selectors(command);
    }

    /**
     * Rewrites the selector arguments whose names differ.
     *
     * `r` and `rm` are a radius and a minimum radius, which Java writes as one `distance` range.
     */
    private static String selectors(String command) {
        Matcher matcher = SELECTOR.matcher(command);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String inside = matcher.group(1);
            String radius = null;
            String minimumRadius = null;
            List<String> arguments = new ArrayList<>();

            for (String argument : inside.split(",")) {
                String[] parts = argument.split("=", 2);
                if (parts.length != 2) {
                    if (!argument.isBlank()) arguments.add(argument);
                    continue;
                }
                String key = parts[0].strip();
                String value = parts[1].strip();

                switch (key) {
                    case "r" -> radius = value;
                    case "rm" -> minimumRadius = value;
                    case "c" -> arguments.add((value.startsWith("-") ? "limit=" + value.substring(1)
                        : "limit=" + value) + (value.startsWith("-") ? ",sort=furthest" : ""));
                    case "m" -> arguments.add("gamemode=" + gameMode(value));
                    case "l" -> arguments.add("level=.." + value);
                    case "lm" -> arguments.add("level=" + value + "..");
                    case "rx" -> arguments.add("x_rotation=.." + value);
                    case "rxm" -> arguments.add("x_rotation=" + value + "..");
                    case "ry" -> arguments.add("y_rotation=.." + value);
                    case "rym" -> arguments.add("y_rotation=" + value + "..");
                    case "family" -> arguments.add("tag=" + value);
                    default -> arguments.add(key + "=" + value);
                }
            }

            if (radius != null || minimumRadius != null) {
                arguments.add("distance=" + (minimumRadius == null ? "" : minimumRadius)
                    + ".." + (radius == null ? "" : radius));
            }

            String replacement = matcher.group().substring(0, 2)
                + (arguments.isEmpty() ? "" : "[" + String.join(",", arguments) + "]");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String gameMode(String bedrock) {
        return switch (bedrock) {
            case "0", "s", "survival" -> "survival";
            case "1", "c", "creative" -> "creative";
            case "2", "a", "adventure" -> "adventure";
            case "3", "spectator" -> "spectator";
            default -> bedrock;
        };
    }

    /**
     * `functions/tick.json` names the functions Bedrock runs every tick; Java uses a function tag.
     */
    private static void writeTickTag(BedrockPack pack, Path root, String namespace, List<String> converted)
        throws IOException {
        Path file = pack.file("functions/tick.json");
        if (!Files.isRegularFile(file)) return;

        try {
            JsonArray values = new JsonArray();
            for (JsonElement value : Json.list(Json.parseObject(file), "values")) {
                if (!value.isJsonPrimitive()) continue;
                String name = namespace + ":" + Names.sanitise(value.getAsString());
                if (converted.contains(name)) values.add(name);
            }
            if (values.isEmpty()) return;

            JsonObject tag = new JsonObject();
            tag.add("values", values);

            String directory = at.ioexception.mcaddon.platform.Platform.modernDataPaths()
                ? "tags/function" : "tags/functions";
            Path target = root.resolve("data/minecraft").resolve(directory).resolve("tick.json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, tag.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
        }
    }
}
