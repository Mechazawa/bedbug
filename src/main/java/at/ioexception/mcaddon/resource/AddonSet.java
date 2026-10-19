package at.ioexception.mcaddon.resource;

import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.FunctionNames;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackSet;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A set of add-ons after conversion: the packs as read, what they were converted into, and the
 * functions they ship, which a function line in any of them may call.
 *
 * The add-ons a player installed are one set; the add-ons a server plays with are another.
 */
public record AddonSet(PackSet packs, List<ConvertedPack> converted, FunctionNames functions) {
    public static final AddonSet EMPTY = new AddonSet(new PackSet(List.of(), List.of()), List.of(),
        FunctionNames.NONE);

    /** The namespace the add-on this pack came out of was converted to, or null for a pack of none. */
    public String namespaceOf(BedrockPack pack) {
        return converted.stream()
            .filter(one -> one.pack().origin().equals(pack.origin()))
            .map(ConvertedPack::namespace)
            .findFirst()
            .orElse(null);
    }

    /**
     * The add-ons of this set whose converted pack passes, in the same order and with the same
     * functions. Nothing is converted again, and the packs of an add-on that is left out go with it.
     */
    public AddonSet only(Predicate<ConvertedPack> keep) {
        List<ConvertedPack> kept = converted.stream().filter(keep).toList();
        Set<String> dropped = converted.stream()
            .filter(keep.negate())
            .flatMap(one -> Stream.concat(Stream.of(one.pack()), one.behaviours().stream()))
            .map(BedrockPack::origin)
            .collect(Collectors.toSet());
        List<BedrockPack> remaining = packs.packs().stream()
            .filter(pack -> !dropped.contains(pack.origin()))
            .toList();
        return new AddonSet(new PackSet(remaining, packs.problems()), kept, functions);
    }
}
