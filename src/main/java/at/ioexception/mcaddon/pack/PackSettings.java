package at.ioexception.mcaddon.pack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The sliders, toggles and dropdowns the installed packs declare, at the values they ship with.
 *
 * Bedrock shows these on a screen beside a pack and lets a player move them, and the three
 * `query.*_pack_setting*` Molang queries and `World.getPackSettings` read back whatever the player
 * left them at. Java has no such screen, so nothing moves a setting off the `default` its author
 * declared and that default is the answer.
 *
 * A setting's name carries the pack's own namespace, which is what the schema requires of it and
 * what lets one index hold every pack's settings at once.
 */
public final class PackSettings {
    /** A setting no pack declares, which answers with nothing set, as Bedrock does for one. */
    private static final PackManifest.Setting NONE = new PackManifest.Setting("", "", null);

    private static Map<String, PackManifest.Setting> declared = Map.of();

    private PackSettings() {
    }

    public static void read(PackSet packs) {
        Map<String, PackManifest.Setting> found = new LinkedHashMap<>();
        packs.packs().stream()
            .flatMap(pack -> pack.manifest().settings().stream())
            .forEach(setting -> found.putIfAbsent(setting.name(), setting));
        declared = Map.copyOf(found);
    }

    public static PackManifest.Setting of(String name) {
        return declared.getOrDefault(name, NONE);
    }

    /** Every declared setting at its value, in the shape `World.getPackSettings` answers with. */
    public static Map<String, Object> values() {
        Map<String, Object> found = new LinkedHashMap<>();
        declared.forEach((name, setting) -> {
            if (setting.plain() != null) found.put(name, setting.plain());
        });
        return found;
    }
}
