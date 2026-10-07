package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * A stone floor high above the world with the player standing on it, which the hands-on tests start from.
 *
 * The floor's top face is at {@link #FLOOR} plus one and the player stands on it at the middle of
 * the block above {@link #CENTRE}. It is high enough that nothing the world generated reaches it,
 * so what a test places or summons is all there is.
 */
final class Stage {
    static final int FLOOR = 119;
    static final BlockPos CENTRE = new BlockPos(0, FLOOR, 0);
    private static final int HALF_WIDTH = 8;

    private Stage() {
    }

    /** Builds the floor, empties the space above it and stands a player with nothing in hand on it. */
    static void build(ClientGameTestContext context, TestSingleplayerContext world, String gameMode) {
        TestServerContext server = world.getServer();
        server.runCommand("tp @a 0.5 " + (FLOOR + 1) + " 0.5 0 0");
        Worlds.waitForServer(context, world,
            minecraft -> player(minecraft).level().isLoaded(CENTRE.offset(HALF_WIDTH, 0, HALF_WIDTH))
                && player(minecraft).level().isLoaded(CENTRE.offset(-HALF_WIDTH, 0, -HALF_WIDTH)),
            200, "The chunks under the stage never loaded.");

        server.runCommand("fill " + -HALF_WIDTH + " " + FLOOR + " " + -HALF_WIDTH + " "
            + HALF_WIDTH + " " + (FLOOR + 6) + " " + HALF_WIDTH + " air");
        server.runCommand("fill " + -HALF_WIDTH + " " + FLOOR + " " + -HALF_WIDTH + " "
            + HALF_WIDTH + " " + FLOOR + " " + HALF_WIDTH + " stone");
        // Discarded rather than killed, because a mob that is killed stays in the way while it dies.
        server.runOnServer(minecraft -> {
            java.util.List<net.minecraft.world.entity.Entity> others = new java.util.ArrayList<>();
            player(minecraft).level().getAllEntities().forEach(others::add);
            others.stream().filter(entity -> !(entity instanceof ServerPlayer))
                .forEach(net.minecraft.world.entity.Entity::discard);
        });
        server.runCommand("time set noon");
        server.runCommand("difficulty easy");
        server.runCommand("gamemode " + gameMode + " @a");
        server.runCommand("clear @a");
        server.runCommand("tp @a 0.5 " + (FLOOR + 1) + " 0.5 0 0");
        Worlds.waitForChunks(world);
    }

    /**
     * Puts a stack in the first hotbar slot, which is the one a fresh client has selected, and waits
     * for the client to hold it, because a key pressed before then uses whatever was there before.
     */
    static void hold(ClientGameTestContext context, TestSingleplayerContext world, String item, int count) {
        world.getServer().runCommand("item replace entity @a hotbar.0 with " + item + " " + count);
        context.waitFor(client -> client.player != null
            && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem())
                .toString().equals(item)
            && client.player.getMainHandItem().getCount() == count);
    }

    static void emptyHand(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:air");
        context.waitFor(client -> client.player != null && client.player.getMainHandItem().isEmpty());
    }

    /** Turns the player's view onto a point, worked out from where the client has their eyes. */
    static void lookAt(ClientGameTestContext context, net.minecraft.world.phys.Vec3 point) {
        net.minecraft.world.phys.Vec3 eye = context.computeOnClient(client -> client.player.getEyePosition());
        net.minecraft.world.phys.Vec3 to = point.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float pitch = (float) Math.toDegrees(-Math.atan2(to.y, to.horizontalDistance()));
        context.getInput().lookAt(yaw, pitch);
        context.waitTick();
    }

    static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    static ItemStack held(MinecraftServer server) {
        return player(server).getMainHandItem().copy();
    }

    /** What the server holds there, as an id, so a failure can name it. */
    static String blockAt(MinecraftServer server, BlockPos position) {
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(player(server).level().getBlockState(position).getBlock()).toString();
    }
}
