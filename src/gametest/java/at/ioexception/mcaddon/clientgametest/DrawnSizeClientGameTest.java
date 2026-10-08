package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.Submitted;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.AABB;

/**
 * An entity's model is drawn standing where the entity stands, at the size its pack gave it.
 *
 * `tp:wisp` draws a four pixel cube resting on its feet with no animation, and its `tp:calmed`
 * group carries a `minecraft:scale` of 0.75 that the server switches on and the client has to learn
 * of before it can draw it. `tp:sprite` is declared young with no scale of its own, which Bedrock
 * draws at the size its young geometry was written at.
 */
public final class DrawnSizeClientGameTest implements FabricClientGameTest {
    /** How far a drawn point may sit from where it belongs, in blocks. */
    private static final double TOLERANCE = 0.005;

    private static final double CUBE_HEIGHT = 4 / 16.0;
    private static final double CALMED_SCALE = 0.75;
    private static final double WISP_BOX_HEIGHT = 0.6;

    /** `tp:sprite`'s young geometry is seventeen pixels and its cap two more. */
    private static final double YOUNG_SPRITE_HEIGHT = 19 / 16.0;

    /** How far the sprite's idle animation moves its body up and down, in blocks. */
    private static final double SPRITE_BOB = 0.5 / 16.0;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            wisp(context, world);
            sprite(context, world);
        }
    }

    private static void wisp(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon tp:wisp ~ ~ ~3 {NoAI:1b}");
        AddonEntity wisp = Entities.waitForClient(context, "tp:wisp");
        Assert.near(wisp.getBbHeight(), WISP_BOX_HEIGHT, 0.001, "the box height of an unscaled tp:wisp");

        AABB plain = context.computeOnClient(client -> Submitted.entity(wisp).box());
        Assert.that(plain != null, "tp:wisp has a client definition and nothing was drawn for it.");
        Assert.near(plain.minY, 0, TOLERANCE, "the lowest drawn point of tp:wisp above its feet");
        Assert.near(plain.getYsize(), CUBE_HEIGHT, TOLERANCE, "the drawn height of tp:wisp");

        world.getServer().runCommand("bedrock event entity @e[type=tp:wisp] tp:settle");
        context.waitFor(client -> Math.abs(wisp.getBbHeight() - WISP_BOX_HEIGHT * CALMED_SCALE) < 0.001);

        AABB calmed = context.computeOnClient(client -> Submitted.entity(wisp).box());
        Assert.near(calmed.minY, 0, TOLERANCE, "the lowest drawn point of a scaled tp:wisp above its feet");
        Assert.near(calmed.getYsize(), CUBE_HEIGHT * CALMED_SCALE, TOLERANCE,
            "the drawn height of tp:wisp once its group scaled it by " + CALMED_SCALE);
    }

    private static void sprite(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runCommand("summon tp:sprite ~ ~ ~-3 {NoAI:1b}");
        AddonEntity sprite = Entities.waitForClient(context, "tp:sprite");

        AABB young = context.computeOnClient(client -> Submitted.entity(sprite).box());
        Assert.that(young != null, "tp:sprite has a client definition and nothing was drawn for it.");
        Assert.near(young.minY, 0, SPRITE_BOB + TOLERANCE, "the lowest drawn point of tp:sprite above its feet");
        Assert.near(young.maxY, YOUNG_SPRITE_HEIGHT, SPRITE_BOB + TOLERANCE,
            "the highest drawn point of young tp:sprite, which Java's own halving of the young would shrink");
    }
}
