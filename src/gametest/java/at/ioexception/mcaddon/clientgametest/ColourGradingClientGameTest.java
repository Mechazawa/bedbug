package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.bedrock.grading.GradeUniforms;
import at.ioexception.mcaddon.client.render.ColourGrading;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The grade a pack gives a biome is the grade the frame is drawn with.
 *
 * `tp:glade`'s client biome names `tp:glade_grade`, which tints the frame toward a 4000 K light,
 * desaturates its midtones and lifts its contrast. The player looks at a wall of orange concrete,
 * first in a plains biome, which no pack grades, and again after the ground around them became
 * `tp:glade`. The second frame's pixel has to be the first frame's pixel run through the glade's
 * grade, which is what reading the grade back and drawing it both have to get right.
 *
 * `tp:soot_fields` names no grade, so it takes the test pack's `color_grading/color_grading.json`.
 */
public final class ColourGradingClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 100;
    /** One step of an eight bit channel either way, for the rounding on each side of the pass. */
    private static final int CHANNEL_TOLERANCE = 2;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "creative");
            world.getServer().runCommand("fill -3 120 3 3 124 3 minecraft:orange_concrete");
            // A biome another mod adds names no grade either, so it takes the pack's whole-world one.
            // The box covers every quarter chunk the camera's blend weighs and stays under fillbiome's limit.
            world.getServer().runCommand("fillbiome -12 108 -12 12 136 12 minecraft:plains");
            context.runOnClient(client -> Platform.hideHud(client, true));
            context.waitTicks(5);

            GradeUniforms soot = context.computeOnClient(client -> ColourGrading.of(client.level.registryAccess()
                .lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME,
                    Platform.id("tp", "soot_fields")))));
            Assert.near(soot.value(GradeUniforms.CONTRAST + 1, 0), 1.2, 1e-4,
                "the midtone contrast of tp:soot_fields, which takes the pack's color_grading.json");

            Worlds.waitForClient(context, client -> ColourGrading.current().leavesFrameAlone(), PATIENCE,
                () -> "the player stands in plains, which no pack grades, and the frame is graded.");
            int plain = centre(context, world);

            world.getServer().runCommand("fillbiome -12 108 -12 12 136 12 tp:glade");
            Worlds.waitForClient(context, client -> Math.abs(midtoneContrast(ColourGrading.current()) - 1.3f) < 1e-4,
                PATIENCE, () -> {
                    net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                    var biome = client.level.getBiome(client.player.blockPosition());
                    return "the player stands in tp:glade and the frame's midtone contrast is "
                        + midtoneContrast(ColourGrading.current()) + " where tp:glade_grade states 1.3. The client has "
                        + biome.getRegisteredName() + " there, which takes "
                        + midtoneContrast(ColourGrading.of(biome)) + ".";
                });
            int graded = centre(context, world);

            float[] wanted = ColourGrading.current().grade((plain >> 16 & 0xFF) / 255f,
                (plain >> 8 & 0xFF) / 255f, (plain & 0xFF) / 255f);
            int[] found = {graded >> 16 & 0xFF, graded >> 8 & 0xFF, graded & 0xFF};
            for (int channel = 0; channel < 3; channel++) {
                int expected = Math.round(wanted[channel] * 255);
                int channelIndex = channel;
                Assert.that(Math.abs(found[channel] - expected) <= CHANNEL_TOLERANCE, () -> "channel "
                    + channelIndex + " of the wall in tp:glade is " + found[channelIndex] + " where the grade makes "
                    + expected + " of the ungraded " + Integer.toHexString(plain & 0xFFFFFF) + ", and the frame is "
                    + Integer.toHexString(graded & 0xFFFFFF) + ".");
            }
            context.runOnClient(client -> Platform.hideHud(client, false));
        }
    }

    private static float midtoneContrast(GradeUniforms grade) {
        return grade.value(GradeUniforms.CONTRAST + 1, 0);
    }

    /**
     * The pixel in the middle of the last frame drawn at noon, read out of the main target the way
     * the game's own screenshot key reads it, so no frame is drawn outside the game's own loop.
     */
    private static int centre(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("time set noon");
        context.waitTicks(2);
        AtomicInteger pixel = new AtomicInteger();
        AtomicBoolean read = new AtomicBoolean();
        context.runOnClient(client -> Screenshot.takeScreenshot(Platform.mainRenderTarget(client), image -> {
            try (image) {
                pixel.set(image.getPixel(image.getWidth() / 2, image.getHeight() / 2));
            }
            read.set(true);
        }));
        context.waitFor(client -> read.get());
        return pixel.get();
    }
}
