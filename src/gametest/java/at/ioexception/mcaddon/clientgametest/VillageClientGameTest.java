package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

/**
 * A village generated in a normal world has its villagers standing in it once a player arrives.
 *
 * Villagers are placed while the village's chunks generate and are loaded again from the entity
 * storage, so a gap in either half leaves the houses and the bell with nobody there. The player is
 * in creative because the teleport puts them in the air above the village, and a survival player
 * dies of the fall and holds no chunk at all once dead.
 */
public final class VillageClientGameTest implements FabricClientGameTest {
    private static final int SEARCH_CHUNKS = 100;
    private static final int VILLAGE_REACH = 96;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
            .adjustSettings(ui -> {
                ui.setWorldType(ui.getNormalPresetList().get(0));
                ui.setGenerateStructures(true);
                ui.setSeed("12345");
                ui.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            }).create()) {
            Worlds.waitForChunks(world);
            BlockPos village = world.getServer().computeOnServer(server -> server.overworld()
                .findNearestMapStructure(StructureTags.VILLAGE, BlockPos.ZERO, SEARCH_CHUNKS, false));
            Assert.that(village != null, "No village generated within " + SEARCH_CHUNKS + " chunks of the origin.");

            world.getServer().runCommand("tp @a " + village.getX() + " 200 " + village.getZ());
            Worlds.waitForServer(context, world.getServer(), server -> villagersAround(server, village) > 0, 1200,
                "The village at " + village.toShortString() + " generated with no villager loaded around it.");
        }
    }

    private static int villagersAround(MinecraftServer server, BlockPos village) {
        AABB reach = new AABB(village).inflate(VILLAGE_REACH, 256, VILLAGE_REACH);
        return server.overworld().getEntitiesOfClass(Villager.class, reach).size();
    }
}
