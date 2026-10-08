package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.PlayerFog;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * The fog a pack describes is the fog a frame is drawn with.
 *
 * `/fog` keeps a stack per player. `tp:dusk` paints the air `#3A2E5C` from a fifth of the render
 * distance to nine tenths of it, and `tp:fog_glade` pushed over it paints `#d2661e`; popping the top
 * gives the one under it back and popping the last gives the game's own fog back.
 *
 * `tp:glade`'s client biome names `tp:fog_glade` as well, so standing in that biome puts its air
 * colour and its water fog's eighteen blocks on the environment the game draws fog from.
 */
public final class FogClientGameTest implements FabricClientGameTest {
    private static final float COLOUR_TOLERANCE = 0.002f;
    private static final float DISTANCE_TOLERANCE = 0.01f;
    private static final int PATIENCE = 100;

    private static final int DUSK = 0x3A2E5C;
    private static final int GLADE = 0xD2661E;
    private static final float GLADE_WATER_END = 18f;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            stack(context, world);
            biome(context, world);
        }
    }

    private static void stack(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("bedrock fog @a push tp:dusk dusk");
        waitForColour(context, DUSK, "tp:dusk was pushed and the frame's fog never took its colour.");
        float distance = context.computeOnClient(client -> PlayerFog.renderDistanceBlocks());
        FogData dusk = context.computeOnClient(Worlds::fog);
        Assert.near(dusk.environmentalStart, 0.2f * distance, DISTANCE_TOLERANCE,
            "where tp:dusk's fog starts, a fifth of the render distance");
        Assert.near(dusk.environmentalEnd, 0.9f * distance, DISTANCE_TOLERANCE,
            "where tp:dusk's fog ends, nine tenths of the render distance");

        world.getServer().runCommand("bedrock fog @a push tp:fog_glade glade");
        waitForColour(context, GLADE, "tp:fog_glade was pushed over tp:dusk and the frame's fog never took it.");
        world.getServer().runCommand("bedrock fog @a pop glade");
        waitForColour(context, DUSK, "tp:fog_glade was popped and the frame's fog never went back to tp:dusk.");

        world.getServer().runCommand("bedrock fog @a pop dusk");
        Worlds.waitForClient(context, client -> PlayerFog.active() == null && !matches(Worlds.fog(client), DUSK),
            PATIENCE, () -> "the last fog on the stack was popped and the frame kept tp:dusk's colour.");
    }

    private static void biome(ClientGameTestContext context, TestSingleplayerContext world) {
        // The overworld's day darkens the fog colour every biome states, and at noon it leaves it alone.
        world.getServer().runCommand("time set noon");
        context.waitTicks(2);
        BlockPos standing = context.computeOnClient(client -> client.player.blockPosition());
        int before = context.computeOnClient(client ->
            client.level.environmentAttributes().getValue(EnvironmentAttributes.FOG_COLOR, standing));
        Assert.that((before & 0xFFFFFF) != GLADE, "the world's own biome already has tp:glade's fog colour.");

        // The flat world's floor is four blocks above the bottom of the world, so the fill starts at the feet.
        world.getServer().runCommand("execute as @p at @s run fillbiome ~-16 ~ ~-16 ~16 ~8 ~16 tp:glade");
        Worlds.waitForClient(context, client -> (client.level.environmentAttributes()
                .getValue(EnvironmentAttributes.FOG_COLOR, standing) & 0xFFFFFF) == GLADE, PATIENCE,
            () -> "the player stands in " + Minecraft.getInstance().level.getBiome(standing).getRegisteredName()
                + ", whose client biome names tp:fog_glade, and the fog colour there is "
                + Integer.toHexString(Minecraft.getInstance().level.environmentAttributes()
                .getValue(EnvironmentAttributes.FOG_COLOR, standing)) + " where it was "
                + Integer.toHexString(before) + ".");
        float waterEnd = context.computeOnClient(client ->
            client.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, standing));
        Assert.near(waterEnd, GLADE_WATER_END, DISTANCE_TOLERANCE,
            "where tp:glade's water fog ends, the eighteen blocks tp:fog_glade states for water");
    }

    private static void waitForColour(ClientGameTestContext context, int colour, String failure) {
        Worlds.waitForClient(context, client -> matches(Worlds.fog(client), colour), PATIENCE,
            () -> failure + " It is " + Worlds.fog(Minecraft.getInstance()).color + ".");
    }

    private static boolean matches(FogData fog, int colour) {
        return fog != null
            && Math.abs(fog.color.x() - (colour >> 16 & 0xFF) / 255f) < COLOUR_TOLERANCE
            && Math.abs(fog.color.y() - (colour >> 8 & 0xFF) / 255f) < COLOUR_TOLERANCE
            && Math.abs(fog.color.z() - (colour & 0xFF) / 255f) < COLOUR_TOLERANCE;
    }
}
