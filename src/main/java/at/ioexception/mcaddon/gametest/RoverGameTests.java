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

/**
 * The goal fields `tp:rover` carries one group each of, read back off the world.
 */
public final class RoverGameTests {
    private static final String ROVER = "tp:rover";

    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    /** How far the pig stands from the mount, along X. */
    private static final int APART = 8;

    /**
     * `minecraft:behavior.mount_pathing` walks a mount to its rider's target, at its own speed, though
     * the rider has no goal of its own that walks anywhere.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aMountWalksToItsRidersTarget(GameTestHelper helper) {
        chase(helper, "tp:rover_make_pathing", null, 2);
    }

    /** `target_dist` holds the mount that far from the target. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aMountKeepsItsTargetDistance(GameTestHelper helper) {
        AddonEntity mount = rover(helper, "tp:rover_make_pathing_distant", STAND);
        if (mount == null) {
            helper.succeed();
            return;
        }
        Mob pig = pig(helper);
        seat(helper, mount, null, pig);
        helper.runAfterDelay(80, () -> {
            double apart = mount.distanceTo(pig);
            helper.assertTrue(apart > 2 && apart < 4.5,
                ROVER + " stating a target_dist of 3 stopped " + apart + " blocks from its rider's target.");
            helper.succeed();
        });
    }

    private static void chase(GameTestHelper helper, String mountEvent, String riderEvent, double within) {
        AddonEntity mount = rover(helper, mountEvent, STAND);
        if (mount == null) {
            helper.succeed();
            return;
        }
        Mob pig = pig(helper);
        seat(helper, mount, riderEvent, pig);
        helper.succeedWhen(() -> helper.assertTrue(mount.distanceTo(pig) < within,
            ROVER + " carrying a rider whose target is " + APART + " blocks off is still "
                + mount.distanceTo(pig) + " from it."));
    }

    private static void seat(GameTestHelper helper, AddonEntity mount, String riderEvent, Mob target) {
        AddonEntity rider = rover(helper, riderEvent, STAND);
        helper.assertTrue(rider.startRiding(mount, true, false), ROVER + " would not carry a rider.");
        rider.setTarget(target);
    }

    private static Mob pig(GameTestHelper helper) {
        Mob pig = (Mob) Platform.createMob(BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", "pig")).orElseThrow(), helper.getLevel());
        BlockPos at = helper.absolutePos(STAND.east(APART));
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        return pig;
    }

    private static AddonEntity rover(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity rover = BehaviourGameTests.spawn(helper, ROVER, where);
        if (rover == null) return null;

        if (event != null) EntityEvents.trigger(rover, event, null, ServerHooks.state());
        rover.setNoAi(false);
        return rover;
    }
}
