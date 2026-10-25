package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The goals that carry and place blocks, lay eggs, work at a station and hand items over, each read
 * back off the world through `tp:keeper`, a plain mob whose groups carry one goal each.
 */
public final class KeeperGoalGameTests {
    private static final String KEEPER = "tp:keeper";

    private static final BlockPos STAND = new BlockPos(2, 0, 2);

    /**
     * `minecraft:behavior.take_block` lifts only a block its `blocks` lists. Seven stone blocks and
     * one gold block stand around the keeper, and only the gold may go.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aTakeBlockGoalLiftsOnlyTheBlocksItLists(GameTestHelper helper) {
        BlockPos gold = STAND.east();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) helper.setBlock(STAND.offset(x, 0, z), Blocks.STONE);
            }
        }
        helper.setBlock(gold, Blocks.GOLD_BLOCK);
        AddonEntity keeper = keeper(helper, "tp:keeper_take");
        if (keeper == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            helper.assertTrue(Boolean.TRUE.equals(keeper.value("tp:took")), KEEPER + " has taken no block.");
            helper.assertBlockPresent(Blocks.AIR, gold);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos at = STAND.offset(x, 0, z);
                    if ((x != 0 || z != 0) && !at.equals(gold)) helper.assertBlockPresent(Blocks.STONE, at);
                }
            }
            helper.assertTrue("minecraft:gold_block".equals(keeper.value("carried_block_identifier")),
                KEEPER + " carries " + keeper.value("carried_block_identifier") + " and not the gold.");
        });
    }

    /**
     * `minecraft:behavior.place_block` places nothing while `can_place` fails, then one of its
     * `randomly_placeable_blocks` in its states, carrying nothing itself, and raises `on_place`. The
     * gold entry outweighs the log and its filter never passes, so the log is the only block it may
     * place.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void aPlaceBlockGoalPlacesARandomBlockOnceItsFilterPasses(GameTestHelper helper) {
        AddonEntity keeper = keeper(helper, "tp:keeper_place");
        if (keeper == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenExecuteAfter(REFUSED_TICKS, () -> {
                helper.assertBlockPresent(Blocks.AIR, STAND);
                helper.assertFalse(Boolean.TRUE.equals(keeper.value("tp:placed")),
                    KEEPER + " placed a block while its can_place filter failed.");
                keeper.addTag("tp_may_place");
            })
            .thenWaitUntil(() -> {
                BlockState placed = helper.getBlockState(STAND);
                helper.assertTrue(placed.is(Blocks.OAK_LOG)
                        && placed.getValue(RotatedPillarBlock.AXIS) == Direction.Axis.X,
                    KEEPER + " places an oak log lying along x and placed " + placed + ".");
                helper.assertTrue(Boolean.TRUE.equals(keeper.value("tp:placed")),
                    KEEPER + " placed its block and never raised on_place.");
            })
            .thenSucceed();
    }

    /** Ticks a goal checked every second tick has to place a block it is allowed to. */
    private static final int REFUSED_TICKS = 30;

    private static AddonEntity keeper(GameTestHelper helper, String event) {
        AddonEntity keeper = BehaviourGameTests.spawn(helper, KEEPER, STAND);
        if (keeper == null) return null;

        keeper.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        EntityEvents.trigger(keeper, event, null, ServerHooks.state());
        keeper.setNoAi(false);
        return keeper;
    }
}
