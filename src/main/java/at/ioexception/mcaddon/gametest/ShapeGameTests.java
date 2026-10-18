package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.registry.AddonRegistry;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Whether another copy of an installed add-on can take over the objects registered for it.
 *
 * Each copy here is the test pack's behaviour pack with one file edited, read as a pack of its own
 * the way an archive a server sends is read, and nothing of it is registered.
 */
public final class ShapeGameTests {
    /**
     * A copy changing what the registered objects were built from is named; one changing only what
     * is bound to them afterwards is not.
     */
    @GameTest()
    public void aCopyBuiltDifferentlyNamesItsAddOn(GameTestHelper helper) {
        BedrockPack installed = ActiveAddons.installed().packs().withRole(PackRole.BEHAVIOUR).stream()
            .filter(pack -> "tp".equals(ActiveAddons.installed().namespaceOf(pack)))
            .findFirst().orElse(null);
        if (installed == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(AddonRegistry.conflict(ActiveAddons.installed().packs()).isEmpty(),
            "The installed add-ons conflict with what they registered themselves.");
        helper.assertTrue(conflict(installed, "entities/sprite.json", "\"value\": 10", "\"value\": 12").isEmpty(),
            "A copy that only heals the sprite differently was refused.");

        String name = installed.displayName();
        helper.assertTrue(conflict(installed, "blocks/lamp.json", "[0, 1, 2]", "[0, 1, 2, 3]")
            .equals(Optional.of(name)), "A copy giving the lamp a fourth level was not refused by name.");
        helper.assertTrue(conflict(installed, "items/dust.json", "\"minecraft:max_stack_size\": 64",
            "\"minecraft:max_stack_size\": 16").equals(Optional.of(name)),
            "A copy stacking the dust to sixteen was not refused by name.");
        helper.assertTrue(conflict(installed, "entities/sprite.json", "\"width\": 0.6", "\"width\": 0.9")
            .equals(Optional.of(name)), "A copy widening the sprite was not refused by name.");
        helper.succeed();
    }

    /** What `AddonRegistry.conflict` answers for a copy of `pack` with one text replaced in one file. */
    private static Optional<String> conflict(BedrockPack pack, String file, String from, String to) {
        try {
            Path original = pack.file("manifest.json").getParent();
            Path copy = Files.createTempDirectory("mcaddon-shape");
            try (Stream<Path> walk = Files.walk(original)) {
                for (Path source : walk.toList()) {
                    Path target = copy.resolve(original.relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target);
                    }
                }
            }
            Path edited = copy.resolve(file);
            String text = Files.readString(edited);
            if (!text.contains(from)) throw new IllegalStateException(file + " no longer holds " + from);
            Files.writeString(edited, text.replace(from, to));

            Optional<String> answer = AddonRegistry.conflict(new PackSet(
                List.of(new BedrockPack(copy, pack.manifest(), pack.origin())), List.of()));
            try (Stream<Path> walk = Files.walk(copy)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
            return answer;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
