package at.ioexception.mcaddon.pack;

import java.util.ArrayList;
import java.util.List;

/** Every pack that loaded, in load order, and everything that went wrong while finding them. */
public record PackSet(List<BedrockPack> packs, List<String> problems) {
    public List<BedrockPack> withRole(PackRole role) {
        return packs.stream().filter(pack -> pack.role() == role).toList();
    }

    /** Lines for the log, `/mcaddon packs` and the add-on screen, which say the same thing. */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        if (packs.isEmpty()) {
            lines.add("No packs found in " + McAddonPaths.addons() + ".");
        }

        packs.stream().map(PackSet::report).forEach(lines::addAll);
        for (String problem : problems) lines.add("problem: " + problem);
        return lines;
    }

    /** What loaded of one pack: its role, name, version and source, its credits, form and script APIs. */
    public static List<String> report(BedrockPack pack) {
        PackManifest manifest = pack.manifest();
        List<String> modules = manifest.modules().stream()
            .map(module -> module.type().name().toLowerCase(java.util.Locale.ROOT))
            .toList();
        List<String> lines = new ArrayList<>();
        lines.add("%s %s (%s) from %s, modules %s".formatted(
            pack.role() == PackRole.RESOURCE ? "resources" : "behaviour",
            pack.displayName(), manifest.version(), pack.origin(), modules));

        if (!manifest.metadata().isEmpty()) lines.add("    " + credits(manifest.metadata()));
        if (pack.subpackName() != null) lines.add("    loaded as " + pack.subpackName());

        for (PackManifest.ModuleDependency dependency : manifest.moduleDependencies()) {
            lines.add("    script API " + dependency.moduleName() + " " + dependency.version());
        }
        return lines;
    }

    /** Who a pack says wrote it, under what licence and where it lives, in whichever parts it states. */
    private static String credits(PackManifest.Metadata metadata) {
        List<String> parts = new ArrayList<>();
        if (!metadata.authors().isEmpty()) parts.add("by " + String.join(", ", metadata.authors()));
        if (metadata.license() != null) parts.add(metadata.license());
        if (metadata.url() != null) parts.add(metadata.url());
        return String.join(", ", parts);
    }
}
