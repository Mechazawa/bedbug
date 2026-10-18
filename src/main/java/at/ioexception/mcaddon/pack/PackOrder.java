package at.ioexception.mcaddon.pack;

import at.ioexception.mcaddon.script.ScriptHost;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puts discovered packs in load order.
 *
 * Resource packs come before behaviour packs, and each role is sorted so a pack follows the packs it
 * depends on. The roles are separated first because an add-on's two halves normally require each
 * other: in the reference add-on the behaviour pack names the resource pack's uuid and the resource
 * pack names the behaviour pack's, which no single topological sort can satisfy.
 */
public final class PackOrder {
    private PackOrder() {
    }

    public static PackSet sort(List<BedrockPack> packs, List<String> problems) {
        Set<UUID> known = new HashSet<>();
        for (BedrockPack pack : packs) known.add(pack.manifest().uuid());

        for (BedrockPack pack : packs) {
            pack.manifest().moduleDependencies().stream()
                .filter(dependency -> !dependency.optional() && !ScriptHost.provides(dependency.moduleName()))
                .forEach(dependency -> problems.add(pack.displayName()
                    + " requires a script API this mod does not provide: " + dependency.moduleName()));
        }

        List<BedrockPack> ordered = new ArrayList<>(packs.size());
        for (PackRole role : List.of(PackRole.RESOURCE, PackRole.BEHAVIOUR)) {
            ordered.addAll(withinRole(packs.stream().filter(pack -> pack.role() == role).toList(), known, problems));
        }
        return new PackSet(List.copyOf(ordered), List.copyOf(problems));
    }

    private static List<BedrockPack> withinRole(List<BedrockPack> packs, Set<UUID> known, List<String> problems) {
        Map<UUID, BedrockPack> byUuid = new HashMap<>();
        for (BedrockPack pack : packs) byUuid.put(pack.manifest().uuid(), pack);

        Map<UUID, Set<UUID>> waitingOn = new HashMap<>();
        for (BedrockPack pack : packs) {
            Set<UUID> required = new HashSet<>();
            for (PackManifest.PackDependency dependency : pack.manifest().packDependencies()) {
                if (byUuid.containsKey(dependency.uuid())) {
                    required.add(dependency.uuid());
                } else if (!known.contains(dependency.uuid())) {
                    problems.add(pack.displayName() + " requires a pack that is not installed: "
                        + dependency.uuid() + " " + dependency.version());
                }
            }
            waitingOn.put(pack.manifest().uuid(), required);
        }

        List<BedrockPack> sorted = new ArrayList<>(packs.size());
        Deque<BedrockPack> ready = new ArrayDeque<>(packs.stream()
            .filter(pack -> waitingOn.get(pack.manifest().uuid()).isEmpty())
            .sorted(Comparator.comparing(BedrockPack::displayName))
            .toList());

        while (!ready.isEmpty()) {
            BedrockPack next = ready.poll();
            sorted.add(next);
            for (BedrockPack pack : packs) {
                Set<UUID> required = waitingOn.get(pack.manifest().uuid());
                if (required.remove(next.manifest().uuid()) && required.isEmpty()) ready.add(pack);
            }
        }

        if (sorted.size() < packs.size()) {
            List<BedrockPack> remaining = packs.stream()
                .filter(pack -> !sorted.contains(pack))
                .sorted(Comparator.comparing(BedrockPack::displayName))
                .toList();
            problems.add("Dependency cycle between " + remaining.stream().map(BedrockPack::displayName).toList()
                + "; loading them in name order");
            sorted.addAll(remaining);
        }
        return sorted;
    }
}
