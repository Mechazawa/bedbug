package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.hud.HudElement;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Bedrock's paper doll: the player drawn small in the top left corner while they sneak, swim, fly,
 * glide or ride, and for {@link #LINGER} ticks after.
 *
 * Bedrock's `hud_screen.json` anchors it 15 pixels in from that corner and moves the text written
 * there {@link #PADDING} pixels down while it is shown. `/hud` and `ScreenDisplay.setHudVisibility`
 * hide it as `paperdoll`.
 */
public final class PaperDoll {
    public static final int PADDING = 50;
    private static final int LINGER = 15;
    private static final int LEFT = 10;
    private static final int TOP = 8;
    private static final int RIGHT = 35;
    private static final int BOTTOM = 46;
    private static final int SIZE = 18;

    /** Looking at a point this far right of its middle turns the doll's body 16 degrees and its head 31. */
    private static final float LOOK_RIGHT = 40;

    private static int remaining;

    private PaperDoll() {
    }

    public static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        boolean acting = player != null && (player.isCrouching() || player.isSwimming()
            || player.getAbilities().flying || player.isFallFlying() || player.isPassenger());
        remaining = acting ? LINGER : Math.max(0, remaining - 1);
    }

    public static boolean shown() {
        return remaining > 0 && !PlayerHud.hides(HudElement.PAPERDOLL);
    }

    public static void draw(Platform.Painter painter) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && shown()) painter.entity(player, LEFT, TOP, RIGHT, BOTTOM, SIZE, LOOK_RIGHT, 0);
    }
}
