package at.ioexception.mcaddon.clientgametest;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * `/wsserver` connects the player's client to a WebSocket server the test runs on 127.0.0.1, which
 * then drives the game the way Code Builder does.
 *
 * The server subscribes to three events and sends command lines in Bedrock's words. Each line has to
 * come back answered under its own request id with Bedrock's status code, change the world as the
 * player typing it would, and raise the events it causes with the bodies Bedrock sends. The
 * connection is then encrypted and has to go on answering, and `closewebsocket` has to end it.
 */
public final class WebSocketClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 100;
    private static final int SUCCESS = 0;
    private static final int FAILED_TO_PARSE_COMMAND = -2147483648;
    private static final int EXECUTION_FAIL = -2147352576;
    private static final int STONE_HELD = 5;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder()
            .adjustSettings(ui -> ui.setAllowCommands(true)).create();
            WebSocketPeer peer = new WebSocketPeer()) {
            Worlds.waitForChunks(world);
            String name = context.computeOnClient(client -> client.player.getName().getString());
            BlockPos below = world.getServer().computeOnServer(server ->
                server.getPlayerList().getPlayers().get(0).blockPosition());
            BlockPos gold = below.offset(0, 0, 3);

            context.runOnClient(client -> client.player.connection.sendCommand(
                "bedrock connect 127.0.0.1:" + peer.port()));
            waitFor(context, peer::connected, "/connect never reached the WebSocket server on port "
                + peer.port() + ".");
            Assert.that(peer.subprotocols().contains("com.microsoft.minecraft.wsencrypt"),
                () -> "The client offers Bedrock's encryption subprotocol and offered '" + peer.subprotocols() + "'.");

            peer.subscribe("PlayerMessage");
            peer.subscribe("BlockBroken");
            peer.subscribe("BlockPlaced");

            JsonObject said = answer(context, peer, peer.command("say hello websocket"), SUCCESS);
            JsonObject message = awaited(context, peer, WebSocketPeer.event("PlayerMessage"),
                "/say run through the WebSocket raises PlayerMessage, and none came.");
            JsonObject heard = message.getAsJsonObject("body");
            Assert.that(heard.get("type").getAsString().equals("say")
                && heard.get("message").getAsString().equals("hello websocket")
                && heard.get("sender").getAsString().equals(name)
                && heard.get("receiver").getAsString().isEmpty()
                && message.getAsJsonObject("header").get("messagePurpose").getAsString().equals("event"),
                () -> "PlayerMessage names a say from " + name + " to nobody, and read " + message
                    + " after the answer " + said + ".");

            answer(context, peer, peer.command("setblock " + gold.getX() + " " + gold.getY() + " " + gold.getZ()
                + " gold_block"), SUCCESS);
            Assert.that(world.getServer().computeOnServer(server ->
                server.overworld().getBlockState(gold).is(Blocks.GOLD_BLOCK)),
                "setblock run through the WebSocket places the gold block.");
            answer(context, peer, peer.command("setblock " + gold.getX() + " " + gold.getY() + " " + gold.getZ()
                + " gold_block"), EXECUTION_FAIL);
            answer(context, peer, peer.command("notacommand at all"), FAILED_TO_PARSE_COMMAND);

            world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().get(0).gameMode
                .destroyBlock(gold));
            JsonObject broken = awaited(context, peer, WebSocketPeer.event("BlockBroken"),
                "A block the player breaks raises BlockBroken, and none came.").getAsJsonObject("body");
            Assert.that(broken.getAsJsonObject("block").get("id").getAsString().equals("gold_block")
                && broken.getAsJsonObject("block").get("namespace").getAsString().equals("minecraft")
                && broken.getAsJsonObject("player").get("name").getAsString().equals(name)
                && broken.getAsJsonObject("player").get("type").getAsString().equals("minecraft:player"),
                () -> "BlockBroken names the gold block and the player who broke it, and read " + broken + ".");

            world.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, STONE_HELD));
                player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(gold.below()), Direction.UP, gold.below(), false)));
            });
            JsonObject placed = awaited(context, peer, WebSocketPeer.event("BlockPlaced"),
                "A block the player places raises BlockPlaced, and none came.").getAsJsonObject("body");
            Assert.that(placed.getAsJsonObject("block").get("id").getAsString().equals("stone")
                && placed.getAsJsonObject("tool").get("id").getAsString().equals("stone")
                && placed.getAsJsonObject("tool").get("stackSize").getAsInt() == STONE_HELD
                && placed.get("placementMethod").getAsInt() == 0,
                () -> "BlockPlaced names the stone, placed by the player from a stack of " + STONE_HELD
                    + ", and read " + placed + ".");

            String exchange = peer.encrypt();
            awaited(context, peer, WebSocketPeer.answering(exchange), "The client answers a key exchange.");
            waitFor(context, peer::encrypted, "The key exchange completes.");
            answer(context, peer, peer.command("say hello encrypted"), SUCCESS);
            awaited(context, peer, WebSocketPeer.event("PlayerMessage").and(found ->
                found.getAsJsonObject("body").get("message").getAsString().equals("hello encrypted")),
                "An event raised after the key exchange arrives encrypted and reads back.");

            peer.command("closewebsocket");
            waitFor(context, peer::closed, "closewebsocket closes the connection.");
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static JsonObject answer(ClientGameTestContext context, WebSocketPeer peer, String requestId,
        int statusCode) {
        JsonObject answer = awaited(context, peer, WebSocketPeer.answering(requestId),
            "Every command request is answered under its own id.");
        Assert.that(answer.getAsJsonObject("header").get("messagePurpose").getAsString().equals("commandResponse")
            && answer.getAsJsonObject("body").get("statusCode").getAsInt() == statusCode,
            () -> "Expected a commandResponse with status " + statusCode + " and read " + answer + ".");
        return answer;
    }

    private static JsonObject awaited(ClientGameTestContext context, WebSocketPeer peer,
        Predicate<JsonObject> wanted, String failure) {
        waitFor(context, () -> peer.find(wanted) != null, failure);
        return peer.find(wanted);
    }

    private static void waitFor(ClientGameTestContext context, BooleanSupplier holds, String failure) {
        for (int tick = 0; tick < PATIENCE; tick++) {
            if (holds.getAsBoolean()) return;
            context.waitTick();
        }
        throw new AssertionError(failure);
    }
}
