package at.ioexception.mcaddon.resource;

import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.FunctionNames;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackSet;

import java.util.List;

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
}
