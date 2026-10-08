package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.screen.AddonFormScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;

import java.util.stream.IntStream;

/**
 * Commands the test pack runs on a player, written in Bedrock's dialect, do what they say.
 *
 * The script hands the player one of the pack's items unless `hasitem` finds one on them already,
 * so asking twice leaves exactly one. The NPC dialogue's first button raises the player a level with
 * `xp 1L`, which Java's own `xp` does not read.
 */
public final class PackCommandClientGameTest implements FabricClientGameTest {
    private static final int WAIT_TICKS = 100;
    private static final String GIVEN = "tp:dust";

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);

            world.getServer().runCommand("execute as @a run scriptevent \"tp:stock_up\"");
            Worlds.waitForServer(context, world.getServer(), server -> carried(server) == 1, WAIT_TICKS,
                "The script's give, guarded by hasitem, never handed over " + GIVEN + ".");
            world.getServer().runCommand("execute as @a run scriptevent \"tp:stock_up\"");
            context.waitTicks(5);
            int carried = world.getServer().computeOnServer(PackCommandClientGameTest::carried);
            Assert.that(carried == 1,
                "hasitem did not see the " + GIVEN + " the player carries, which now holds " + carried + ".");

            int levels = world.getServer().computeOnServer(server -> player(server).experienceLevel);
            world.getServer().runCommand("execute as @p at @s run summon tp:ember ~2 ~ ~ {NoAI:1b}");
            context.waitTicks(5);
            world.getServer().runCommand("dialogue open @e[type=tp:ember,limit=1] @p \"tp:greeting\"");
            context.waitForScreen(AddonFormScreen.class);
            context.clickScreenButton("Feed it");
            context.waitForScreen(null);

            Worlds.waitForServer(context, world.getServer(),
                server -> player(server).experienceLevel == levels + 1, WAIT_TICKS,
                "The dialogue button's xp 1L never raised the player a level.");
        }
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static int carried(MinecraftServer server) {
        Inventory inventory = player(server).getInventory();
        return IntStream.range(0, inventory.getContainerSize())
            .mapToObj(inventory::getItem)
            .filter(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(GIVEN))
            .mapToInt(stack -> stack.getCount())
            .sum();
    }
}
