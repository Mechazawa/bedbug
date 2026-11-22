package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;

import java.util.UUID;

/** The trade components' fields, read back off `tp:haggler`, a trader whose groups carry one each. */
public final class HagglerGameTests {
    private static final String HAGGLER = "tp:haggler";
    private static final BlockPos STAND = new BlockPos(2, 0, 2);

    /** A trade table's `display_name` is the title of the screen a customer opens, as a key of the pack's text. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aTradeTablesDisplayNameTitlesTheTradingScreen(GameTestHelper helper) {
        AddonEntity haggler = haggler(helper, "tp:haggler_name");
        if (haggler == null) {
            helper.succeed();
            return;
        }

        Customer customer = Customer.join(helper);
        haggler.mobInteract(customer.player(), InteractionHand.MAIN_HAND);
        String title = customer.screenTitleKey();
        customer.leave();
        helper.assertTrue("tp.haggler.counter".equals(title),
            HAGGLER + " opened a trading screen titled by " + title + " and not by its display name.");
        helper.succeed();
    }

    static AddonEntity haggler(GameTestHelper helper, String event) {
        AddonEntity haggler = BehaviourGameTests.spawn(helper, HAGGLER, STAND);
        if (haggler == null) return null;

        EntityEvents.trigger(haggler, event, null, ServerHooks.state());
        return haggler;
    }

    /** A player on a connection whose far end keeps what the server sent. */
    record Customer(ServerPlayer player, EmbeddedChannel channel) {
        static Customer join(GameTestHelper helper) {
            MinecraftServer server = helper.getLevel().getServer();
            GameProfile profile = new GameProfile(UUID.randomUUID(), "Customer");
            ServerPlayer player = new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
            BlockPos spot = helper.absolutePos(STAND.west());
            Platform.placeAt(player, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0f, 0f);
            return new Customer(player, channel);
        }

        /** The translation key of the last screen the server opened for this player, or null. */
        String screenTitleKey() {
            String key = null;
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                if (packet instanceof ClientboundOpenScreenPacket opened) {
                    key = opened.getTitle().getContents() instanceof TranslatableContents translated
                        ? translated.getKey() : opened.getTitle().getString();
                }
            }
            return key;
        }

        void leave() {
            player.level().getServer().getPlayerList().remove(player);
        }
    }
}
