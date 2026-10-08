package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.PushReaction;

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

    /**
     * The prices a navigation component states are the ones the pathfinder plans with.
     *
     * Bedrock avoids nothing a pack does not ask it to, so the plain ember walks through a block
     * that hurts, and Java refuses lava to every mob. The wading group asks for water and damaging
     * blocks to be avoided and lets the entity cross lava, which reverses all three.
     */
    @GameTest()
    public void aNavigationComponentPricesTheGroundItNames(GameTestHelper helper) {
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(Platform.damagingMalus(ember) == 0,
            EMBER + " never asked to avoid damaging blocks and its pathfinder prices them at "
                + Platform.damagingMalus(ember) + ".");
        helper.assertTrue(Platform.lavaMalus(ember) < 0, EMBER + " never asked to cross lava and may.");
        helper.assertTrue(Platform.waterMalus(ember) == 0, EMBER + " never asked to avoid water and does.");

        EntityEvents.trigger(ember, "tp:ember_wade", null, ServerHooks.state());
        helper.assertTrue(Platform.damagingMalus(ember) != 0,
            EMBER + " asked to avoid damaging blocks and its pathfinder still walks through them.");
        helper.assertTrue(Platform.lavaMalus(ember) == 0, EMBER + " asked to cross lava and may not.");
        helper.assertTrue(Platform.waterMalus(ember) > 0, EMBER + " asked to avoid water and wades in.");
        helper.succeed();
    }

    /**
     * `minecraft:navigation.generic` swimming and never walking is a fish, and gets a fish's navigator.
     *
     * Bedrock describes a fish, a turtle and an axolotl with the same component, so the allowances
     * inside it pick between Java's swimming, amphibious and ground navigators. A ground navigator
     * plans a fish's way along the sea floor.
     */
    @GameTest()
    public void aGenericNavigationThatOnlySwimsGetsASwimmingNavigator(GameTestHelper helper) {
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        if (ember == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(ember, "tp:ember_fin", null, ServerHooks.state());
        helper.assertTrue(ember.getNavigation() instanceof WaterBoundPathNavigation,
            EMBER + " gained a navigation that swims and never walks and plans with "
                + ember.getNavigation().getClass().getSimpleName() + ".");

        EntityEvents.trigger(ember, "tp:ember_unfin", null, ServerHooks.state());
        helper.assertTrue(ember.getNavigation() instanceof GroundPathNavigation,
            EMBER + " lost the group that made it swim and still plans with "
                + ember.getNavigation().getClass().getSimpleName() + ".");
        helper.succeed();
    }

    /**
     * A piston leaves alone an entity whose `minecraft:pushable` says pistons may not move it.
     *
     * Java asks the entity what a piston does to it, and the ember's ghostly group answers no to
     * pistons as well as to other entities.
     */
    @GameTest()
    public void aPistonLeavesAloneAnEntityThatMayNotBePushedByOne(GameTestHelper helper) {
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        if (ember == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(ember.getPistonPushReaction() != PushReaction.IGNORE,
            EMBER + " never refused a piston and a piston leaves it where it is.");
        EntityEvents.trigger(ember, "tp:ember_fade", null, ServerHooks.state());
        helper.assertTrue(ember.getPistonPushReaction() == PushReaction.IGNORE,
            EMBER + " gained a minecraft:pushable refusing pistons and a piston still moves it.");
        helper.succeed();
    }

    /**
     * An entity nobody owns refuses breeding food unless its pack says taming is not needed.
     *
     * Bedrock's `require_tame` is yes by default, and the ember's fertile group never mentions it,
     * so the food only works once the ember belongs to somebody.
     */
    @GameTest()
    public void breedingFoodWaitsForAnOwnerByDefault(GameTestHelper helper) {
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        if (ember == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(ember, "tp:ember_make_fertile", null, ServerHooks.state());
        Player owner = holding(helper, ember, Items.WHEAT);
        ember.mobInteract(owner, InteractionHand.MAIN_HAND);
        helper.assertFalse(ember.inTheMood(), EMBER + " belongs to nobody and was put in the mood.");

        ember.setOwner(owner);
        ember.mobInteract(owner, InteractionHand.MAIN_HAND);
        helper.assertTrue(ember.inTheMood(), EMBER + " belongs to its feeder and stayed out of the mood.");
        helper.succeed();
    }

    /**
     * Breeding raises the event `breeds_with` names for the mate, carries the child where the pack
     * says the entity becomes pregnant, and rests the entity for `breed_cooldown` seconds.
     */
    @GameTest()
    public void breedingRaisesTheMatesEventAndThenRests(GameTestHelper helper) {
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        AddonEntity mate = BehaviourGameTests.spawn(helper, EMBER);
        if (ember == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(ember, "tp:ember_make_fertile", null, ServerHooks.state());
        Player owner = holding(helper, ember, Items.WHEAT);
        ember.setOwner(owner);
        helper.assertTrue(ember.breedsWith(mate), EMBER + " names its own kind as a mate and refuses it.");

        helper.assertFalse(ember.conceive(mate),
            EMBER + " becomes pregnant by its pack's word and a child was born anyway.");
        helper.assertTrue(Boolean.TRUE.equals(ember.value("tp:pregnant")),
            EMBER + " bred and never raised the event breeds_with names for its mate.");

        ember.mobInteract(owner, InteractionHand.MAIN_HAND);
        helper.assertFalse(ember.inTheMood(), EMBER + " was put in the mood again the moment it bred.");
        ember.tickCount += BREED_COOLDOWN + 1;
        ember.mobInteract(owner, InteractionHand.MAIN_HAND);
        helper.assertTrue(ember.inTheMood(), EMBER + " rested its two seconds and still refuses food.");
        helper.succeed();
    }

    /** The two seconds the ember's fertile group rests after breeding, in Java's ticks. */
    private static final int BREED_COOLDOWN = 2 * 20;

    /** A player standing at the entity with a stack of the item in hand. */
    private static Player holding(GameTestHelper helper, AddonEntity entity, Item item) {
        Player player = Platform.mockPlayer(helper);
        Platform.placeAt(player, entity.getX(), entity.getY(), entity.getZ(), 0f, 0f);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, 16));
        return player;
    }

    /**
     * An entity following its owner steps to the owner's side once the owner is far ahead.
     *
     * Java writes the same walk into its tamable animals alone, so the ember's loyal group is what
     * gives it one, and the owner standing fifteen blocks off is past the twelve Java's pets
     * teleport at.
     */
    @GameTest()
    public void anEntityFollowingItsOwnerCatchesUpWithThem(GameTestHelper helper) {
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        if (ember == null) {
            helper.succeed();
            return;
        }

        net.minecraft.server.level.ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        BlockPos far = helper.absolutePos(STAND).east(FAR_AHEAD);
        Platform.placeAt(owner, far.getX() + 0.5, far.getY(), far.getZ() + 0.5, 0f, 0f);
        ember.setOwner(owner);
        EntityEvents.trigger(ember, "tp:ember_befriend", null, ServerHooks.state());
        ember.setNoAi(false);

        live(ember, CATCH_UP_TICKS);
        helper.assertTrue(ember.distanceTo(owner) < FAR_AHEAD / 2,
            EMBER + " follows its owner and is still " + ember.distanceTo(owner) + " blocks behind.");
        helper.succeed();
    }

    /** How far ahead the owner stands, past the distance a pet teleports at. */
    private static final int FAR_AHEAD = 15;

    /** Long enough for the follow goal to start and take its first step. */
    private static final int CATCH_UP_TICKS = 20;

    /**
     * A hunter picks what its `entity_types` filters name, even with a player standing nearer.
     *
     * The ember's hunting group names the wisp's own family, which no Java class stands for, so a
     * goal choosing by class alone falls back to hunting players.
     */
    @GameTest()
    public void aHunterPicksTheFamilyItsFiltersName(GameTestHelper helper) {
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        AddonEntity wisp = BehaviourGameTests.spawn(helper, WISP, STAND.east(4));
        if (ember == null || wisp == null) {
            helper.succeed();
            return;
        }

        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos near = helper.absolutePos(STAND).east(1);
        Platform.placeAt(player, near.getX() + 0.5, near.getY(), near.getZ() + 0.5, 0f, 0f);
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        EntityEvents.trigger(ember, "tp:ember_hunt", null, ServerHooks.state());
        ember.setNoAi(false);
        live(ember, SEARCH_TICKS);
        helper.assertTrue(ember.getTarget() == wisp,
            EMBER + " hunts the wisp's family and set its sights on " + ember.getTarget() + ".");
        helper.succeed();
    }

    /**
     * An entity turns on an attacker its `hurt_by_target` entries name, and not on one they leave out.
     *
     * The ember's vengeful group names the wisp's family, so a player's blow goes unanswered.
     */
    @GameTest()
    public void anEntityAnswersOnlyTheAttackersItsEntriesName(GameTestHelper helper) {
        AddonEntity ember = BehaviourGameTests.spawn(helper, EMBER);
        AddonEntity wisp = BehaviourGameTests.spawn(helper, WISP, STAND.east(2));
        if (ember == null || wisp == null) {
            helper.succeed();
            return;
        }

        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        EntityEvents.trigger(ember, "tp:ember_avenge", null, ServerHooks.state());
        ember.setNoAi(false);

        Platform.hurt(ember, helper.getLevel(), ember.damageSources().playerAttack(player), 0.5f);
        live(ember, SEARCH_TICKS);
        helper.assertTrue(ember.getTarget() == null,
            EMBER + " answers only the wisp's family and turned on the player who hit it.");

        // Out of the moment after a blow in which Java ignores the next one.
        ember.invulnerableTime = 0;
        Platform.hurt(ember, helper.getLevel(), ember.damageSources().mobAttack(wisp), 0.5f);
        live(ember, SEARCH_TICKS);
        helper.assertTrue(ember.getTarget() == wisp,
            EMBER + " was hit by the wisp its entries name and set its sights on " + ember.getTarget() + ".");
        helper.succeed();
    }

    /** Long enough for a target goal searching every tick to have run several times. */
    private static final int SEARCH_TICKS = 10;

    /**
     * Runs the entity's own ticks the way the level does, which counts its age first: Java's goals
     * tell one blow from the next by the age it landed at.
     */
    private static void live(AddonEntity entity, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            entity.tickCount++;
            entity.tick();
        }
    }
}
