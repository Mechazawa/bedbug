package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The goals that carry items between containers and leave them for somebody.
 *
 * `tp:porter` carries out of barrels into chests, one group per field of
 * `minecraft:behavior.transport_items`, each searching six blocks across so a neighbouring test's
 * containers stay out of reach. `tp:offerer` leaves `tp:gift`'s three feathers for a pig, one group
 * per field of `minecraft:behavior.drop_item_for`.
 */
public final class ItemGoalGameTests {
    private static final String PORTER = "tp:porter";
    private static final String OFFERER = "tp:offerer";

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

    /**
     * The offerer finds a pig three blocks off inside its `target_range` and leaves it the gift
     * table's feathers, which cannot be picked up for `seconds_before_pickup`.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anOffererLeavesItsTablesGiftThatWaitsBeforePickup(GameTestHelper helper) {
        pig(helper, new BlockPos(4, 0, 4));
        if (offerer(helper, "tp:offerer_make_gifting", new BlockPos(1, 0, 4)) == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            List<ItemEntity> gifts = feathers(helper);
            helper.assertFalse(gifts.isEmpty(), OFFERER + " has left no feathers.");
            helper.assertTrue(gifts.get(0).getItem().getCount() == 3,
                OFFERER + " left " + gifts.get(0).getItem() + " where its table gives three feathers.");
            helper.assertTrue(gifts.get(0).hasPickUpDelay(), OFFERER + "'s gift can be picked up at once.");
        });
    }

    /** At a drop chance of nothing the attempt is raised and nothing is left. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anOffererThatNeverDropsStillRaisesItsAttempt(GameTestHelper helper) {
        pig(helper, new BlockPos(4, 0, 4));
        AddonEntity offerer = offerer(helper, "tp:offerer_make_reluctant", new BlockPos(2, 0, 4));
        if (offerer == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(Boolean.TRUE.equals(offerer.value("tp:attempted")),
                OFFERER + " never raised on_drop_attempt."))
            .thenExecuteAfter(5, () -> helper.assertTrue(feathers(helper).isEmpty(),
                OFFERER + " dropped a gift at a drop chance of nothing."))
            .thenSucceed();
    }

    /** A four second cooldown leaves one gift in three seconds beside a pig that stays. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anOffererWaitsItsCooldownBetweenGifts(GameTestHelper helper) {
        pig(helper, new BlockPos(4, 0, 4));
        if (offerer(helper, "tp:offerer_make_patient", new BlockPos(3, 0, 4)) == null) {
            helper.succeed();
            return;
        }

        Set<UUID> seen = new HashSet<>();
        helper.onEachTick(() -> feathers(helper).forEach(gift -> seen.add(gift.getUUID())));
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(seen.size() == 1, OFFERER + " left " + seen.size() + " gifts in three seconds.");
            helper.succeed();
        });
    }

    /**
     * An offerer kept to the afternoon leaves nothing at any time a test here sets: dawn, the
     * morning after it and midnight.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anOffererLeavesNothingOutsideItsTimeOfDay(GameTestHelper helper) {
        pig(helper, new BlockPos(4, 0, 4));
        AddonEntity offerer = offerer(helper, "tp:offerer_make_afternoon", new BlockPos(3, 0, 4));
        if (offerer == null) {
            helper.succeed();
            return;
        }

        helper.runAfterDelay(60, () -> {
            long sinceDawn = Math.floorMod(Platform.dayTime(helper.getLevel()), 24000L);
            helper.assertTrue(sinceDawn < AFTERNOON_FROM || sinceDawn > AFTERNOON_TO,
                "The clock stands in the offerer's afternoon at " + sinceDawn + ".");
            helper.assertTrue(feathers(helper).isEmpty() && !Boolean.TRUE.equals(offerer.value("tp:attempted")),
                OFFERER + " left a gift outside its time of day.");
            helper.succeed();
        });
    }

    /** Noon is 0 on the goal's clock and 6000 on Java's, so 0.1 to 0.2 is 8400 to 10800. */
    private static final long AFTERNOON_FROM = 8400;
    private static final long AFTERNOON_TO = 10800;

    /**
     * Searching four blocks round the pig for the spot four blocks from it, the offerer leaves its
     * gift from there rather than at the pig's feet.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
    public void anOffererStandsItsOfferingDistanceFromThePig(GameTestHelper helper) {
        Mob pig = pig(helper, new BlockPos(4, 0, 4));
        AddonEntity offerer = offerer(helper, "tp:offerer_make_apart", new BlockPos(2, 0, 4));
        if (offerer == null) {
            helper.succeed();
            return;
        }

        double[] apart = {-1};
        helper.onEachTick(() -> {
            if (apart[0] < 0 && !feathers(helper).isEmpty()) {
                apart[0] = Math.hypot(offerer.getX() - pig.getX(), offerer.getZ() - pig.getZ());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(apart[0] >= 0, OFFERER + " has left no gift.");
            helper.assertTrue(apart[0] >= 3, OFFERER + " left its gift " + apart[0] + " blocks from the pig.");
        });
    }

    /** A pig floating three blocks up has ground to stand on only within a search height of three. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anOffererSearchesItsHeightForGroundBelowThePig(GameTestHelper helper) {
        pig(helper, new BlockPos(4, 3, 4)).setNoGravity(true);
        if (offerer(helper, "tp:offerer_make_reaching", new BlockPos(2, 0, 4)) == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> helper.assertFalse(feathers(helper).isEmpty(),
            OFFERER + " found nowhere to stand below the pig."));
    }

    /**
     * Looking no higher than its own feet, the offerer throws its gift no higher than a level throw
     * goes, though the pig floats five blocks up.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anOffererLooksNoHigherThanItsMaximumHeadHeight(GameTestHelper helper) {
        pig(helper, new BlockPos(4, 5, 4)).setNoGravity(true);
        if (offerer(helper, "tp:offerer_make_downcast", new BlockPos(2, 0, 4)) == null) {
            helper.succeed();
            return;
        }

        double[] rise = {Double.NaN};
        helper.onEachTick(() -> feathers(helper).stream().findFirst()
            .filter(gift -> Double.isNaN(rise[0])).ifPresent(gift -> rise[0] = gift.getDeltaMovement().y));
        helper.succeedWhen(() -> {
            helper.assertFalse(Double.isNaN(rise[0]), OFFERER + " has left no gift.");
            helper.assertTrue(rise[0] < LEVEL_THROW,
                OFFERER + " threw its gift up at " + rise[0] + " towards the pig.");
        });
    }

    /** The rise of a gift thrown straight across, which a throw up at the pig exceeds. */
    private static final double LEVEL_THROW = 0.25;

    /** A teleport lands three blocks over the spot, which the offerer then falls from. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anOffererTeleportsToItsSpotAtItsOffset(GameTestHelper helper) {
        pig(helper, new BlockPos(5, 0, 4));
        AddonEntity offerer = offerer(helper, "tp:offerer_make_leaping", new BlockPos(1, 0, 4));
        if (offerer == null) {
            helper.succeed();
            return;
        }

        double floor = offerer.getY();
        double[] highest = {floor};
        helper.onEachTick(() -> highest[0] = Math.max(highest[0], offerer.getY()));
        helper.succeedWhen(() -> {
            helper.assertTrue(highest[0] - floor > 2.5,
                OFFERER + " rose " + (highest[0] - floor) + " blocks where its teleport offset is three.");
            helper.assertFalse(feathers(helper).isEmpty(), OFFERER + " has left no gift after landing.");
        });
    }

    /** An offerer that cannot walk and may teleport only twenty blocks off stays where it is. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anOffererTeleportsOnlyPastItsMinimumDistance(GameTestHelper helper) {
        pig(helper, new BlockPos(5, 0, 4));
        AddonEntity offerer = offerer(helper, "tp:offerer_make_grounded", new BlockPos(1, 0, 4));
        if (offerer == null) {
            helper.succeed();
            return;
        }

        offerer.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        double startX = offerer.getX();
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(Math.abs(offerer.getX() - startX) < 0.5,
                OFFERER + " moved " + (offerer.getX() - startX) + " blocks.");
            helper.assertTrue(feathers(helper).isEmpty(), OFFERER + " left a gift from three blocks off.");
            helper.succeed();
        });
    }

    private static AddonEntity porter(GameTestHelper helper, String event, BlockPos where) {
        return withGroup(helper, PORTER, event, where);
    }

    private static AddonEntity offerer(GameTestHelper helper, String event, BlockPos where) {
        return withGroup(helper, OFFERER, event, where);
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

    private static List<ItemEntity> feathers(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(2),
            item -> item.getItem().is(Items.FEATHER));
    }

    /** A pig with no goals of its own where the test puts it. */
    private static Mob pig(GameTestHelper helper, BlockPos where) {
        ServerLevel level = helper.getLevel();
        Entity created = Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "pig")).orElseThrow(), level);
        Mob pig = (Mob) created;
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        pig.setNoAi(true);
        level.addFreshEntity(pig);
        return pig;
    }
}
