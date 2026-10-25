package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.List;

/**
 * The goals that carry items between containers.
 *
 * `tp:porter` carries out of barrels into chests, one group per field of
 * `minecraft:behavior.transport_items`, each searching six blocks across so a neighbouring test's
 * containers stay out of reach.
 */
public final class ItemGoalGameTests {
    private static final String PORTER = "tp:porter";

    private static final BlockPos CORNER = new BlockPos(1, 0, 1);

    /** A transport group carries no more than five at a time, out of a barrel and into a chest. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aPorterCarriesFromItsSourcesToItsDestinationsAFewAtATime(GameTestHelper helper) {
        Container barrel = container(helper, Blocks.BARREL, new BlockPos(3, 0, 1));
        Container chest = container(helper, Blocks.CHEST, new BlockPos(5, 0, 4));
        barrel.setItem(0, new ItemStack(Items.COBBLESTONE, 20));
        if (porter(helper, "tp:porter_make_carrying", CORNER) == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            int carried = count(chest, Items.COBBLESTONE);
            helper.assertTrue(carried > 0, PORTER + " has put nothing in the chest.");
            helper.assertTrue(carried == 5, PORTER + " carries five at a time and put " + carried + " in the chest.");
        });
    }

    /** `with_matching` passes a nearer empty chest for one already holding what is carried. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void aPorterPlacingWithMatchingPassesAnEmptyChest(GameTestHelper helper) {
        container(helper, Blocks.BARREL, new BlockPos(2, 0, 1)).setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        Container empty = container(helper, Blocks.CHEST, new BlockPos(1, 0, 4));
        Container matching = container(helper, Blocks.CHEST, new BlockPos(6, 0, 6));
        matching.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
        if (porter(helper, "tp:porter_make_choosy", CORNER) == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            helper.assertTrue(empty.isEmpty(), PORTER + " put cobblestone in a chest holding none.");
            helper.assertTrue(count(matching, Items.COBBLESTONE) > 1,
                PORTER + " has not put cobblestone beside the cobblestone already there.");
        });
    }

    /** `nearest` takes from the nearest barrel first, out of six. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aPorterSearchingNearestTakesFromTheNearestBarrel(GameTestHelper helper) {
        container(helper, Blocks.BARREL, new BlockPos(2, 0, 2)).setItem(0, new ItemStack(Items.DIRT));
        for (BlockPos far : List.of(new BlockPos(6, 0, 6), new BlockPos(6, 0, 1), new BlockPos(1, 0, 6),
            new BlockPos(6, 0, 3), new BlockPos(3, 0, 6))) {
            container(helper, Blocks.BARREL, far).setItem(0, new ItemStack(Items.STONE));
        }
        AddonEntity porter = porter(helper, "tp:porter_make_nearest", CORNER);
        if (porter == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            ItemStack held = porter.getMainHandItem();
            helper.assertFalse(held.isEmpty(), PORTER + " has taken nothing.");
            helper.assertTrue(held.is(Items.DIRT), PORTER + " took " + held + " before the nearest barrel's dirt.");
        });
    }

    /** A barrel four blocks off is outside a search distance of two. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aPorterLooksNoFurtherThanItsSearchDistance(GameTestHelper helper) {
        container(helper, Blocks.BARREL, new BlockPos(5, 0, 1)).setItem(0, new ItemStack(Items.DIRT));
        AddonEntity porter = porter(helper, "tp:porter_make_short", CORNER);
        if (porter == null) {
            helper.succeed();
            return;
        }

        helper.runAfterDelay(80, () -> {
            helper.assertTrue(porter.getMainHandItem().isEmpty(), PORTER + " fetched from a barrel four blocks off.");
            helper.succeed();
        });
    }

    /**
     * After two empty barrels the porter rests for thirty seconds and forgets them, so it never gets
     * past them to the full one behind.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void aPorterRestsOnceItHasVisitedItsMostContainers(GameTestHelper helper) {
        for (BlockPos empty : List.of(new BlockPos(2, 0, 2), new BlockPos(1, 0, 3), new BlockPos(3, 0, 1))) {
            container(helper, Blocks.BARREL, empty);
        }
        Container full = container(helper, Blocks.BARREL, new BlockPos(6, 0, 6));
        full.setItem(0, new ItemStack(Items.DIRT));
        AddonEntity porter = porter(helper, "tp:porter_make_forgetful", CORNER);
        if (porter == null) {
            helper.succeed();
            return;
        }

        helper.runAfterDelay(260, () -> {
            helper.assertTrue(porter.getMainHandItem().isEmpty() && !full.isEmpty(),
                PORTER + " went past two empty barrels to the full one.");
            helper.succeed();
        });
    }

    /** With nothing to fetch from, the porter looks again after its one second idle cooldown. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void aPorterWithNothingToFetchLooksAgainAfterItsIdleCooldown(GameTestHelper helper) {
        AddonEntity porter = porter(helper, "tp:porter_make_restless", CORNER);
        if (porter == null) {
            helper.succeed();
            return;
        }

        helper.runAfterDelay(10, () ->
            container(helper, Blocks.BARREL, new BlockPos(3, 0, 1)).setItem(0, new ItemStack(Items.DIRT)));
        helper.succeedWhen(() -> helper.assertFalse(porter.getMainHandItem().isEmpty(),
            PORTER + " has not looked again for a barrel."));
    }

    /** A porter waits its three second initial cooldown before it first fetches. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 160)
    public void aPorterWaitsItsInitialCooldown(GameTestHelper helper) {
        container(helper, Blocks.BARREL, new BlockPos(2, 0, 1)).setItem(0, new ItemStack(Items.DIRT));
        AddonEntity porter = porter(helper, "tp:porter_make_late", CORNER);
        if (porter == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenExecuteAfter(40, () -> helper.assertTrue(porter.getMainHandItem().isEmpty(),
                PORTER + " fetched within two seconds of a three second initial cooldown."))
            .thenWaitUntil(() -> helper.assertFalse(porter.getMainHandItem().isEmpty(),
                PORTER + " never fetched after its initial cooldown."))
            .thenSucceed();
    }

    private static AddonEntity porter(GameTestHelper helper, String event, BlockPos where) {
        return withGroup(helper, PORTER, event, where);
    }

    private static AddonEntity withGroup(GameTestHelper helper, String identifier, String event, BlockPos where) {
        AddonEntity mob = BehaviourGameTests.spawn(helper, identifier, where);
        if (mob == null) return null;

        EntityEvents.trigger(mob, event, null, ServerHooks.state());
        mob.setNoAi(false);
        return mob;
    }

    private static Container container(GameTestHelper helper, Block block, BlockPos where) {
        helper.setBlock(where, block);
        return HopperBlockEntity.getContainerAt(helper.getLevel(), helper.absolutePos(where));
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

}
