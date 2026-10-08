package at.ioexception.mcaddon.server;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The `{key=value, ...}` a Bedrock selector argument writes its own arguments in, in written order.
 *
 * A key may come twice, `has_property` asking after two properties at once, so these are pairs
 * rather than a map. A value runs to the next comma or closing brace and keeps the quotes and the
 * `!` it was written with, which each argument reads in its own way.
 */
final class BracedArguments {
    private BracedArguments() {
    }

    static List<Map.Entry<String, String>> read(StringReader reader) throws CommandSyntaxException {
        reader.skipWhitespace();
        reader.expect('{');
        List<Map.Entry<String, String>> read = new ArrayList<>();
        while (true) {
            reader.skipWhitespace();
            if (reader.canRead() && reader.peek() == '}') break;
            String key = until(reader, "=");
            reader.expect('=');
            read.add(Map.entry(key, until(reader, ",}")));
            if (reader.canRead() && reader.peek() == ',') reader.skip();
        }
        reader.skip();
        return read;
    }

    /** What the reader holds up to the first of these characters, which it leaves unread. */
    private static String until(StringReader reader, String stops) {
        int start = reader.getCursor();
        while (reader.canRead() && stops.indexOf(reader.peek()) < 0) reader.skip();
        return reader.getString().substring(start, reader.getCursor()).strip();
    }
}
