package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.pack.BedrockPack;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The functions every installed add-on ships, seen from the add-on whose command calls one.
 *
 * Bedrock names a function by its path under `functions/` alone and finds it in whichever loaded
 * behaviour pack has it. Java names one by a namespace as well, and each add-on's functions are
 * written under that add-on's own namespace, so a call has to be given the namespace of an add-on
 * that ships the path it names. The add-on issuing the call is asked first, so two add-ons shipping
 * the same path each reach their own, and the rest are asked in the order the packs were sorted in.
 */
public final class FunctionNames {
    public static final FunctionNames NONE = new FunctionNames(Map.of(), null);

    private final Map<String, Set<String>> paths;
    private final String issuer;

    private FunctionNames(Map<String, Set<String>> paths, String issuer) {
        this.paths = paths;
        this.issuer = issuer;
    }

    /** The functions of each add-on's behaviour packs, under the namespace that add-on was converted to. */
    public static FunctionNames of(Map<String, List<BedrockPack>> behavioursByNamespace) {
        Map<String, Set<String>> paths = new LinkedHashMap<>();
        behavioursByNamespace.forEach((namespace, behaviours) -> behaviours.forEach(pack ->
            pack.list("functions", ".mcfunction").forEach(file ->
                paths.computeIfAbsent(namespace, ignored -> new LinkedHashSet<>()).add(pathOf(pack, file)))));
        return new FunctionNames(Collections.unmodifiableMap(paths), null);
    }

    /** The same functions, asked about by the add-on writing under this namespace, or by none. */
    public FunctionNames issuedBy(String namespace) {
        return new FunctionNames(paths, namespace);
    }

    /**
     * The Java id a call by this name reaches, or null when no installed add-on ships it.
     *
     * A name already carrying a namespace, or naming a function tag, is written in Java's dialect
     * and is left as it stands.
     */
    public String qualify(String name) {
        if (name.contains(":") || name.startsWith("#")) return name;

        String path = Names.sanitise(name.startsWith("/") ? name.substring(1) : name);
        return Stream.concat(Stream.ofNullable(issuer), paths.keySet().stream())
            .filter(namespace -> paths.getOrDefault(namespace, Set.of()).contains(path))
            .findFirst()
            .map(namespace -> namespace + ":" + path)
            .orElse(null);
    }

    /**
     * Every function by its Java id, as one line.
     *
     * A converted function file names functions other add-ons ship, so what it was converted
     * against is part of what says it is out of date.
     */
    public String stamp() {
        Set<String> ids = new TreeSet<>();
        paths.forEach((namespace, names) -> names.forEach(path -> ids.add(namespace + ":" + path)));
        return String.join(",", ids);
    }

    /** The path a function file is written to under its add-on's namespace. */
    static String pathOf(BedrockPack pack, Path file) {
        String relative = pack.relative(file).toString().replace('\\', '/');
        return Names.sanitise(relative.substring("functions/".length(),
            relative.length() - ".mcfunction".length()));
    }
}
