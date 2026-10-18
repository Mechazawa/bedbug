package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * `minecraft:looked_at`, read through a mock player who is turned towards `tp:gazed` or away from it.
 *
 * Each group of `tp:gazed` states the one field its test asks about, over a wide field of view where
 * the test is not about the angle. The player is placed beside the entity and aimed at its eyes
 * before any tick runs, plus a number of degrees of yaw where the test asks about the angle.
 */
public final class LookedAtGameTests {
    private static final String GAZED = "tp:gazed";
    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    /** Past the second the timed group asks for and the tick that notices it. */
    private static final int NOTICED = 40;

    private static AddonEntity gazed(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity gazed = BehaviourGameTests.spawn(helper, GAZED, where);
        if (gazed == null) return null;

        EntityEvents.trigger(gazed, event, null, ServerHooks.state());
        gazed.setNoAi(false);
        return gazed;
    }

    /** A player `distance` blocks east of the entity, aimed at its eyes and then off by `yawOffset` degrees. */
    private static Player observer(GameTestHelper helper, AddonEntity gazed, double distance, float yawOffset) {
        Player player = helper.makeMockServerPlayerInLevel();
        Platform.placeAt(player, gazed.getX() + distance, gazed.getY(), gazed.getZ(), 0f, 0f);
        aim(player, gazed.getEyePosition(), yawOffset);
        return player;
    }

    /** A pig that stays put `distance` blocks east of the entity, which no game mode keeps from being a target. */
    private static Mob watcher(GameTestHelper helper, AddonEntity gazed, double distance) {
        Mob pig = (Mob) Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "pig")).orElseThrow(),
            helper.getLevel());
        Platform.placeAt(pig, gazed.getX() + distance, gazed.getY(), gazed.getZ(), 0f, 0f);
        pig.setNoAi(true);
        helper.getLevel().addFreshEntity(pig);
        aim(pig, gazed.getEyePosition(), 0f);
        return pig;
    }

    private static void aim(LivingEntity player, Vec3 at, float yawOffset) {
        Vec3 line = at.subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-line.x, line.z)) + yawOffset;
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot((float) -Math.toDegrees(Math.atan2(line.y, Math.hypot(line.x, line.z))));
    }

    private static boolean seen(AddonEntity gazed) {
        return Boolean.TRUE.equals(gazed.value("tp:seen"));
    }

    /**
     * A stare is noticed only after `min_looked_at_duration`, repeats only after `looked_at_cooldown`
     * and raises `not_looked_at_event` once it breaks.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aStareIsNoticedAfterItsDurationAndAgainAfterItsCooldown(GameTestHelper helper) {
        AddonEntity gazed = gazed(helper, "tp:gaze_make_timed", STAND);
        if (gazed == null) {
            helper.succeed();
            return;
        }

        observer(helper, gazed, 4, 0f);
        long begun = helper.getLevel().getGameTime();
        long[] noticed = {-1, -1};
        helper.onEachTick(() -> {
            if (!seen(gazed)) return;

            noticed[noticed[0] < 0 ? 0 : 1] = helper.getLevel().getGameTime() - begun;
            gazed.setValue("tp:seen", false);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(noticed[1] >= 0, GAZED + " noticed the stare " + (noticed[0] < 0 ? 0 : 1) + " times.");
            helper.assertTrue(noticed[0] >= 20 - 2,
                GAZED + " noticed a stare of under a second at tick " + noticed[0] + ".");
            helper.assertTrue(noticed[0] <= NOTICED, GAZED + " noticed the stare late, at tick " + noticed[0] + ".");
            helper.assertTrue(noticed[1] - noticed[0] >= 40 - 2,
                GAZED + " noticed the stare again after " + (noticed[1] - noticed[0]) + " ticks of a 2 s cooldown.");
        });
    }

    /** Looking away raises `not_looked_at_event` once the gaze that was noticed breaks. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 140)
    public void aBrokenGazeRaisesTheNotLookedAtEvent(GameTestHelper helper) {
        AddonEntity gazed = gazed(helper, "tp:gaze_make_timed", STAND);
        if (gazed == null) {
            helper.succeed();
            return;
        }

        Player player = observer(helper, gazed, 4, 0f);
        helper.runAtTickTime(NOTICED, () -> {
            helper.assertFalse(Boolean.TRUE.equals(gazed.value("tp:lost")), GAZED + " lost a gaze that held.");
            aim(player, gazed.getEyePosition(), 180f);
        });
        helper.runAtTickTime(NOTICED + 60, () -> {
            helper.assertTrue(Boolean.TRUE.equals(gazed.value("tp:lost")),
                GAZED + " raised no not_looked_at_event once the player looked away.");
            helper.succeed();
        });
    }

    /** The field of view is a width at one block, so it narrows with distance unless the pack says it does not. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aFieldOfViewNarrowsWithDistanceUnlessTheFlagIsOff(GameTestHelper helper) {
        AddonEntity scaled = gazed(helper, "tp:gaze_make_scaled", STAND);
        AddonEntity flat = gazed(helper, "tp:gaze_make_flat", STAND.south(5));
        if (scaled == null || flat == null) {
            helper.succeed();
            return;
        }

        observer(helper, scaled, 6, 5f);
        observer(helper, flat, 6, 5f);
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(seen(flat), GAZED + " ignored a stare five degrees off inside an unscaled field of 20.");
            helper.assertFalse(seen(scaled),
                GAZED + " saw a stare five degrees off at six blocks inside a scaled field of 20.");
            helper.succeed();
        });
    }

    /** Only a player inside `search_radius` is looked for. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aStareFromBeyondTheSearchRadiusIsNotNoticed(GameTestHelper helper) {
        AddonEntity near = gazed(helper, "tp:gaze_make_near", STAND);
        AddonEntity far = gazed(helper, "tp:gaze_make_near", STAND.south(5));
        if (near == null || far == null) {
            helper.succeed();
            return;
        }

        observer(helper, near, 3, 0f);
        observer(helper, far, 6, 0f);
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(seen(near), GAZED + " missed a stare from three blocks inside a radius of four.");
            helper.assertFalse(seen(far), GAZED + " noticed a stare from six blocks beyond a radius of four.");
            helper.succeed();
        });
    }

    /** `set_target` makes the first looker a target unless it says never. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aStareMakesTheLookerATargetUnlessSetTargetIsNever(GameTestHelper helper) {
        AddonEntity targeting = gazed(helper, "tp:gaze_make_targeting", STAND);
        AddonEntity passive = gazed(helper, "tp:gaze_make_passive", STAND.south(5));
        if (targeting == null || passive == null) {
            helper.succeed();
            return;
        }

        Mob looker = watcher(helper, targeting, 4);
        watcher(helper, passive, 4);
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(targeting.getTarget() == looker, GAZED + " did not take its looker as a target.");
            helper.assertTrue(passive.getTarget() == null && seen(passive),
                GAZED + " took a target though set_target is never, or noticed nothing.");
            helper.succeed();
        });
    }

    /** A line to the named location that a block cuts is not a look at it. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aBlockCuttingTheLineToALocationHidesOnlyThatLocation(GameTestHelper helper) {
        AddonEntity head = gazed(helper, "tp:gaze_make_head", STAND);
        AddonEntity feet = gazed(helper, "tp:gaze_make_feet", STAND.south(5));
        if (head == null || feet == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(STAND.east(1).above(1), Blocks.STONE);
        helper.setBlock(STAND.south(5).east(1).above(1), Blocks.STONE);
        observer(helper, head, 6, 0f);
        Player lookingAtFeet = observer(helper, feet, 6, 0f);
        aim(lookingAtFeet, feet.position(), 0f);
        helper.runAtTickTime(20, () -> {
            helper.assertFalse(seen(head), GAZED + " saw through a block at its head.");
            helper.assertTrue(seen(feet), GAZED + " was hidden at its feet by a block above them.");
            helper.succeed();
        });
    }

    /** A looker the filters refuse is not noticed. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aLookerTheFiltersRefuseIsNotNoticed(GameTestHelper helper) {
        AddonEntity filtered = gazed(helper, "tp:gaze_make_filtered", STAND);
        if (filtered == null) {
            helper.succeed();
            return;
        }

        observer(helper, filtered, 4, 0f);
        helper.runAtTickTime(20, () -> {
            helper.assertFalse(seen(filtered), GAZED + " noticed a player though its filter asks for a pig.");
            helper.succeed();
        });
    }
}
