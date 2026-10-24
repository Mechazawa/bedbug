package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.state.WorldAddonList;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import java.nio.file.Path;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

/**
 * A world whose list turns an add-on off loses that add-on's content as it loads, as Bedrock's does.
 *
 * With the test add-on on, the world gets its lamp, an idler, dust in the player's inventory, ender
 * chest and a chest and dust lying on the ground, each beside something of the game's own. Opened
 * again with the add-on written off, the lamp is air, the idler is gone and no dust is left
 * anywhere, while the stone, the pig, the stick and both diamonds are where they were.
 */
public final class RemovedAddonsClientGameTest implements FabricClientGameTest {
    private static final String NAMESPACE = "tp";
    private static final int SETTLE_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        Served.waitForInstalled(context);
        TestWorldSave save;
        BlockPos base;
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            save = world.getWorldSave();
            Worlds.waitForChunks(world);
            TestServerContext server = world.getServer();
            base = server.computeOnServer(game -> game.getPlayerList().getPlayers().get(0).blockPosition());
            place(server, base);
            context.waitTicks(SETTLE_TICKS);
            Assert.that(server.computeOnServer(game -> count(game, base, true) == 6),
                "The test add-on's content was not all placed: "
                    + server.computeOnServer(game -> describe(game, base)));
        }

        Path root = save.getSaveDirectory();
        WorldAddonList.read(root).orElseThrow().with(uuid(), false).write(root);
        try (TestSingleplayerContext world = save.open()) {
            Worlds.waitForChunks(world);
            context.waitTicks(SETTLE_TICKS);
            TestServerContext server = world.getServer();
            Assert.that(server.computeOnServer(game -> count(game, base, true) == 0),
                "Content of an add-on the world turns off is still there: "
                    + server.computeOnServer(game -> describe(game, base)));
            Assert.that(server.computeOnServer(game -> count(game, base, false) == 6),
                "The game's own content beside a turned off add-on's was touched: "
                    + server.computeOnServer(game -> describe(game, base)));
        }
        Served.waitForInstalled(context);
    }

    private static void place(TestServerContext server, BlockPos base) {
        String lamp = at(base.offset(2, 0, 0));
        String chest = at(base.offset(2, 0, 2));
        String drops = (base.getX() - 3) + " " + base.getY() + " " + (base.getZ() + 3);
        String keep = "{PickupDelay:32767s,Age:-32768s,Item:{id:\"%s\",count:1}}";
        List.of(
            "setblock " + lamp + " tp:lamp",
            "setblock " + at(base.offset(3, 0, 0)) + " minecraft:stone",
            "setblock " + chest + " minecraft:chest",
            "item replace block " + chest + " container.0 with tp:dust 3",
            "item replace block " + chest + " container.1 with minecraft:diamond",
            "summon tp:idler " + at(base.offset(-2, 0, -2)),
            "summon minecraft:pig " + at(base.offset(-2, 0, 2)) + " {PersistenceRequired:1b,NoAI:1b}",
            "give @a tp:dust 5",
            "give @a minecraft:stick",
            "item replace entity @a enderchest.0 with tp:dust",
            "item replace entity @a enderchest.1 with minecraft:diamond",
            "summon minecraft:item " + drops + " " + String.format(keep, "tp:dust"),
            "summon minecraft:item " + drops + " " + String.format(keep, "minecraft:diamond")
        ).forEach(server::runCommand);
    }

    private static String at(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /**
     * How many of the six places hold what was put there: the test add-on's content when `addOn`,
     * else the game's own beside it.
     */
    private static long count(MinecraftServer game, BlockPos base, boolean addOn) {
        ServerLevel level = game.overworld();
        ServerPlayer player = game.getPlayerList().getPlayers().get(0);
        Identifier block = addOn ? Platform.id(NAMESPACE, "lamp") : Platform.id("minecraft", "stone");
        Identifier entity = addOn ? Platform.id(NAMESPACE, "idler") : Platform.id("minecraft", "pig");
        Item item = BuiltInRegistries.ITEM.getValue(
            addOn ? Platform.id(NAMESPACE, "dust") : Platform.id("minecraft", "diamond"));
        Item carried = addOn ? item : BuiltInRegistries.ITEM.getValue(Platform.id("minecraft", "stick"));
        List<Entity> entities = StreamSupport.stream(level.getAllEntities().spliterator(), false).toList();
        return List.of(
            BuiltInRegistries.BLOCK.getKey(level.getBlockState(base.offset(addOn ? 2 : 3, 0, 0)).getBlock())
                .equals(block),
            entities.stream().anyMatch(one -> BuiltInRegistries.ENTITY_TYPE.getKey(one.getType()).equals(entity)),
            holds(player.getInventory(), carried),
            holds(player.getEnderChestInventory(), item),
            level.getBlockEntity(base.offset(2, 0, 2)) instanceof Container chest && holds(chest, item),
            entities.stream().anyMatch(one -> one instanceof ItemEntity dropped && dropped.getItem().is(item))
        ).stream().filter(Boolean::booleanValue).count();
    }

    private static boolean holds(Container container, Item item) {
        return IntStream.range(0, container.getContainerSize()).mapToObj(container::getItem)
            .anyMatch(stack -> stack.is(item));
    }

    private static String describe(MinecraftServer game, BlockPos base) {
        return "add-on " + count(game, base, true) + " of 6, game's own " + count(game, base, false) + " of 6";
    }

    private static UUID uuid() {
        return ActiveAddons.installed().converted().stream()
            .filter(one -> one.namespace().equals(NAMESPACE))
            .map(ConvertedPack::pack)
            .map(pack -> pack.manifest().uuid())
            .findFirst()
            .orElseThrow(() -> new AssertionError("The test add-on is not installed."));
    }
}
