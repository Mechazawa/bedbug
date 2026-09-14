package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.fog.FogDefinition;
import net.minecraft.client.Minecraft;

/**
 * The fog `/fog` left on top of this client's own stack.
 *
 * The server keeps the stack and sends the numbers, so nothing here reads a pack. What the client
 * owns is the render distance, which is what a fog written as a multiple of it is resolved against.
 */
public final class PlayerFog {
    private static final int BLOCKS_PER_CHUNK = 16;

    private static FogDefinition active;

    private PlayerFog() {
    }

    public static void accept(FogDefinition fog) {
        active = fog;
    }

    public static void clear() {
        active = null;
    }

    /** Null while the player's stack is empty, which leaves the game's own fog alone. */
    public static FogDefinition active() {
        return active;
    }

    public static float renderDistanceBlocks() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * (float) BLOCKS_PER_CHUNK;
    }
}
