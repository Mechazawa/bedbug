package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.hud.HudElement;

import java.util.Set;

/**
 * The pieces of this client's heads-up display the server has hidden, which `HudMixin` leaves
 * undrawn. Everything else is drawn as the player's own settings say.
 */
public final class PlayerHud {
    private static Set<HudElement> hidden = Set.of();

    private PlayerHud() {
    }

    public static void accept(int bits) {
        hidden = HudElement.fromBits(bits);
    }

    public static void clear() {
        hidden = Set.of();
    }

    public static boolean hides(HudElement element) {
        return hidden.contains(element);
    }
}
