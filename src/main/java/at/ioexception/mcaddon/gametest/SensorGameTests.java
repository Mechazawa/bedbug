package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

/**
 * The components that answer something the world did to an entity, read through the events they
 * raise and the riders they let go of.
 *
 * `tp:cart` writes `minecraft:rail_sensor` with an event for each way the rail changes. The other two
 * carts each state the one field their test asks about.
 */
public final class SensorGameTests {
    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    /** A few ticks after the entity is placed, which is when the rail under it has been read. */
    private static final int SETTLED = 6;

    /** A powered rail on stone, with a block of redstone beside it that is there while it should have power. */
    private static void powered(GameTestHelper helper, BlockPos at, boolean powered) {
        helper.setBlock(at.below(), Blocks.STONE);
        helper.setBlock(at.west(), powered ? Blocks.REDSTONE_BLOCK : Blocks.AIR);
        helper.setBlock(at, Blocks.POWERED_RAIL);
    }

    /** A rail sensor raises `on_activate` for a powered rail and `on_deactivate` once it loses power. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aRailSensorRaisesAnEventForEachChangeOfPower(GameTestHelper helper) {
        AddonEntity cart = BehaviourGameTests.spawn(helper, "tp:cart", STAND);
        if (cart == null) {
            helper.succeed();
            return;
        }

        powered(helper, STAND, true);
        helper.runAtTickTime(SETTLED, () -> {
            helper.assertTrue(Boolean.TRUE.equals(cart.value("tp:activated")), "The cart raised no on_activate.");
            helper.assertFalse(Boolean.TRUE.equals(cart.value("tp:deactivated")),
                "The cart raised on_deactivate on a powered rail.");
            powered(helper, STAND, false);
        });
        helper.runAtTickTime(2 * SETTLED, () -> {
            helper.assertTrue(Boolean.TRUE.equals(cart.value("tp:deactivated")),
                "The cart raised no on_deactivate when the rail lost power.");
            helper.succeed();
        });
    }

    /** A rider is put off at an activated rail unless `eject_on_activate` says false. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aRailSensorEjectsItsRidersOnlyWhereItsPackSaysSo(GameTestHelper helper) {
        AddonEntity ejecting = BehaviourGameTests.spawn(helper, "tp:cart", STAND);
        AddonEntity keeping = BehaviourGameTests.spawn(helper, "tp:cart_keeping", STAND.east(3));
        if (ejecting == null || keeping == null) {
            helper.succeed();
            return;
        }

        Mob first = pigOn(helper, ejecting);
        Mob second = pigOn(helper, keeping);
        powered(helper, STAND, true);
        powered(helper, STAND.east(3), true);
        helper.runAtTickTime(SETTLED, () -> {
            helper.assertFalse(first.isPassenger(), "The default cart kept its rider on an activated rail.");
            helper.assertTrue(second.isPassenger(), "The cart that sets eject_on_activate false put its rider off.");
            helper.succeed();
        });
    }

    /** With `check_block_types` a cart off every rail counts as deactivated, and without it does not. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aRailSensorCheckingBlockTypesCountsNoRailAsDeactivated(GameTestHelper helper) {
        AddonEntity checking = BehaviourGameTests.spawn(helper, "tp:cart_checking", STAND);
        AddonEntity plain = BehaviourGameTests.spawn(helper, "tp:cart", STAND.east(3));
        if (checking == null || plain == null) {
            helper.succeed();
            return;
        }

        helper.runAtTickTime(SETTLED, () -> {
            helper.assertTrue(Boolean.TRUE.equals(checking.value("tp:deactivated")),
                "The cart checking block types raised no on_deactivate off every rail.");
            helper.assertFalse(Boolean.TRUE.equals(plain.value("tp:deactivated")),
                "The cart that leaves check_block_types off raised on_deactivate off every rail.");
            helper.succeed();
        });
    }

    private static Mob pigOn(GameTestHelper helper, Entity cart) {
        Mob pig = (Mob) Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "pig")).orElseThrow(),
            helper.getLevel());
        Platform.placeAt(pig, cart.getX(), cart.getY(), cart.getZ(), 0f, 0f);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        helper.assertTrue(pig.startRiding(cart), "The pig could not mount " + cart.getType() + ".");
        return pig;
    }
}
