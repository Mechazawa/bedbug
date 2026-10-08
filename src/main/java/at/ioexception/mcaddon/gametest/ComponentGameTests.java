package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * The fields inside an entity component that Java has a place for, read back through that place.
 *
 * Each of these was once taken for something Java decides alone, so a regression puts the entity
 * back on Java's own number with nothing in a count to show it.
 */
public final class ComponentGameTests {
    private static final String EMBER = "tp:ember";
    private static final String WISP = "tp:wisp";

    /** Where an entity is stood, clear of the structure's edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /** The seven seconds of air `tp:wisp` declares, in Java's ticks. */
    private static final int WISP_SUPPLY = 7 * 20;

    /** The two seconds of air the ember's gilled group declares, in Java's ticks. */
    private static final int GILLED_SUPPLY = 2 * 20;

    /** Enough ticks for a count of air to have moved, well short of drowning. */
    private static final int BREATHS = 10;

    /**
     * `total_supply` is seconds of air, and the entity arrives holding all of it.
     *
     * Java gives every mob three hundred ticks, which is fifteen seconds, so a supply read as Java's
     * own default or as a number of ticks shows up as a count other than one hundred and forty.
     */
    @GameTest()
    public void aBreathableSupplyIsCountedInSeconds(GameTestHelper helper) {
        AddonEntity wisp = BehaviourGameTests.spawn(helper, WISP);
        if (wisp == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(wisp.getMaxAirSupply() == WISP_SUPPLY,
            WISP + " declares seven seconds of air and holds " + wisp.getMaxAirSupply() + " ticks.");
        helper.assertTrue(wisp.getAirSupply() == WISP_SUPPLY,
            WISP + " arrived holding " + wisp.getAirSupply() + " of its " + WISP_SUPPLY + " ticks of air.");
        helper.succeed();
    }

    /**
     * An entity with no `minecraft:breathable` never runs out of air under water.
     *
     * Bedrock leaves an entity that declares nothing to breathe unable to drown, which is how its
     * iron golem stands on a river bed. Java drowns every mob it is not told otherwise about.
     */
    @GameTest()
    public void anEntityWithNothingToBreatheNeverDrowns(GameTestHelper helper) {
        helper.setBlock(STAND, Blocks.WATER);
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        if (ember == null) {
            helper.succeed();
            return;
        }

        int full = ember.getAirSupply();
        for (int tick = 0; tick < BREATHS; tick++) {
            ember.tick();
        }
        helper.assertTrue(ember.getAirSupply() == full,
            EMBER + " declares no minecraft:breathable and lost air under water, "
                + full + " down to " + ember.getAirSupply() + ".");
        helper.succeed();
    }

    /**
     * An entity that breathes water alone holds its breath on land and fills up again in water.
     *
     * Java counts a mob's air down under water and nowhere else, so a fish out of water is the case
     * this mod has to count itself, and a group is where a pack switches such breathing on.
     */
    @GameTest()
    public void aGroupThatBreathesOnlyWaterHoldsItsBreathOnLand(GameTestHelper helper) {
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        if (ember == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(ember, "tp:ember_grow_gills", null, ServerHooks.state());
        helper.assertTrue(ember.getMaxAirSupply() == GILLED_SUPPLY,
            EMBER + " gained two seconds of air from a group and holds " + ember.getMaxAirSupply() + ".");

        int before = ember.getAirSupply();
        for (int tick = 0; tick < BREATHS; tick++) {
            ember.tick();
        }
        helper.assertTrue(ember.getAirSupply() == before - BREATHS,
            EMBER + " breathes only water and its air went from " + before + " to "
                + ember.getAirSupply() + " in " + BREATHS + " ticks on land.");

        helper.setBlock(STAND, Blocks.WATER);
        ember.tick();
        helper.assertTrue(ember.getAirSupply() == GILLED_SUPPLY,
            EMBER + " breathes water and did not fill its air in one breath of it.");
        helper.succeed();
    }
}
