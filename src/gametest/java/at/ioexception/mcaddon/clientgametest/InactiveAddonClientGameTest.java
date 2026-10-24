package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.resource.AddonSet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A world opened while the test pack is outside the active set holds nothing of it, and the same
 * world settings with the pack active hold all of it.
 *
 * Fabric keeps the pack's nether biomes and its end biome for the life of the process and resolves
 * them as a world builds its nether and end, so without the pack's data pack such a world would not
 * load. What is read back is the nether's and the end's possible biomes, the tag the pack's script
 * puts on a villager it sees spawn, and the `tp:lamp` ore its feature rule places round the spawn.
 */
public final class InactiveAddonClientGameTest implements FabricClientGameTest {
    private static final String NAMESPACE = "tp";
    private static final Set<String> BIOMES = Set.of("tp:cinder_wastes", "tp:soot_fields", "tp:hush");
    private static final String SEEN = "tp:spawn_seen";
    private static final int SCRIPT_TICKS = 60;
    private static final int REACH = 2;
    private static final int PATIENCE = 1200;

    @Override
    public void runTest(ClientGameTestContext context) {
        AddonSet installed = context.computeOnClient(client -> ActiveAddons.installed());
        Assert.that(installed.converted().stream().anyMatch(one -> one.namespace().equals(NAMESPACE)),
            "The test add-on is not installed.");
        Block lamp = AddonBlocks.named("tp:lamp");
        Assert.that(lamp != null, "The test add-on declares tp:lamp and no block of that name exists.");

        context.runOnClient(client -> ActiveAddons.activate(installed.only(one -> !one.namespace().equals(NAMESPACE))));
        try (TestSingleplayerContext world = create(context)) {
            TestServerContext server = world.getServer();
            Set<String> biomes = server.computeOnServer(InactiveAddonClientGameTest::packBiomes);
            Assert.that(biomes.isEmpty(), "Expected no biome of the inactive test add-on in the nether or the end"
                + " and found " + biomes + ".");

            summonVillager(server);
            context.waitTicks(SCRIPT_TICKS);
            Assert.that(server.computeOnServer(minecraft -> !seen(minecraft)),
                "The inactive test add-on's script tagged a villager it saw spawn.");

            int lamps = lamps(context, server, lamp);
            Assert.that(lamps == 0,
                "The inactive test add-on's ore rule placed " + lamps + " tp:lamp round the spawn.");
        } finally {
            context.runOnClient(client -> ActiveAddons.activate(installed));
        }

        try (TestSingleplayerContext world = create(context)) {
            TestServerContext server = world.getServer();
            Set<String> biomes = server.computeOnServer(InactiveAddonClientGameTest::packBiomes);
            Assert.that(biomes.equals(BIOMES), "Expected " + BIOMES + " in the nether and the end with the test"
                + " add-on active and found " + biomes + ".");

            summonVillager(server);
            Worlds.waitForServer(context, server, InactiveAddonClientGameTest::seen, SCRIPT_TICKS,
                "The active test add-on's script never tagged a villager it saw spawn.");

            Assert.that(lamps(context, server, lamp) > 0, "The active test add-on's ore rule placed no tp:lamp"
                + " round the spawn.");
        }
    }

    private static TestSingleplayerContext create(ClientGameTestContext context) {
        TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
            .adjustSettings(ui -> {
                ui.setWorldType(ui.getNormalPresetList().get(0));
                ui.setSeed("12345");
                ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            }).create();
        Worlds.waitForChunks(world);
        return world;
    }

    /** The pack's biomes the nether's and the end's biome sources can place. */
    private static Set<String> packBiomes(MinecraftServer server) {
        Set<String> found = new TreeSet<>();
        for (ResourceKey<Level> dimension : Set.of(Level.NETHER, Level.END)) {
            server.getLevel(dimension).getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                .flatMap(holder -> holder.unwrapKey().stream())
                .map(key -> key.identifier().toString())
                .filter(id -> id.startsWith(NAMESPACE + ":"))
                .forEach(found::add);
        }
        return found;
    }

    private static void summonVillager(TestServerContext server) {
        server.runCommand("execute as @p at @s run summon minecraft:villager ~4 ~ ~ {NoAI:1b}");
    }

    private static boolean seen(MinecraftServer server) {
        ServerPlayer player = player(server);
        return !player.level().getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(64),
            entity -> entity.entityTags().contains(SEEN)).isEmpty();
    }

    /** The pack's ore in the chunks round the player, once they have generated. */
    private static int lamps(ClientGameTestContext context, TestServerContext server, Block lamp) {
        ChunkPos middle = server.computeOnServer(minecraft -> ChunkPos.containing(player(minecraft).blockPosition()));
        Worlds.waitForServer(context, server, minecraft -> chunks(minecraft.overworld(), middle) != null, PATIENCE,
            "The chunks round the spawn never finished generating.");

        return server.computeOnServer(minecraft -> {
            ServerLevel overworld = minecraft.overworld();
            int count = 0;
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (LevelChunk chunk : chunks(overworld, middle)) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = overworld.getMinY(); y <= overworld.getMaxY(); y++) {
                            at.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                            if (chunk.getBlockState(at).is(lamp)) count++;
                        }
                    }
                }
            }
            return count;
        });
    }

    /** Every chunk within reach of `middle`, or null while one of them has not generated. */
    private static List<LevelChunk> chunks(ServerLevel overworld, ChunkPos middle) {
        List<LevelChunk> found = new ArrayList<>();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                LevelChunk chunk = overworld.getChunkSource().getChunkNow(middle.x() + dx, middle.z() + dz);
                if (chunk == null) return null;
                found.add(chunk);
            }
        }
        return found;
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }
}
