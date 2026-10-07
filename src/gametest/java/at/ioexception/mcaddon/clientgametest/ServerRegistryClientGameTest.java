package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.client.ServerAddons;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.network.PackList;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * A client joining a dedicated server agrees with it about everything the add-ons registered.
 *
 * The server and the client run in one process here, so the built-in registries are one object and
 * agreeing on an id is not in question. What is in question is what crosses the connection: the
 * registry sync the server opens with, which refuses a client lacking an entry, and every item stack,
 * block state and entity type that goes over afterwards by its number. Each is sent from the server
 * and read back from the client's own copy of the world. The pack's biomes are a dynamic registry,
 * which the client holds only as the server sent it.
 *
 * The server also says which add-ons it has as the client joins. Both sides have the same ones, so
 * the client is then told of a version of the test pack it has not got, which is the case a real
 * client can meet without the registry sync turning it away first.
 */
public final class ServerRegistryClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 200;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = context.worldBuilder().createServer();
            var connection = server.connect()) {
            Worlds.waitForChunks(connection);
            String player = server.computeOnServer(minecraft -> client(minecraft).getName().getString());

            registries(context, server);
            items(context, server, player);
            blocks(context, server);
            packs(context, server);
        }
    }

    /** Every add-on entity type, item, block and sound event, and every add-on biome, on both sides. */
    private static void registries(ClientGameTestContext context, TestServerContext server) {
        List<String> missing = new ArrayList<>();
        AddonEntities.all().keySet().forEach(type -> check(context, server, Registries.ENTITY_TYPE,
            BuiltInRegistries.ENTITY_TYPE.getKey(type), missing));
        AddonItems.all().values().forEach(item -> check(context, server, Registries.ITEM,
            BuiltInRegistries.ITEM.getKey(item), missing));
        AddonBlocks.all().values().forEach(block -> check(context, server, Registries.BLOCK,
            BuiltInRegistries.BLOCK.getKey(block), missing));
        BuiltInRegistries.SOUND_EVENT.keySet().stream()
            .filter(id -> id.getNamespace().equals("tp"))
            .forEach(id -> check(context, server, Registries.SOUND_EVENT, id, missing));
        for (String biome : List.of("tp:glade", "tp:cinder_wastes")) {
            check(context, server, Registries.BIOME, Identifier.parse(biome), missing);
        }
        Assert.that(missing.isEmpty(), "Expected every add-on registry entry on both sides and found " + missing);
        Assert.that(!AddonEntities.all().isEmpty() && !AddonItems.all().isEmpty() && !AddonBlocks.all().isEmpty(),
            "The test add-on registered no entity type, item or block, so nothing above was compared.");
    }

    private static <T> void check(ClientGameTestContext context, TestServerContext server,
        ResourceKey<? extends Registry<T>> registry, Identifier id, List<String> missing) {
        int onServer = server.computeOnServer(minecraft -> idIn(minecraft.registryAccess(), registry, id));
        int onClient = context.computeOnClient(client -> idIn(clientRegistries(client), registry, id));
        if (onServer < 0 || onServer != onClient) {
            missing.add(registry.identifier() + " " + id + ": " + onServer + " on the server, " + onClient
                + " on the client");
        }
    }

    /** The entry's number in the registry, or -1 when it has none, which is what goes over the wire. */
    private static <T> int idIn(RegistryAccess access, ResourceKey<? extends Registry<T>> registry, Identifier id) {
        Registry<T> entries = access.lookupOrThrow(registry);
        return entries.getOptional(id).map(entries::getId).orElse(-1);
    }

    private static RegistryAccess clientRegistries(Minecraft client) {
        return client.getConnection().registryAccess();
    }

    /** An add-on item given on the server arrives in the client's inventory as that item. */
    private static void items(ClientGameTestContext context, TestServerContext server, String player) {
        Item shard = AddonItems.named("tp:shard");
        Assert.that(shard != null, "The test add-on declares tp:shard and no item of that name was registered.");
        server.runCommand("give " + player + " tp:shard 3");
        Worlds.waitForClient(context, client -> client.player.getInventory().countItem(shard) == 3, PATIENCE,
            () -> "Expected three tp:shard in the client's inventory after the server gave them and found "
                + Minecraft.getInstance().player.getInventory().countItem(shard) + ".");
    }

    /** An add-on block placed on the server reaches the client as the same state, properties and all. */
    private static void blocks(ClientGameTestContext context, TestServerContext server) {
        BlockPos where = server.computeOnServer(minecraft -> client(minecraft).blockPosition().offset(2, 0, 0));
        server.runCommand("setblock " + where.getX() + " " + where.getY() + " " + where.getZ() + " tp:lamp");
        Worlds.waitForServer(context, server, minecraft -> placed(minecraft, where).getBlock() != null
                && BuiltInRegistries.BLOCK.getKey(placed(minecraft, where).getBlock()).toString().equals("tp:lamp"),
            PATIENCE, minecraft -> "Expected setblock to place tp:lamp at " + where.toShortString()
                + " on the server and found " + placed(minecraft, where) + ".");

        BlockState onServer = server.computeOnServer(minecraft -> placed(minecraft, where));
        Worlds.waitForClient(context, client -> client.level.getBlockState(where) == onServer, PATIENCE,
            () -> "Expected the client to hold " + onServer + " at " + where.toShortString() + " and found "
                + Minecraft.getInstance().level.getBlockState(where) + ".");
    }

    /**
     * The client heard on joining which add-ons the server has and found none missing, then hears of
     * a release of the test pack it has not got and names it.
     */
    private static void packs(ClientGameTestContext context, TestServerContext server) {
        PackList own = PackList.of(McAddon.packs());
        Worlds.waitForClient(context, client -> ServerAddons.announced() != null, PATIENCE,
            () -> "The server never told the client which add-ons it has.");
        PackList announced = context.computeOnClient(client -> ServerAddons.announced());
        Assert.that(announced.packs().equals(own.packs()), "Expected the server to announce " + own.packs()
            + " and found " + announced.packs() + ".");
        List<PackList.Entry> missing = context.computeOnClient(client -> ServerAddons.missing());
        Assert.that(missing.isEmpty(), "Expected the client to lack none of the server's add-ons and found "
            + missing + ".");

        PackList.Entry first = own.packs().get(0);
        PackList.Entry later = new PackList.Entry(first.uuid(), "9.9.9", first.name());
        List<PackList.Entry> released = new ArrayList<>(own.packs());
        released.set(0, later);
        server.runOnServer(minecraft -> Channels.sendPacks(client(minecraft), new PackList(released).encode()));
        Worlds.waitForClient(context, client -> ServerAddons.missing().equals(List.of(later)), PATIENCE,
            () -> "Expected the client to say it lacks " + later + " and found " + ServerAddons.missing() + ".");
    }

    private static BlockState placed(MinecraftServer server, BlockPos where) {
        return server.overworld().getBlockState(where);
    }

    private static ServerPlayer client(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }
}
