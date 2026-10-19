package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.registry.AddonEntities;
import com.mojang.serialization.Lifecycle;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.FlatLevelSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A dimension the test pack declares is a level a player can enter, with the band of height and the
 * empty generator the pack stated, and the world holding it opens as a stable one.
 *
 * `tp:grove` states its band as a floor and a height and names the plains, `tp:hollow` states its
 * band as a floor and a ceiling and names no biome, and `tp:chasm` names a generator nothing
 * converts. The band is read back from the client, which holds the dimension type only as the
 * registry sync delivered it.
 *
 * A world holding a registry entry from a pack with no `KnownPack` is experimental, and a client
 * then asks the player whether to open it at all, every time. The development environment answers
 * that question for the player, so it is asked here of the entries themselves: every one the test
 * pack wrote into a dynamic registry is stable, and carries the converted pack's name where it
 * carries one.
 */
public final class PackDimensionClientGameTest implements FabricClientGameTest {
    private static final ResourceKey<Level> GROVE = level("tp:grove");
    private static final ResourceKey<Level> HOLLOW = level("tp:hollow");
    private static final ResourceKey<Level> CHASM = level("tp:chasm");

    private static final BlockPos ARRIVAL = new BlockPos(0, 64, 0);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            TestServerContext server = world.getServer();

            levels(server);
            travel(context, server);
            stable(server);
        }
    }

    /** The two dimensions with an empty generator are levels with their bands, and the third is named. */
    private static void levels(TestServerContext server) {
        String grove = server.computeOnServer(minecraft -> describe(minecraft.getLevel(GROVE)));
        Assert.that(grove.equals("0 128 flat minecraft:plains"),
            "Expected tp:grove from y 0, 128 high, empty and of the plains, and found " + grove + ".");
        String hollow = server.computeOnServer(minecraft -> describe(minecraft.getLevel(HOLLOW)));
        Assert.that(hollow.equals("-64 384 flat minecraft:the_void"),
            "Expected tp:hollow from y -64 to 320, empty and of the void, and found " + hollow + ".");

        Assert.that(server.computeOnServer(minecraft -> minecraft.getLevel(CHASM) == null),
            "tp:chasm names a generator nothing converts and became a level anyway.");
        Assert.that(AddonEntities.report().stream()
            .anyMatch(line -> line.contains("dimension generator_type overworld")),
            "tp:chasm's generator is not named among what the packs asked for: " + AddonEntities.report());
    }

    /** The floor, the height, the generator and the biome at the arrival point, or what is missing. */
    private static String describe(ServerLevel level) {
        if (level == null) return "no level";

        String generator = level.getChunkSource().getGenerator() instanceof FlatLevelSource flat
            && empty(flat.settings()) ? "flat" : String.valueOf(level.getChunkSource().getGenerator());
        String biome = level.getBiome(ARRIVAL).unwrapKey().map(key -> key.identifier().toString()).orElse("none");
        return level.getMinY() + " " + level.getHeight() + " " + generator + " " + biome;
    }

    private static boolean empty(FlatLevelGeneratorSettings settings) {
        return settings.getLayers().stream().allMatch(state -> state == null || state.isAir());
    }

    /**
     * `execute in` takes the player to the grove, where the client stands in a world as tall as the
     * pack said. The player flies, because a void dimension has nothing to stand on and falling out
     * of the bottom of the world kills a creative player as well.
     */
    private static void travel(ClientGameTestContext context, TestServerContext server) {
        server.runCommand("gamemode creative @a");
        server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
        server.runCommand("execute in tp:grove run tp @a " + (ARRIVAL.getX() + 0.5) + " " + ARRIVAL.getY() + " "
            + (ARRIVAL.getZ() + 0.5));

        context.waitFor(client -> client.level != null && client.level.dimension() == GROVE);
        String band = context.computeOnClient(client -> client.level.getMinY() + " " + client.level.getHeight());
        Assert.that(band.equals("0 128"), "Expected the client's tp:grove to run from y 0 and be 128 high and"
            + " found " + band + ".");
        Assert.that(server.computeOnServer(minecraft -> player(minecraft).level().dimension() == GROVE),
            "The client is in tp:grove and the server's player is not.");
    }

    /**
     * Every entry the test pack wrote into a registry a data pack fills is stable, and one carrying
     * a pack's name carries the converted pack's.
     *
     * Fabric registers a biome its modification API changed again under a name of its own, which
     * keeps it stable and drops the pack's name, so a biome a rule added a feature or a spawn to
     * carries none. Baking a world's dimensions registers every level stem again the same way.
     */
    private static void stable(TestServerContext server) {
        Set<String> uuids = McAddon.packs().packs().stream()
            .map(pack -> pack.manifest().uuid().toString()).collect(Collectors.toSet());
        Set<ResourceKey<? extends Registry<?>>> dynamic = Stream.concat(
                //? if >=26.3 {
                /*RegistryDataLoader.WORLD_REGISTRIES.stream(), RegistryDataLoader.DIMENSION_REGISTRIES.stream())
                *///?} else {
                RegistryDataLoader.WORLDGEN_REGISTRIES.stream(), RegistryDataLoader.DIMENSION_REGISTRIES.stream())
                //?}
            .map(RegistryDataLoader.RegistryData::key).collect(Collectors.toSet());
        List<String> wrong = new ArrayList<>();
        List<String> named = new ArrayList<>();
        server.runOnServer(minecraft -> minecraft.registryAccess().registries()
            .filter(entry -> dynamic.contains(entry.key()))
            .forEach(entry -> check(entry, uuids, wrong, named)));

        Assert.that(wrong.isEmpty(), "Expected every entry the test pack wrote to be stable and named by its"
            + " converted pack where it is named at all, and found " + wrong);
        for (ResourceKey<? extends Registry<?>> registry : List.of(Registries.BIOME, Registries.DIMENSION_TYPE,
            //? if >=26.3 {
            /*Registries.PLACED_FEATURE, Registries.FEATURE)) {
            *///?} else {
            Registries.PLACED_FEATURE, Registries.CONFIGURED_FEATURE)) {
            //?}
            Assert.that(named.contains(registry.identifier().toString()),
                "No entry the test pack wrote into " + registry.identifier() + " carries its pack's name: " + named);
        }
    }

    private static <T> void check(RegistryAccess.RegistryEntry<T> entry, Set<String> uuids, List<String> wrong,
        List<String> named) {
        Registry<T> registry = entry.value();
        for (ResourceKey<T> key : registry.registryKeySet()) {
            if (!key.identifier().getNamespace().equals("tp")) continue;

            RegistrationInfo info = registry.registrationInfo(key).orElse(null);
            KnownPack known = info == null ? null : info.knownPackInfo().orElse(null);
            boolean ours = known == null || known.namespace().equals(McAddon.MOD_ID) && uuids.contains(known.id())
                && known.version().endsWith("+" + McAddon.VERSION);
            if (info == null || !ours || info.lifecycle() != Lifecycle.stable()) {
                wrong.add(entry.key().identifier() + " " + key.identifier() + " " + info);
            }
            if (known != null) named.add(entry.key().identifier().toString());
        }
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static ResourceKey<Level> level(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }
}
