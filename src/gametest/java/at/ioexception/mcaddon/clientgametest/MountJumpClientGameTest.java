package at.ioexception.mcaddon.clientgametest;

import at.ioexception.mcaddon.client.render.EntityProperties;
import at.ioexception.mcaddon.entity.AddonEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.world.phys.Vec3;

/**
 * A player riding an add-on mount on a dedicated server power jumps it and dashes it with the jump key.
 *
 * `tp:steed` is steered by its rider and carries neither ability until an event adds the group with
 * one: `tp:steed_leaping` holds `minecraft:can_power_jump` over a jump strength of 0.9, and
 * `tp:steed_dashing` holds a `minecraft:dash_action` with a six second cooldown. The client moves a
 * mount its player steers, so the jump key has to reach the mount there, and the server has to hear
 * of the dash to publish `can_dash`. Without either group the key is the rider's own and no jump bar
 * is shown.
 */
public final class MountJumpClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 200;

    /** Ticks held for a full charge, which Java's riding jump reaches at ten. */
    private static final int CHARGED = 10;

    /** Ticks held for a dash, long enough that the riding jump counts a charge at all. */
    private static final int TAPPED = 3;

    /** How long a jump or a dash is watched for, which is past the time either takes to land. */
    private static final int WATCHED = 40;

    /** Twice the 1.25 blocks an ordinary jump at Java's 0.42 clears. */
    private static final double LEAP_HEIGHT = 2.5;

    /** About half of the five and a half blocks a dash of 0.8 blocks a tick carries the steed. */
    private static final double DASH_REACH = 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = Worlds.server(context);
            var connection = server.connect()) {
            Worlds.waitForChunks(connection);
            Stage.build(context, server, connection, "survival");
            server.runCommand("summon tp:steed 0.5 " + (Stage.FLOOR + 1) + " 0.5 {Rotation:[0f,0f]}");
            AddonEntity steed = Entities.waitForClient(context, "tp:steed");
            server.runCommand("ride @p mount @e[type=tp:steed,limit=1]");
            Worlds.waitForClient(context, client -> client.player.getVehicle() == steed && steed.onGround(),
                PATIENCE, () -> "Expected the player to sit on a tp:steed standing on the stage.");

            Assert.that(context.computeOnClient(client -> client.player.jumpableVehicle() == null),
                "Expected no jump bar for a tp:steed with neither a power jump nor a dash.");
            leap(context, server, steed);
            dash(context, server, steed);
        }
    }

    private static void leap(ClientGameTestContext context, TestServerContext server, AddonEntity steed) {
        server.runCommand("bedrock event entity @e[type=tp:steed] tp:steed_leap");
        Worlds.waitForClient(context, client -> client.player.jumpableVehicle() == steed, PATIENCE,
            () -> "Expected tp:steed_leap to give the client's tp:steed a jump bar.");

        double ground = context.computeOnClient(client -> steed.getY());
        context.getInput().holdKeyFor(options -> options.keyJump, CHARGED);
        double peak = ground;
        for (int tick = 0; tick < WATCHED; tick++) {
            peak = Math.max(peak, context.computeOnClient(client -> steed.getY()));
            context.waitTick();
        }
        Assert.that(peak - ground > LEAP_HEIGHT, "Expected a fully charged jump to lift tp:steed more than "
            + LEAP_HEIGHT + " blocks and it rose " + (peak - ground) + ".");
        Worlds.waitForClient(context, client -> steed.onGround(), PATIENCE,
            () -> "Expected tp:steed to land after its jump.");
    }

    private static void dash(ClientGameTestContext context, TestServerContext server, AddonEntity steed) {
        server.runCommand("bedrock event entity @e[type=tp:steed] tp:steed_dash");
        Worlds.waitForClient(context, client -> Boolean.TRUE.equals(canDash(steed)), PATIENCE,
            () -> "Expected tp:steed_dash to publish can_dash to the client and found " + canDash(steed) + ".");

        double reach = dashed(context, steed);
        Assert.that(reach > DASH_REACH, "Expected a dash to carry tp:steed more than " + DASH_REACH
            + " blocks and it went " + reach + ".");
        Worlds.waitForClient(context, client -> Boolean.FALSE.equals(canDash(steed)), PATIENCE,
            () -> "Expected the server to publish can_dash as false while the dash cools down.");

        double again = dashed(context, steed);
        Assert.that(again < 0.1, "Expected a second dash inside the six second cooldown to do nothing and"
            + " tp:steed went " + again + ".");
        Worlds.waitForClient(context, client -> Boolean.TRUE.equals(canDash(steed)), PATIENCE,
            () -> "Expected can_dash back once the six second cooldown had run out.");
    }

    /** How far across the stage the steed went after the jump key was pressed briefly. */
    private static double dashed(ClientGameTestContext context, AddonEntity steed) {
        Vec3 from = context.computeOnClient(client -> steed.position());
        context.getInput().holdKeyFor(options -> options.keyJump, TAPPED);
        context.waitTicks(WATCHED);
        Vec3 to = context.computeOnClient(client -> steed.position());
        return Math.hypot(to.x - from.x, to.z - from.z);
    }

    private static Object canDash(AddonEntity steed) {
        return EntityProperties.of(steed.getId()).get("can_dash");
    }
}
