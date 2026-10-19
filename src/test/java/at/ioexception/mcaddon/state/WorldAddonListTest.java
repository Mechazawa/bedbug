package at.ioexception.mcaddon.state;

import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.convert.FunctionNames;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.resource.AddonSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAddonListTest {
    private static final UUID ONE = UUID.randomUUID();
    private static final UUID TWO = UUID.randomUUID();
    private static final UUID THREE = UUID.randomUUID();

    @TempDir
    Path world;

    @Test
    void readsBackWhatItWrote() {
        WorldAddonList list = new WorldAddonList(List.of(
            new WorldAddonList.Entry(ONE, "One", true), new WorldAddonList.Entry(TWO, "Two", false)));
        list.write(world);
        assertEquals(Optional.of(list), WorldAddonList.read(world));
    }

    @Test
    void aWorldWithNoFileHasNoList() {
        assertTrue(WorldAddonList.read(world).isEmpty());
    }

    @Test
    void anInstalledAddonTheListDoesNotNameIsOff() {
        AddonSet resolved = new WorldAddonList(List.of(new WorldAddonList.Entry(ONE, "One", true)))
            .resolve(installed(ONE, TWO));
        assertEquals(List.of(ONE), uuids(resolved));
    }

    @Test
    void anAddonNoLongerInstalledStaysListed() {
        new WorldAddonList(List.of(new WorldAddonList.Entry(THREE, "Gone", true))).write(world);
        WorldAddonList read = WorldAddonList.read(world).orElseThrow();
        assertEquals(List.of(THREE), read.addons().stream().map(WorldAddonList.Entry::uuid).toList());
        assertTrue(uuids(read.resolve(installed(ONE))).isEmpty());
    }

    @Test
    void withChangesOneEntryAndAppendsAnUnknownOne() {
        WorldAddonList list = WorldAddonList.allOn(installed(ONE, TWO)).with(TWO, false).with(THREE, true);
        assertEquals(List.of(true, false, true), list.addons().stream().map(WorldAddonList.Entry::on).toList());
        assertEquals(ONE, list.addons().get(0).uuid());
    }

    @Test
    void resolveKeepsOrderAndFunctions() {
        AddonSet installed = installed(ONE, TWO, THREE);
        AddonSet resolved = new WorldAddonList(List.of(new WorldAddonList.Entry(THREE, "", true),
            new WorldAddonList.Entry(ONE, "", true), new WorldAddonList.Entry(TWO, "", false)))
            .resolve(installed);
        assertEquals(List.of(ONE, THREE), uuids(resolved));
        assertSame(installed.functions(), resolved.functions());
        assertEquals(2, resolved.packs().packs().size());
        assertFalse(resolved.packs().packs().stream().anyMatch(pack -> pack.manifest().uuid().equals(TWO)));
    }

    private static List<UUID> uuids(AddonSet set) {
        return set.converted().stream().map(one -> one.pack().manifest().uuid()).toList();
    }

    private static AddonSet installed(UUID... uuids) {
        List<ConvertedPack> converted = Arrays.stream(uuids).map(WorldAddonListTest::converted).toList();
        List<BedrockPack> packs = converted.stream().map(ConvertedPack::pack).toList();
        return new AddonSet(new PackSet(packs, List.of()), converted, FunctionNames.NONE);
    }

    private static ConvertedPack converted(UUID uuid) {
        PackManifest manifest = new PackManifest(2, uuid, null, "pack-" + uuid, "", null, List.of(), List.of(),
            List.of(), Set.of(), List.of(), List.of(), null, List.of());
        BedrockPack pack = new BedrockPack(Path.of("/none/" + uuid), manifest, uuid.toString());
        return new ConvertedPack(pack, List.of(), Path.of("/none/out/" + uuid), "ns", Map.of(), Map.of(),
            Map.of(), List.of(), Map.of());
    }
}
