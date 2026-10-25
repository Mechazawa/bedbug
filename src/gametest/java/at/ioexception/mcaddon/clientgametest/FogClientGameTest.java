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
            media(context, world);
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
        int before = context.computeOnClient(client -> fogColour(client, standing));
        Assert.that((before & 0xFFFFFF) != GLADE, "the world's own biome already has tp:glade's fog colour.");

        // The flat world's floor is four blocks above the bottom of the world, so the fill starts at the feet.
        world.getServer().runCommand("execute as @p at @s run fillbiome ~-16 ~ ~-16 ~16 ~8 ~16 tp:glade");
        Worlds.waitForClient(context, client -> (fogColour(client, standing) & 0xFFFFFF) == GLADE, PATIENCE,
            () -> "the player stands in " + Minecraft.getInstance().level.getBiome(standing).getRegisteredName()
                + ", whose client biome names tp:fog_glade, and the fog colour there is "
                + Integer.toHexString(fogColour(Minecraft.getInstance(), standing)) + " where it was "
                + Integer.toHexString(before) + ".");
        float waterEnd = context.computeOnClient(client ->
            client.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, standing));
        Assert.near(waterEnd, GLADE_WATER_END, DISTANCE_TOLERANCE,
            "where tp:glade's water fog ends, the eighteen blocks tp:fog_glade states for water");
    }

    /**
     * `tp:fog_glade` states lava, lava resistance, powder snow and weather beside its air. Standing
     * in `tp:glade` the camera takes each in turn, and the frame's fog reads back the numbers.
     */
    private static void media(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("execute as @p at @s run fillbiome ~-16 ~-4 ~-16 ~16 ~16 ~16 tp:glade");
        float distance = context.computeOnClient(client -> PlayerFog.renderDistanceBlocks());

        world.getServer().runCommand("execute as @p at @s run setblock ~ ~1.62 ~ lava");
        waitForFog(context, 0xB32400, 0f, 4f, "the camera is in lava and the frame never took tp:fog_glade's lava fog");
        world.getServer().runCommand("effect give @a fire_resistance 100 0 true");
        waitForFog(context, 0xFFAA00, 0.1f, 12f,
            "the camera entity resists fire and the frame never took tp:fog_glade's lava resistance fog");
        world.getServer().runCommand("effect clear @a");
        world.getServer().runCommand("execute as @p at @s run setblock ~ ~1.62 ~ powder_snow");
        waitForFog(context, 0xE0F0FF, 0f, 0.5f * distance,
            "the camera is in powder snow and the frame never took tp:fog_glade's powder snow fog");
        world.getServer().runCommand("execute as @p at @s run setblock ~ ~1.62 ~ air");

        world.getServer().runCommand("weather rain 100000");
        waitForFog(context, 0x5A5A5A, 0.2f * distance, 0.6f * distance,
            "it rains on the camera and the frame never took tp:fog_glade's weather fog", PATIENCE * 4);
        world.getServer().runCommand("weather clear");
        Worlds.waitForClient(context, client -> !matches(Worlds.fog(client), 0x5A5A5A), PATIENCE * 4,
            () -> "the rain stopped and the frame kept the weather fog.");
    }

    private static void waitForFog(ClientGameTestContext context, int colour, float start, float end,
        String failure) {
        waitForFog(context, colour, start, end, failure, PATIENCE);
    }

    private static void waitForFog(ClientGameTestContext context, int colour, float start, float end,
        String failure, int patience) {
        Worlds.waitForClient(context, client -> matches(Worlds.fog(client), colour), patience,
            () -> failure + " It is " + Worlds.fog(Minecraft.getInstance()).color + ".");
        FogData fog = context.computeOnClient(Worlds::fog);
        Assert.near(fog.environmentalStart, start, DISTANCE_TOLERANCE, "where the fog starts: " + failure);
        Assert.near(fog.environmentalEnd, end, DISTANCE_TOLERANCE, "where the fog ends: " + failure);
    }

    /** The fog colour the biome states at a place, which 26.3 keeps as a vector of three channels. */
    private static int fogColour(Minecraft client, BlockPos at) {
        //? if >=26.3 {
        /*return net.minecraft.util.ARGB.colorFromVector3f(
            client.level.environmentAttributes().getValue(EnvironmentAttributes.FOG_COLOR, at));
        *///?} else {
        return client.level.environmentAttributes().getValue(EnvironmentAttributes.FOG_COLOR, at);
        //?}
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
