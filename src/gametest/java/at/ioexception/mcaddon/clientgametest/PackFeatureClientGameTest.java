package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.registry.AddonBlocks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The test pack's feature rules place their blocks in chunks a normal world generates fresh.
 *
 * The ore rule hangs a vein of `tp:lamp`, the pack's own block, on every overworld biome between two
 * heights. The bloom rule hangs a scatter on the forests that wraps a weighted pick of two single
 * blocks, so it reaches a block through four features. One of the two is a torchflower, which no
 * feature of the game's places anywhere, so every one found is the pack's.
 *
 * The forest is searched for from the middle of the world rather than from the player, whom Java
 * puts down anywhere within the spawn radius, so every run of one seed counts the same chunks.
 */
public final class PackFeatureClientGameTest implements FabricClientGameTest {
    /** The chunks counted, this many either side of the middle one. */
    private static final int REACH = 3;

    /** The heights the ore rule scatters between, and the room a vein of nine takes round its start. */
    private static final int ORE_LOW = -48 - 4;
    private static final int ORE_HIGH = 64 + 4;

    private static final int SEARCH_RADIUS = 4096;
    private static final int STEP = 64;
    private static final int PATIENCE = 1200;

    /** The Java biomes Bedrock tags `forest` and not `mutated`, which is what the bloom rule asks for. */
    private static final Set<String> RULED = Set.of("forest", "birch_forest", "dark_forest", "taiga", "snowy_taiga",
        "old_growth_pine_taiga", "windswept_forest");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
            .adjustSettings(ui -> {
                ui.setWorldType(ui.getNormalPresetList().get(0));
                ui.setSeed("12345");
                ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            }).create()) {
            Worlds.waitForChunks(world);
            TestServerContext server = world.getServer();
            Block lamp = AddonBlocks.named("tp:lamp");
            Assert.that(lamp != null, "The test add-on declares tp:lamp and no block of that name exists.");

            ores(context, server, lamp);
            blooms(context, server);
        }
    }

    /** Nearly every chunk round the spawn holds the pack's ore, and only between the rule's heights. */
    private static void ores(ClientGameTestContext context, TestServerContext server, Block lamp) {
        BlockPos here = server.computeOnServer(minecraft -> player(minecraft).blockPosition());
        List<BlockPos> found = found(context, server, here, state -> state.is(lamp));

        long chunks = found.stream().map(ChunkPos::containing).distinct().count();
        int around = (2 * REACH + 1) * (2 * REACH + 1);
        Assert.that(chunks * 4 >= around * 3, "Expected tp:lamp ore in nearly every one of the " + around
            + " chunks round the spawn and found it in " + chunks + ".");
        List<BlockPos> astray = found.stream().filter(at -> at.getY() < ORE_LOW || at.getY() > ORE_HIGH).toList();
        Assert.that(astray.isEmpty(), "Expected tp:lamp ore between y " + ORE_LOW + " and " + ORE_HIGH
            + " and found some at " + astray + ".");
    }

    /**
     * A forest holds the pack's torchflowers, each in a chunk whose corner is forest at the surface,
     * which is where a rule's biome filter is asked.
     */
    private static void blooms(ClientGameTestContext context, TestServerContext server) {
        BlockPos forest = server.computeOnServer(minecraft -> forestNear(minecraft.overworld()));
        Assert.that(forest != null, "No forest as wide as the chunks counted lies within " + SEARCH_RADIUS
            + " blocks of the middle of the world.");

        server.runOnServer(minecraft -> {
            ServerPlayer player = player(minecraft);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
        server.runCommand("tp @a " + forest.getX() + " " + (forest.getY() + 40) + " " + forest.getZ());

        List<BlockPos> found = found(context, server, forest, state -> state.is(Blocks.TORCHFLOWER));
        Assert.that(!found.isEmpty(), () -> "No tp:pale_bloom torchflower stands in the "
            + (2 * REACH + 1) * (2 * REACH + 1) + " chunks round the forest at " + forest.toShortString()
            + ", whose surface holds " + server.computeOnServer(minecraft -> surface(minecraft.overworld(), forest))
            + ".");
        List<BlockPos> astray = server.computeOnServer(minecraft -> found.stream()
            .filter(at -> !ruled(minecraft.overworld().getBiome(surfaceAt(minecraft.overworld(),
                at.getX() & ~15, at.getZ() & ~15)))).toList());
        Assert.that(astray.isEmpty(), "Expected the pack's torchflowers only in forest chunks and found some at "
            + astray);
    }

    /**
     * The first point, searched outwards from the middle of the world, whose ground is open forest as
     * far as the chunks counted reach.
     *
     * Biomes are three dimensional, so the forest has to be the biome at the surface rather than at
     * some height over it; a cheap look at sea level rules most points out before the surface is
     * worked out for the rest.
     */
    private static BlockPos forestNear(ServerLevel overworld) {
        for (int ring = 0; ring <= SEARCH_RADIUS; ring += STEP) {
            for (int dx = -ring; dx <= ring; dx += STEP) {
                for (int dz = -ring; dz <= ring; dz += STEP) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;

                    BlockPos centre = new BlockPos(dx, 0, dz);
                    if (forestAround(overworld, centre, false) && forestAround(overworld, centre, true)) {
                        return surfaceAt(overworld, centre.getX(), centre.getZ());
                    }
                }
            }
        }
        return null;
    }

    private static boolean forestAround(ServerLevel overworld, BlockPos centre, boolean atSurface) {
        int reach = REACH * 16;
        for (int dx = -reach; dx <= reach; dx += 16) {
            for (int dz = -reach; dz <= reach; dz += 16) {
                int x = centre.getX() + dx;
                int z = centre.getZ() + dz;
                BlockPos at = atSurface ? surfaceAt(overworld, x, z) : new BlockPos(x, overworld.getSeaLevel(), z);
                if (!open(overworld.getBiome(at))) return false;
            }
        }
        return true;
    }

    private static BlockPos surfaceAt(ServerLevel overworld, int x, int z) {
        return new BlockPos(x, overworld.getChunkSource().getGenerator().getBaseHeight(x, z,
            Heightmap.Types.WORLD_SURFACE_WG, overworld, overworld.getChunkSource().randomState()), z);
    }

    /** The biome and the block underfoot at points over the ground round a forest, counted. */
    private static String surface(ServerLevel overworld, BlockPos centre) {
        Map<String, Integer> seen = new TreeMap<>();
        for (int dx = -REACH * 16; dx <= REACH * 16; dx += 4) {
            for (int dz = -REACH * 16; dz <= REACH * 16; dz += 4) {
                BlockPos top = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, centre.offset(dx, 0, dz));
                String biome = overworld.getBiome(top).unwrapKey().map(key -> key.identifier().toString()).orElse("?");
                String ground = BuiltInRegistries.BLOCK.getKey(overworld.getBlockState(top.below()).getBlock())
                    .toString();
                seen.merge(biome + " on " + ground, 1, Integer::sum);
            }
        }
        return seen.toString();
    }

    private static boolean ruled(Holder<Biome> biome) {
        return biome.unwrapKey().filter(key -> key.identifier().getNamespace().equals("minecraft"))
            .map(key -> RULED.contains(key.identifier().getPath())).orElse(false);
    }

    /**
     * A ruled forest whose canopy leaves ground the sky reaches. A dark forest's covers nearly all of
     * it, so a scatter onto the heightmap lands on leaves there and a run counts a handful of
     * flowers, or none.
     */
    private static boolean open(Holder<Biome> biome) {
        return ruled(biome) && !biome.is(Biomes.DARK_FOREST);
    }

    /**
     * Every block the test matches in the chunks round a point, waiting until each of them has
     * generated as far as the game generates a chunk a player is near.
     */
    private static List<BlockPos> found(ClientGameTestContext context, TestServerContext server, BlockPos centre,
        Predicate<net.minecraft.world.level.block.state.BlockState> matches) {
        ChunkPos middle = ChunkPos.containing(centre);
        Worlds.waitForServer(context, server, minecraft -> generated(minecraft.overworld(), middle), PATIENCE,
            "The chunks round " + centre.toShortString() + " never finished generating.");

        return server.computeOnServer(minecraft -> {
            ServerLevel overworld = minecraft.overworld();
            List<BlockPos> matched = new ArrayList<>();
            for (int dx = -REACH; dx <= REACH; dx++) {
                for (int dz = -REACH; dz <= REACH; dz++) {
                    LevelChunk chunk = overworld.getChunkSource().getChunkNow(middle.x() + dx, middle.z() + dz);
                    BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = overworld.getMinY(); y <= overworld.getMaxY(); y++) {
                                at.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                                if (matches.test(chunk.getBlockState(at))) matched.add(at.immutable());
                            }
                        }
                    }
                }
            }
            return matched;
        });
    }

    private static boolean generated(ServerLevel overworld, ChunkPos middle) {
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                if (overworld.getChunkSource().getChunkNow(middle.x() + dx, middle.z() + dz) == null) return false;
            }
        }
        return true;
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }
}
