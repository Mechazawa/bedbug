package at.ioexception.mcaddon.client;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.network.PackList;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The add-ons the server this client is connected to has loaded, as it said on joining.
 *
 * A pack is a file the player installs, so a server cannot hand one out. Without this the models are
 * simply missing and nothing says why, so each one this client lacks is named in the log and to the
 * player, who never reads the log.
 */
public final class ServerAddons {
    private static PackList announced;

    private ServerAddons() {
    }

    public static void receive(String encoded) {
        announced = PackList.decode(encoded);
        List<PackList.Entry> missing = missing();
        if (missing.isEmpty()) return;

        for (PackList.Entry entry : missing) {
            McAddon.LOGGER.warn("The server has an add-on this client has not got: {} {}",
                entry.name(), entry.version());
            tell("This server has the add-on " + entry.name() + " " + entry.version()
                + ", which you have not got. What it adds will not appear until it is installed here as well.");
        }
        McAddon.LOGGER.warn("Entities and items from those add-ons will not appear until they are "
            + "installed here as well.");
    }

    /** Null until the server has said, and again once the client has left it. */
    public static PackList announced() {
        return announced;
    }

    /** The server's add-ons this client has not got, by uuid and version together. */
    public static List<PackList.Entry> missing() {
        return announced == null ? List.of() : announced.missingFrom(PackList.of(McAddon.packs()));
    }

    public static void clear() {
        announced = null;
    }

    private static void tell(String line) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(line));
        }
    }
}
