package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.fog.FogDefinition;
import at.ioexception.mcaddon.registry.AddonFogs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;

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

    /**
     * The fog block the frame should be painted with, or null to keep what the game computed.
     *
     * The pushed fog wins over the camera biome's. Lava and powder snow replace the whole frame's
     * fog. Weather is mixed over what the game drew, or over the pushed air fog, by how hard it
     * rains at the camera.
     *
     * @param drawn the fog the game already computed for this frame, in blocks
     */
    public static FogDefinition.Medium paint(FogType medium, ClientLevel level, BlockPos at,
        boolean fireResistant, float rainLevel, FogDefinition.Medium drawn) {
        FogDefinition fog = active != null ? active
            : AddonFogs.forBiome(level.getBiome(at).getRegisteredName());
        float blocks = renderDistanceBlocks();

        if (medium == FogType.LAVA) return inBlocks(fog == null ? null : fog.lava(fireResistant), blocks);
        if (medium == FogType.POWDER_SNOW) return inBlocks(fog == null ? null : fog.powderSnow(), blocks);

        FogDefinition.Medium base = active == null ? drawn : active.air().inBlocks(blocks);
        FogDefinition.Medium weather = fog == null ? null : inBlocks(fog.weather(), blocks);
        if (weather == null || medium != FogType.NONE) return active == null ? null : base;

        boolean falling = level.precipitationAt(at) != Biome.Precipitation.NONE && level.canSeeSky(at);
        return falling ? base.mix(weather, rainLevel) : active == null ? null : base;
    }

    private static FogDefinition.Medium inBlocks(FogDefinition.Medium medium, float blocks) {
        return medium == null ? null : medium.inBlocks(blocks);
    }

    public static float renderDistanceBlocks() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * (float) BLOCKS_PER_CHUNK;
    }
}
