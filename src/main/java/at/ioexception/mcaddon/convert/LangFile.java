package at.ioexception.mcaddon.convert;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Bedrock `.lang` file, which looks like a properties file and is not one.
 *
 * Three rules a properties reader gets wrong: the line splits on the first `=` only because values
 * contain more of them, a tab starts a comment so the value ends there, and the value keeps its
 * surrounding spaces because leading spaces are meaningful in subtitles.
 */
public final class LangFile {
    private LangFile() {
    }

    public static Map<String, String> read(Path file) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        // Decoding the bytes directly rather than readAllLines: a pack with one bad byte sequence
        // should lose that character, not the whole file.
        String[] lines = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n", -1);

        for (String raw : lines) {
            String line = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;

            int split = line.indexOf('=');
            if (split < 0) continue;

            String value = line.substring(split + 1);
            int comment = value.indexOf('\t');
            if (comment >= 0) value = value.substring(0, comment);

            entries.putIfAbsent(line.substring(0, split).trim(), value);
        }
        return entries;
    }
}
