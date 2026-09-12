package at.ioexception.mcaddon.pack;

import java.util.ArrayList;
import java.util.List;

/** Every pack that loaded, in load order, and everything that went wrong while finding them. */
public record PackSet(List<BedrockPack> packs, List<String> problems) {
    public List<BedrockPack> withRole(PackRole role) {
        return packs.stream().filter(pack -> pack.role() == role).toList();
    }

    /** Lines for the log and for the Mod Menu screen, which say the same thing. */
    public List<String> report() {
        List<String> lines = new ArrayList<>();
        if (packs.isEmpty()) {
            lines.add("No packs found in " + McAddonPaths.addons() + ".");
        }

        for (BedrockPack pack : packs) {
            PackManifest manifest = pack.manifest();
            List<String> modules = manifest.modules().stream()
                .map(module -> module.type().name().toLowerCase(java.util.Locale.ROOT))
                .toList();
            lines.add("%s %s (%s) from %s, modules %s".formatted(
                pack.role() == PackRole.RESOURCE ? "resources" : "behaviour",
                pack.displayName(), manifest.version(), pack.origin(), modules));

            for (PackManifest.ModuleDependency dependency : manifest.moduleDependencies()) {
                lines.add("    script API " + dependency.moduleName() + " " + dependency.version());
            }
        }

        for (String problem : problems) lines.add("problem: " + problem);
        return lines;
    }
}
