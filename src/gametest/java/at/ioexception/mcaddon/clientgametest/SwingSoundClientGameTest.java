package at.ioexception.mcaddon.clientgametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

/**
 * A swing heard as the sounds the held item's `minecraft:swing_sounds` names.
 *
 * `tp:wand` names a level sound event for each moment, and the resource pack's
 * `individual_event_sounds` gives each a definition of its own: a swing at the sky is
 * `tp:wand.whoosh`, a blow that lands is `tp:wand.hit` and one struck while falling is
 * `tp:wand.crit`. Each is played by the server, so the client hears it from there.
 */
public final class SwingSoundClientGameTest implements FabricClientGameTest {
    private static final int PATIENCE = 40;

    /** Long enough for the attack to charge fully and the pig's last hurt to wear off. */
    private static final int RECHARGE = 25;

    private static final int CRIT_TRIES = 3;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            Worlds.waitForChunks(world);
            Stage.build(context, world, "survival");
            Stage.hold(context, world, "tp:wand", 1);
            context.runOnClient(client -> Heard.clear());

            context.getInput().lookAt(0, -90);
            context.waitTicks(RECHARGE);
            context.getInput().pressKey(options -> options.keyAttack);
            Worlds.waitForClient(context, client -> !Heard.of("tp:wand.whoosh").isEmpty(), PATIENCE,
                () -> "A swing of tp:wand at the sky plays its attack_miss sound, and tp:wand.whoosh was never heard.");

            world.getServer().runCommand("summon minecraft:pig 0.5 " + (Stage.FLOOR + 1) + " 2.0 {NoAI:1b}");
            Vec3 pig = new Vec3(0.5, Stage.FLOOR + 1.45, 2.0);
            Stage.lookAt(context, pig);
            context.waitTicks(RECHARGE);
            context.getInput().pressKey(options -> options.keyAttack);
            Worlds.waitForClient(context, client -> !Heard.of("tp:wand.hit").isEmpty(), PATIENCE,
                () -> "A blow of tp:wand on a pig plays its attack_hit sound, and tp:wand.hit was never heard.");
            Assert.that(Heard.of("minecraft:entity.player.attack.strong").isEmpty(),
                "tp:wand's attack_hit sound plays in place of Java's own, which was heard as well.");

            for (int tried = 0; tried < CRIT_TRIES && Heard.of("tp:wand.crit").isEmpty(); tried++) {
                context.waitTicks(RECHARGE);
                world.getServer().runCommand("tp @a 0.5 " + (Stage.FLOOR + 2) + " 0.5 ~ ~");
                context.waitFor(client -> client.player.getDeltaMovement().y < 0 && !client.player.onGround());
                Stage.lookAt(context, pig);
                context.getInput().pressKey(options -> options.keyAttack);
                context.waitTicks(10);
            }
            Assert.that(!Heard.of("tp:wand.crit").isEmpty(),
                "A blow of tp:wand struck while falling plays its attack_critical_hit sound, and tp:wand.crit"
                    + " was never heard in " + CRIT_TRIES + " tries.");
        }
    }
}
