package at.ioexception.mcaddon.server;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import java.util.List;

/**
 * Bedrock's `basic_string`: a quoted string, or everything up to the next space.
 *
 * Brigadier's own word stops at a colon, and the names a Bedrock line carries in this position, an
 * entity event or a name tag, carry a namespace as often as not. The tree it is used in is read on the
 * server alone, so the type is never sent to a client.
 */
public final class BasicString implements ArgumentType<String> {
    private static final BasicString INSTANCE = new BasicString();

    private BasicString() {
    }

    public static BasicString basicString() {
        return INSTANCE;
    }

    public static String get(CommandContext<?> command, String name) {
        return command.getArgument(name, String.class);
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead() && StringReader.isQuotedStringStart(reader.peek())) return reader.readQuotedString();

        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') reader.skip();
        if (reader.getCursor() == start) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedSymbol().createWithContext(reader, "string");
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    @Override
    public List<String> getExamples() {
        return List.of("word", "tp:event", "\"two words\"");
    }
}
