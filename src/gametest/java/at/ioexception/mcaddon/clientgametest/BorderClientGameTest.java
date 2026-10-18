package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.block.BorderColumns;
import at.ioexception.mcaddon.client.render.PlayerRules;
import at.ioexception.mcaddon.server.BedrockRules;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Bedrock's border block on a player's own client: the player walking at it is stopped at its
 * column three blocks above it, and it sends dust up and down that column only while
 * `showbordereffect` is on.
 */
public final class BorderClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 200;
    private static final int WALKING = 30;
    private static final int TICKS = 40;
    private static final Vec3 STANDING = new Vec3(0.5, 101, 0.5);
    private static final BlockPos BORDER = new BlockPos(0, 98, 3);

    /** The furthest south the player's middle gets, its box being 0.3 blocks either side of it. */
    private static final double STOP = BORDER.getZ() - 0.3;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            world.getServer().runCommand("fill -8 100 -8 8 100 8 minecraft:stone");
            world.getServer().runCommand("bedrock setblock " + BORDER.getX() + " " + BORDER.getY() + " "
                + BORDER.getZ() + " minecraft:border_block");
            world.getServer().runCommand("tp @a " + STANDING.x + " " + STANDING.y + " " + STANDING.z + " 0 0");
            Worlds.waitForClient(context, client -> BorderColumns.is(client.level.getBlockState(BORDER))
                    && client.player.onGround() && client.player.position().distanceTo(STANDING) < 0.01, PATIENCE,
                () -> "Expected the border block under the floor and the player standing at " + STANDING + ".");

            context.getInput().holdKeyFor(options -> options.keyUp, WALKING);
            context.waitTicks(5);
            double reached = context.computeOnClient(client -> client.player.getZ());
            Assert.that(reached > STOP - 0.1 && reached <= STOP + 1.0E-6,
                "Expected the player walking south to stop at z " + STOP + " beside the border column, reached "
                    + reached + ".");
            double held = world.getServer().computeOnServer(server -> Stage.player(server).getZ());
            Assert.that(held <= STOP + 1.0E-6, "Expected the server to hold the player at z " + STOP + ", held "
                + held + ".");

            List<Born.Plain> shown = dust(context);
            Assert.that(shown.stream().anyMatch(born -> born.velocity().y > 0)
                    && shown.stream().anyMatch(born -> born.velocity().y < 0),
                "Expected the border block to send dust both up and down its column, sent " + shown.size() + ".");
            world.getServer().runCommand("bedrock gamerule showbordereffect false");
            Worlds.waitForClient(context, client -> !PlayerRules.on(BedrockRules.SHOW_BORDER_EFFECT, true), PATIENCE,
                () -> "Expected showbordereffect turned off to reach the client.");
            Assert.that(dust(context).isEmpty(), "Expected no dust from the border block with showbordereffect off.");
            world.getServer().runCommand("bedrock gamerule showbordereffect true");
        }
    }

    /** The particles the border block adds over {@link #TICKS} of its own display ticks, in its column. */
    private static List<Born.Plain> dust(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            Born.clear();
            BlockState state = client.level.getBlockState(BORDER);
            RandomSource random = RandomSource.create(1);
            for (int i = 0; i < TICKS; i++) state.getBlock().animateTick(state, client.level, BORDER, random);
            return Born.plain().stream()
                .filter(born -> Math.floor(born.at().x) == BORDER.getX() && Math.floor(born.at().z) == BORDER.getZ())
                .toList();
        });
    }
}
