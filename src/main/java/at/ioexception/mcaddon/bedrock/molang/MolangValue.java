package at.ioexception.mcaddon.bedrock.molang;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A Molang value: a number, a string, a struct of named members, an entity or an array of entities.
 *
 * Strings are rare but real. The talking animations assign one to pick a mouth shape, and render
 * controllers compare against them, so collapsing everything to a number would lose that.
 *
 * A struct is defined by use: `v.location.x = 1` makes `v.location` one, and assigning it copies
 * every member. A value is never changed in place, so a copy shares nothing with what it was copied
 * from. A member nobody set reads as zero, and a struct read as a number is zero.
 *
 * An entity is held as a reference that `->` evaluates its right side against. An entity, and an
 * array of them, read as zero where a number is wanted.
 */
public record MolangValue(double number, String text, Map<String, MolangValue> members,
    Reference entity, List<Reference> entities) {
    public static final MolangValue ZERO = new MolangValue(0, null);
    public static final MolangValue ONE = new MolangValue(1, null);

    /** An entity a value refers to, which the side holding it knows how to read. */
    public interface Reference {
        /** The entity's scope as another entity may read it, or null once the entity is gone. */
        MolangEnvironment scope();
    }

    public MolangValue(double number, String text) {
        this(number, text, null, null, null);
    }

    /** Member names are lowercase, because Molang reads every name without regard to case. */
    public static MolangValue struct(Map<String, MolangValue> members) {
        return new MolangValue(0, null, Map.copyOf(members), null, null);
    }

    public static MolangValue of(Reference entity) {
        return new MolangValue(0, null, null, entity, null);
    }

    /** An array of entities, each once, in the order given. */
    public static MolangValue of(Collection<? extends Reference> entities) {
        return new MolangValue(0, null, null, null, List.copyOf(new LinkedHashSet<>(entities)));
    }

    public static MolangValue of(double number) {
        return new MolangValue(number, null);
    }

    public static MolangValue of(boolean value) {
        return value ? ONE : ZERO;
    }

    public static MolangValue of(String text) {
        return new MolangValue(0, text);
    }

    /** The shape of every struct Bedrock hands out for a point, a turn or a size. */
    public static MolangValue xyz(double x, double y, double z) {
        return struct(Map.of("x", of(x), "y", of(y), "z", of(z)));
    }

    public boolean isStruct() {
        return members != null;
    }

    /** The member at the end of a path of names, zero where any step of it is missing. */
    public MolangValue member(List<String> path) {
        MolangValue value = this;
        for (String name : path) {
            if (!value.isStruct()) return ZERO;
            value = value.members.getOrDefault(name, ZERO);
        }
        return value;
    }

    public boolean hasMember(List<String> path) {
        MolangValue value = this;
        for (String name : path) {
            if (!value.isStruct() || !value.members.containsKey(name)) return false;
            value = value.members.get(name);
        }
        return true;
    }

    /**
     * A copy with the member at the end of the path set, every struct on the way made where missing.
     *
     * A step that holds a number or a string becomes a struct, which is how a pack declares one:
     * `v.pose = 0; v.pose.t = 0; v.pose.t.x = 0;`.
     */
    public MolangValue with(List<String> path, MolangValue value) {
        if (path.isEmpty()) return value;

        Map<String, MolangValue> copy = isStruct() ? new LinkedHashMap<>(members) : new LinkedHashMap<>();
        String name = path.get(0);
        copy.put(name, copy.getOrDefault(name, ZERO).with(path.subList(1, path.size()), value));
        return struct(copy);
    }

    public boolean isText() {
        return text != null;
    }

    /** The scope of the entity this value refers to, or null where it refers to none still there. */
    public MolangEnvironment scope() {
        return entity == null ? null : entity.scope();
    }

    /**
     * Whether `??` takes this value rather than its fallback. A reference to an entity that is gone
     * is one Bedrock falls through on.
     */
    public boolean resolves() {
        return entity == null || entity.scope() != null;
    }

    /** The entities this value refers to that are still there: its own, or its array's. */
    public Stream<Reference> references() {
        Stream<Reference> held = entities != null ? entities.stream() : Stream.ofNullable(entity);
        return held.filter(reference -> reference.scope() != null);
    }

    /** `q.combine_entities`: every entity any argument refers to, each once. */
    public static MolangValue combined(List<MolangValue> arguments) {
        return of(arguments.stream().flatMap(MolangValue::references).toList());
    }

    /** `q.count`: an array counts as its elements and anything else as one. */
    public static int count(List<MolangValue> arguments) {
        return arguments.stream().mapToInt(value -> value.entities != null ? value.entities.size() : 1).sum();
    }

    public float asFloat() {
        return (float) number;
    }

    /**
     * Molang treats any non-zero number as true; a string is true when it is not empty, an entity
     * while it is there and an array while it holds one.
     */
    public boolean truthy() {
        if (entity != null) return resolves();
        if (entities != null) return !entities.isEmpty();
        return isText() ? !text.isEmpty() : number != 0;
    }

    public boolean matches(MolangValue other) {
        if (entity != null || other.entity != null) return entity != null && entity.equals(other.entity);
        if (isText() || other.isText()) return isText() && other.isText() && text.equals(other.text);
        return number == other.number;
    }

    /** `q.any`: whether any argument after the first equals the first. */
    public static boolean matchesAny(List<MolangValue> arguments) {
        return arguments.size() > 1 && arguments.stream().skip(1).anyMatch(arguments.get(0)::matches);
    }

    /** `q.all`: whether every argument after the first equals the first. */
    public static boolean matchesAll(List<MolangValue> arguments) {
        return arguments.size() > 1 && arguments.stream().skip(1).allMatch(arguments.get(0)::matches);
    }
}
