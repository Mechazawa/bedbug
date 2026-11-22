package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The queries a block's Molang reads its own states through, answered from `states`, which gives a
 * state's value by its name or null when the block has no such state.
 *
 * `block_property` and `has_block_property` are the names packs wrote up to format 1.20.10, and
 * answer the same as `block_state` and `has_block_state`.
 */
public record BlockStateQueries(Function<String, MolangValue> states) implements MolangEnvironment.Queries {

    /** The states one combination of a block's declared values stands for. */
    public static BlockStateQueries of(Map<String, JsonElement> chosen) {
        return new BlockStateQueries(name -> {
            JsonElement value = chosen.get(name);
            return value == null ? null : value(value);
        });
    }

    public static MolangValue value(JsonElement value) {
        if (!value.isJsonPrimitive()) return MolangValue.ZERO;
        if (value.getAsJsonPrimitive().isBoolean()) return MolangValue.of(value.getAsBoolean());
        if (value.getAsJsonPrimitive().isNumber()) return MolangValue.of(value.getAsDouble());
        return MolangValue.of(value.getAsString());
    }

    public MolangEnvironment environment() {
        MolangEnvironment environment = new MolangEnvironment();
        environment.setQueries(this);
        return environment;
    }

    @Override
    public MolangValue query(String name, List<MolangValue> arguments) {
        if (arguments.isEmpty()) return MolangValue.ZERO;
        MolangValue state = states.apply(arguments.get(0).text());
        return switch (name) {
            case "block_state", "block_property" -> state == null ? MolangValue.ZERO : state;
            case "has_block_state", "has_block_property" -> MolangValue.of(state != null);
            default -> MolangValue.ZERO;
        };
    }
}
