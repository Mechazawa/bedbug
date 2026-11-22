package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

/**
 * The fields of each `entity_types` entry of `minecraft:behavior.avoid_mob_type`, which
 * `tp:shirker` carries one group each of, read back off the world.
 */
public final class ShirkerGameTests {
    private static final String SHIRKER = "tp:shirker";

    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    /** A flight runs at the speeds of the entry naming what is fled, not those of the first entry. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aFlightRunsAtTheSpeedOfTheEntryItFlees(GameTestHelper helper) {
        AddonEntity shirker = shirker(helper, "tp:shirker_make_paced", STAND);
        if (shirker == null) {
            helper.succeed();
            return;
        }
        still(helper, "cow", STAND.east(3));
        helper.succeedWhen(() -> {
            helper.assertTrue(avoiding(shirker), SHIRKER + " never fled the cow.");
            double speed = shirker.getMoveControl().getSpeedModifier();
            helper.assertTrue(speed == 1.5, SHIRKER + " fleeing a cow its second entry names at 1.5 runs at " + speed);
        });
    }

    /** Between two candidates the entry with the lower `priority` wins, though its candidate is further. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void theEntryWithTheLowerPriorityIsFledFirst(GameTestHelper helper) {
        AddonEntity shirker = shirker(helper, "tp:shirker_make_ranked", STAND);
        if (shirker == null) {
            helper.succeed();
            return;
        }
        still(helper, "pig", STAND.east(2));
        still(helper, "cow", STAND.south(4));
        helper.succeedWhen(() -> {
            helper.assertTrue(avoiding(shirker), SHIRKER + " never fled.");
            helper.assertTrue(shirker.getMoveControl().getSpeedModifier() == 1.5, SHIRKER
                + " fled the nearer pig of priority 2 over the cow of priority 1, at "
                + shirker.getMoveControl().getSpeedModifier());
        });
    }

    /** An entry's `must_see` holds where the goal states `ignore_visibility`. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void anEntryThatMustSeeIgnoresAPigBehindAWall(GameTestHelper helper) {
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        }
        AddonEntity shirker = shirker(helper, "tp:shirker_make_watching", new BlockPos(2, 0, 2));
        if (shirker == null) {
            helper.succeed();
            return;
        }
        still(helper, "pig", new BlockPos(4, 0, 2));
        helper.runAfterDelay(40, () -> {
            helper.assertFalse(avoiding(shirker), SHIRKER + " whose entry must see fled a pig behind a wall.");
            helper.succeed();
        });
    }

    /** An entry's `max_flee` ends the flight where the goal's own would not. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void anEntrysMaxFleeEndsTheFlight(GameTestHelper helper) {
        AddonEntity shirker = shirker(helper, "tp:shirker_make_short", STAND);
        if (shirker == null) {
            helper.succeed();
            return;
        }
        still(helper, "pig", STAND.east(2));
        helper.succeedWhen(() -> helper.assertTrue(shirker.declaresNow("minecraft:is_chested"),
            SHIRKER + " with an entry max_flee of 3 under a goal max_flee of 30 never escaped."));
    }

    /** After a flight an entry's `cooldown` keeps it from fleeing the same pig again. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 140)
    public void anEntrysCooldownHoldsTheNextFlight(GameTestHelper helper) {
        AddonEntity shirker = shirker(helper, "tp:shirker_make_resting", STAND);
        if (shirker == null) {
            helper.succeed();
            return;
        }
        Mob pig = still(helper, "pig", STAND.east(2));
        boolean[] fled = new boolean[1];
        long[] calmAt = {-1};
        helper.onEachTick(() -> {
            if (avoiding(shirker)) {
                if (calmAt[0] >= 0) helper.fail(SHIRKER + " fled again inside its ten second cooldown.");
                fled[0] = true;
            } else if (fled[0] && calmAt[0] < 0) {
                calmAt[0] = helper.getTick();
                Platform.placeAt(pig, shirker.getX() + 2, shirker.getY(), shirker.getZ(), 0f, 0f);
            }
        });
        helper.succeedWhen(() -> helper.assertTrue(calmAt[0] >= 0 && helper.getTick() - calmAt[0] >= 40,
            SHIRKER + " has not been calm for two seconds yet."));
    }

    /** Under `check_if_outnumbered` one pig frightens nothing beside two of its own kind; three do. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void anOutnumberedEntryFleesOnlyAMajority(GameTestHelper helper) {
        AddonEntity first = shirker(helper, "tp:shirker_make_counting", STAND);
        if (first == null) {
            helper.succeed();
            return;
        }
        AddonEntity second = shirker(helper, "tp:shirker_make_counting", STAND.south(1));
        still(helper, "pig", STAND.east(3));
        helper.runAfterDelay(30, () -> {
            helper.assertFalse(avoiding(first) || avoiding(second),
                SHIRKER + " fled one pig beside another of its own kind.");
            still(helper, "pig", STAND.east(3).south(1));
            still(helper, "pig", STAND.east(3).south(2));
            helper.succeedWhen(() -> helper.assertTrue(avoiding(first) || avoiding(second),
                SHIRKER + " did not flee three pigs beside two of its own kind."));
        });
    }

    /** An entry's `max_height` leaves out a pig standing higher than it allows. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void anEntrysMaxHeightLeavesOutAPigAbove(GameTestHelper helper) {
        helper.setBlock(STAND.east(2), Blocks.STONE);
        helper.setBlock(STAND.east(2).above(), Blocks.STONE);
        AddonEntity shirker = shirker(helper, "tp:shirker_make_level", STAND);
        if (shirker == null) {
            helper.succeed();
            return;
        }
        still(helper, "pig", STAND.east(2).above(2));
        helper.runAfterDelay(40, () -> {
            helper.assertFalse(avoiding(shirker), SHIRKER + " with a max_height of 1 fled a pig two blocks up.");
            helper.succeed();
        });
    }

    /** Under `reevaluate_description` the flight ends once what is fled stops answering the filters. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void aReevaluatedEntryLetsGoOfWhatNoLongerAnswers(GameTestHelper helper) {
        AddonEntity shirker = shirker(helper, "tp:shirker_make_fickle", STAND);
        if (shirker == null) {
            helper.succeed();
            return;
        }
        Mob pig = still(helper, "pig", STAND.east(2));
        pig.addTag("tp_scary");
        long[] untaggedAt = {-1};
        helper.onEachTick(() -> {
            if (untaggedAt[0] < 0 && avoiding(shirker)) {
                pig.removeTag("tp_scary");
                untaggedAt[0] = helper.getTick();
            } else if (untaggedAt[0] >= 0 && helper.getTick() - untaggedAt[0] > 6 && avoiding(shirker)) {
                helper.fail(SHIRKER + " still flees a pig that lost its tag.");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(untaggedAt[0] >= 0, SHIRKER + " never fled the tagged pig.");
            helper.assertTrue(helper.getTick() - untaggedAt[0] >= 8, SHIRKER + " lost the tag only just now.");
        });
    }

    private static boolean avoiding(AddonEntity shirker) {
        return shirker.value("is_avoiding_mobs") == Boolean.TRUE;
    }

    private static Mob still(GameTestHelper helper, String kind, BlockPos where) {
        Mob mob = (Mob) Platform.createMob(BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", kind)).orElseThrow(), helper.getLevel());
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(mob, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setNoAi(true);
        helper.getLevel().addFreshEntity(mob);
        return mob;
    }

    private static AddonEntity shirker(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity shirker = BehaviourGameTests.spawn(helper, SHIRKER, where);
        if (shirker == null) return null;

        EntityEvents.trigger(shirker, event, null, ServerHooks.state());
        shirker.setNoAi(false);
        return shirker;
    }
}
