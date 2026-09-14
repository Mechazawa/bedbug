package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.util.Json;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The arithmetic between a Bedrock quantity and the Java one it becomes.
 *
 * Both sides of each of these is a plain decimal, so a wrong unit costs no error and no warning: a
 * seat lands inside the mount's feet and a chance fires a tenth as often as the pack asked for, and
 * everything still runs. The numbers below are Mojang's own, taken from the vanilla behaviour pack.
 */
class BedrockUnitsTest {

    /** A boat seat sits a fifth of a block down, not a fifth of a pixel. */
    @Test
    void readsASeatInBlocks() {
        assertEquals(new Vec3(0, -0.2, 0),
            BedrockUnits.blocks(vector("[0.0, -0.2, 0.0]"), null));
    }

    /** A horse seat is more than a block up, which is where a rider's feet go. */
    @Test
    void readsARiddenSeatWellAboveTheFeet() {
        assertEquals(new Vec3(0, 1.1, -0.2),
            BedrockUnits.blocks(vector("[0.0, 1.1, -0.2]"), null));
    }

    @Test
    void keepsTheFallbackForAVectorAPackDidNotWrite() {
        Vec3 fallback = new Vec3(32, 16, 32);
        assertSame(fallback, BedrockUnits.blocks(null, fallback));
        assertSame(fallback, BedrockUnits.blocks(vector("[8, 4]"), fallback));
        assertSame(fallback, BedrockUnits.blocks(vector("8"), fallback));
    }

    /**
     * A bee's own chance of ripening a crop, asked once every ten ticks instead of every one.
     *
     * Three per cent per tick comes to a little over a quarter across ten of them, and asking the
     * pack's own number once per interval would leave it at three.
     */
    @Test
    void spreadsAPerTickChanceOverTheIntervalItIsAskedOn() {
        assertEquals(0.2626, BedrockUnits.chanceOverTicks(0.03, 10), 0.0001);
    }

    @Test
    void leavesAChanceAskedEveryTickAloneAndHoldsBothEnds() {
        assertEquals(0.03, BedrockUnits.chanceOverTicks(0.03, 1), 1e-9);
        assertEquals(0, BedrockUnits.chanceOverTicks(0, 10), 1e-9);
        assertEquals(1, BedrockUnits.chanceOverTicks(1, 10), 1e-9);
        assertEquals(0, BedrockUnits.chanceOverTicks(0.5, 0), 1e-9);
    }

    /**
     * A crit particle leaves at two blocks a second, which is a tenth of a block each tick.
     *
     * Handed to Java as it stands it would cross forty blocks a second, so the whole effect lands
     * off the screen inside its own lifetime.
     */
    @Test
    void readsAParticleSpeedInBlocksPerTick() {
        assertEquals(0.1, BedrockUnits.blocksPerTick(2), 1e-9);
    }

    /**
     * Mojang writes gravity as 9.8 blocks a second each second, and a crit falls at ten.
     *
     * One second of that is half a block a tick taken off the upward speed, which is what a
     * conversion applied once instead of twice would apply on every tick of the twenty.
     */
    @Test
    void readsAParticleAccelerationInBlocksPerTickSquared() {
        assertEquals(-0.0245, BedrockUnits.blocksPerTickSquared(-9.8), 1e-9);

        double perTick = 0;
        for (int tick = 0; tick < BedrockUnits.TICKS_PER_SECOND; tick++) {
            perTick += BedrockUnits.blocksPerTickSquared(-9.8);
        }
        assertEquals(-9.8, perTick * BedrockUnits.TICKS_PER_SECOND, 1e-9);
    }

    private static com.google.gson.JsonElement vector(String written) {
        return Json.parse(written);
    }
}
