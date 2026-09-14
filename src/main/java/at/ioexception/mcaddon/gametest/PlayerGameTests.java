package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.bedrock.behaviour.PlayerDefinition;
import at.ioexception.mcaddon.mixin.FoodDataAccessor;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a pack's own `entities/player.json` does to a player in a world.
 *
 * None of it is a function of its input: the starting state is written into the `FoodData` the game
 * built, and the exhaustion prices only exist where the game spends them, which is inside methods a
 * unit test has no player to call. With no pack loaded there is no definition and each of these
 * succeeds without asserting.
 */
public final class PlayerGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /**
     * The five attributes reach the player the pack said they would.
     *
     * Java builds its own numbers into `FoodData` and into `Player.experienceLevel`, so what this
     * proves is that the pack's numbers replace them rather than being read and dropped.
     */
    @GameTest()
    public void theStartingStateThePackWroteReachesAPlayer(GameTestHelper helper) {
        PlayerDefinition declared = PlayerDefinition.declared();
        if (declared == null) {
            helper.succeed();
            return;
        }

        Player player = Platform.mockPlayer(helper);
        declared.applyTo(player);

        FoodData food = player.getFoodData();
        assertAttribute(helper, "hunger", declared.hunger().value(), food.getFoodLevel());
        assertAttribute(helper, "saturation", declared.saturation().value(), food.getSaturationLevel());
        assertAttribute(helper, "exhaustion", declared.exhaustion().value(),
            ((FoodDataAccessor) food).mcaddon$exhaustion());
        assertAttribute(helper, "level", declared.level().value(), player.experienceLevel);
        assertAttribute(helper, "experience", declared.experience().value(),
            player.experienceProgress);
        helper.succeed();
    }

    /**
     * A value over the ceiling the pack stated is held under it.
     *
     * `FoodData` caps the food bar and the exhaustion at literals of its own and Java caps the level
     * at nothing, so each of these is above what the pack allows and below what Java would stop.
     */
    @GameTest()
    public void aCeilingThePackWroteHoldsTheValueUnderIt(GameTestHelper helper) {
        PlayerDefinition declared = PlayerDefinition.declared();
        if (declared == null) {
            helper.succeed();
            return;
        }

        Player player = Platform.mockPlayer(helper);
        FoodData food = player.getFoodData();
        food.setSaturation(20.0f);
        ((FoodDataAccessor) food).mcaddon$setExhaustion(20.0f);
        player.experienceLevel = 90;
        declared.capped(player);

        assertAtMost(helper, "saturation", declared.saturation().max(), food.getSaturationLevel());
        assertAtMost(helper, "exhaustion", declared.exhaustion().max(),
            ((FoodDataAccessor) food).mcaddon$exhaustion());
        assertAtMost(helper, "level", declared.level().max(), player.experienceLevel);
        helper.succeed();
    }

    /**
     * Breaking a block costs what the pack priced mining at.
     *
     * `Block.playerDestroy` is where the game spends its own 0.005, so breaking a block through it
     * is what proves the price arrives at the call site rather than only at the reader.
     */
    @GameTest()
    public void breakingABlockCostsThePacksMiningPrice(GameTestHelper helper) {
        PlayerDefinition declared = PlayerDefinition.declared();
        if (declared == null) {
            helper.succeed();
            return;
        }

        Player player = Platform.mockPlayer(helper);
        FoodData food = player.getFoodData();
        ((FoodDataAccessor) food).mcaddon$setExhaustion(0.0f);

        BlockPos at = helper.absolutePos(STAND);
        BlockState broken = Blocks.STONE.defaultBlockState();
        Blocks.STONE.playerDestroy(helper.getLevel(), player, at, broken, null, ItemStack.EMPTY);

        float spent = ((FoodDataAccessor) food).mcaddon$exhaustion();
        float expected = declared.priced(PlayerDefinition.Action.MINE,
            PlayerDefinition.Action.MINE.javaPrice());
        helper.assertTrue(Math.abs(spent - expected) < 0.0001f,
            "Breaking a block cost " + spent + " exhaustion where the pack priced it at " + expected);
        helper.succeed();
    }

    /**
     * Moving is charged against the way the player was moving at the time.
     *
     * The one method that charges for movement has a branch per way of moving, and the branch is
     * read back off the player rather than off the charge, so this needs a player standing on dry
     * land in a world. Walking is the half that cannot be scaled: Java charges nothing for it, so
     * the distance has to arrive separately.
     */
    @GameTest()
    public void movingIsChargedAtThePriceForTheWayThePlayerMoved(GameTestHelper helper) {
        PlayerDefinition declared = PlayerDefinition.declared();
        if (declared == null) {
            helper.succeed();
            return;
        }

        Player player = Platform.mockPlayer(helper);
        float sprinting = PlayerDefinition.movementCost(player, PlayerDefinition.Action.SPRINT
            .javaPrice(), 0.0);
        assertPrice(helper, "sprinting", declared.priced(PlayerDefinition.Action.SPRINT,
            PlayerDefinition.Action.SPRINT.javaPrice()), sprinting);
        assertPrice(helper, "walking two blocks", declared.walkPrice(2.0),
            PlayerDefinition.movementCost(player, 0.0f, 2.0));
        helper.succeed();
    }

    private static void assertPrice(GameTestHelper helper, String what, float wanted, float found) {
        helper.assertTrue(Math.abs(wanted - found) < 0.0001f,
            "The pack priced " + what + " at " + wanted + " and the game was charged " + found + ".");
    }

    private static void assertAttribute(GameTestHelper helper, String name, Float wanted,
        float found) {
        if (wanted == null) return;

        helper.assertTrue(Math.abs(wanted - found) < 0.0001f,
            "The pack asked for " + wanted + " " + name + " and the player has " + found + ".");
    }

    private static void assertAtMost(GameTestHelper helper, String name, Float ceiling, float found) {
        if (ceiling == null) return;

        helper.assertTrue(found <= ceiling,
            "The pack capped " + name + " at " + ceiling + " and the player holds " + found + ".");
    }
}
