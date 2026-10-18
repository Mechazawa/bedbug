package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.pack.Archives;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackOrder;
import at.ioexception.mcaddon.pack.ReceivedArchives;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.registry.AddonRegistry;
import at.ioexception.mcaddon.resource.AddonPacks;
import at.ioexception.mcaddon.sync.AddonManifest;
import at.ioexception.mcaddon.sync.ServedArchives;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * What a server hands a client joining it.
 *
 * A world opened to LAN runs in a process that may have registered another server's add-ons
 * earlier, and Fabric's registry sync sends a guest every entry those added. So the host hands
 * such an add-on out as well, marked not active, for the guest to register and keep dormant.
 */
public final class ServedArchivesGameTests {
    /**
     * An archive registered in this process from the received cache, which the server did not
     * install, is in the manifest as not active, and the installed add-ons are in it as active.
     */
    @GameTest()
    public void aRegisteredArchiveTheServerDidNotInstallIsHandedOutDormant(GameTestHelper helper) {
        String sha256 = registerReceived(helper);
        try {
            ServedArchives.forget();
            AddonManifest manifest = ServedArchives.manifest();
            AddonManifest.Entry dormant = manifest.entries().stream()
                .filter(entry -> entry.sha256().equals(sha256)).findFirst().orElse(null);
            helper.assertTrue(dormant != null, "The registered archive is not in the manifest.");
            helper.assertFalse(dormant.active(), "The registered archive is handed out as active.");
            helper.assertTrue(ServedArchives.file(sha256) != null, "The registered archive has no file to send.");
            helper.assertTrue(ActiveAddons.installed().packs().packs().stream()
                    .filter(pack -> pack.archive() != null)
                    .allMatch(pack -> manifest.entries().stream()
                        .anyMatch(entry -> entry.sha256().equals(pack.archive()) && entry.active())),
                "An installed add-on is missing from the manifest or not active in it.");
        } finally {
            ServedArchives.forget();
        }
        helper.succeed();
    }

    /** Writes a small add-on into the received cache and registers it, the way a client visit does. */
    private static String registerReceived(GameTestHelper helper) {
        try {
            Path source = Files.createTempDirectory("mcaddon-dormant");
            Files.writeString(source.resolve("manifest.json"), """
                {"format_version": 2,
                 "header": {"name": "Dormant Probe", "uuid": "6b1f4e2a-93c7-4d58-a0e6-2f9b7c1d8e34",
                            "version": [1, 0, 0], "min_engine_version": [1, 20, 0]},
                 "modules": [{"type": "data", "uuid": "0d7a5c39-e8b2-4f61-9c04-a3e1b6f72d58",
                              "version": [1, 0, 0]}]}
                """);
            Files.createDirectories(source.resolve("items"));
            Files.writeString(source.resolve("items/token.json"), """
                {"format_version": "1.20.50",
                 "minecraft:item": {"description": {"identifier": "dormant_probe:token"},
                                    "components": {"minecraft:max_stack_size": 1}}}
                """);
            Path zipped = Files.createTempFile("mcaddon-dormant", ".mcaddon");
            Archives.zip(source, zipped);
            String sha256 = Archives.sha256(zipped);

            ReceivedArchives received = ReceivedArchives.inCache();
            Files.createDirectories(received.directory());
            Files.copy(zipped, received.archive(sha256), StandardCopyOption.REPLACE_EXISTING);
            List<String> problems = new ArrayList<>();
            List<BedrockPack> packs = received.read(sha256, "dormant-probe.mcaddon", problems);
            AddonRegistry.ensureRegistered(AddonPacks.convert(PackOrder.sort(packs, problems)));
            //? if >=26 {
            // The client's registry data step binds every item's components; this is the same call.
            net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(helper.getLevel().registryAccess())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
            //?}
            return sha256;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
