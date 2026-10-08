package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.state.AddonState;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The entity properties Bedrock's `has_property` selector argument asks an entity about.
 *
 * `@e[has_property={tp:mood="calm",tp:level=2..4,tp:lit=true,property=tp:owner}]` keeps an entity
 * whose properties answer every question. A value is a boolean, a number or a range of numbers, or the
 * text of an enum value, and a `!` in front of it asks for any other value. `property=` asks only that
 * the entity has the property it names, and `property=!` that it has not. An entity without a
 * property answers no question about its value, turned round or not.
 *
 * The values are the ones this mod keeps for each entity, where a pack's events and scripts write
 * them, and a property nothing has written yet holds the default its pack declared.
 */
public record HasProperty(List<Question> questions) implements Predicate<Entity> {
    /** The key asking after a property's presence rather than its value. */
    private static final String PRESENCE = "property";

    /** One question about one property: its value, or with no value whether the entity has it. */
    public record Question(String property, String value, boolean negated) {
        boolean answeredBy(Object held) {
            if (value == null) return (held != null) != negated;
            return held != null && matches(held) != negated;
        }

        private boolean matches(Object held) {
            if (held instanceof Boolean flag) return value.equalsIgnoreCase(String.valueOf(flag));
            if (held instanceof Number number) return contains(number.doubleValue());
            return value.equals(String.valueOf(held));
        }

        /** Bedrock's range, `3`, `2..4`, `..4` or `2..`, with no reading at all for a word that is none. */
        private boolean contains(double number) {
            int dots = value.indexOf("..");
            try {
                if (dots < 0) return Double.parseDouble(value) == number;
                String low = value.substring(0, dots).strip();
                String high = value.substring(dots + 2).strip();
                return (low.isEmpty() || number >= Double.parseDouble(low))
                    && (high.isEmpty() || number <= Double.parseDouble(high));
            } catch (NumberFormatException notANumber) {
                return false;
            }
        }
    }

    static HasProperty read(StringReader reader) throws CommandSyntaxException {
        return new HasProperty(BracedArguments.read(reader).stream()
            .map(field -> {
                boolean negated = field.getValue().startsWith("!");
                String value = unquoted((negated ? field.getValue().substring(1) : field.getValue()).strip());
                return field.getKey().equals(PRESENCE)
                    ? new Question(value, null, negated) : new Question(field.getKey(), value, negated);
            })
            .toList());
    }

    /** Whether an entity whose properties hold these values answers every question. */
    public boolean answeredBy(Function<String, Object> valueOf) {
        return questions.stream().allMatch(question -> question.answeredBy(valueOf.apply(question.property())));
    }

    @Override
    public boolean test(Entity entity) {
        AddonState state = ServerHooks.state();
        return answeredBy(property -> valueOf(entity, state, property));
    }

    private static Object valueOf(Entity entity, AddonState state, String property) {
        Object held = state == null ? null : state.property(entity.getUUID(), property);
        if (held != null) return held;

        ServerEntityDefinition definition = AddonEntities.definitionOf(entity.getType());
        ServerEntityDefinition.Property declared = definition == null ? null : definition.properties().get(property);
        return declared == null ? null : declared.initial();
    }

    private static String unquoted(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
            ? value.substring(1, value.length() - 1) : value;
    }
}
