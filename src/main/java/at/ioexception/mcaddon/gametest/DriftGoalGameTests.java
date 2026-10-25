package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The goals that move an entity through the air and the water, read back through where they send it.
 *
 * `tp:drifter` is a plain mob whose groups each carry one of those goals with the field a test here
 * asks about. Most tests hold its feet and read the spot its navigator was sent to, so what is
 * measured is the choice the goal made rather than how far the entity got.
 */
public final class DriftGoalGameTests {
    private static final String DRIFTER = "tp:drifter";

    private static final BlockPos MIDDLE = new BlockPos(3, 3, 3);

    /** Long enough for a goal that floats off at every chance to have picked many spots. */
    private static final int PICK_TICKS = 100;

    /**
     * `use_home_position_restriction` keeps every spot a float picks inside the home the entity was
     * given, two blocks round where it stood.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = PICK_TICKS + 20)
    public void aFloatKeepsInsideItsHome(GameTestHelper helper) {
        AddonEntity drifter = held(helper, "homebound", MIDDLE);
        if (drifter == null) {
            helper.succeed();
            return;
        }

        Set<BlockPos> picked = headings(helper, drifter);
        helper.runAfterDelay(PICK_TICKS, () -> {
            helper.assertFalse(picked.isEmpty(), DRIFTER + " never floated off.");
            picked.forEach(spot -> helper.assertTrue(spot.distSqr(MIDDLE) < HOME_RADIUS * HOME_RADIUS,
                DRIFTER + " keeps to its home and floated for " + spot + "."));
            helper.succeed();
        });
    }

    /** With `use_home_position_restriction` false a float picks spots outside the home as well. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = PICK_TICKS + 20)
    public void aFloatToldToIgnoreItsHomeLeavesIt(GameTestHelper helper) {
        AddonEntity drifter = held(helper, "roaming", MIDDLE);
        if (drifter == null) {
            helper.succeed();
            return;
        }

        Set<BlockPos> picked = headings(helper, drifter);
        helper.runAfterDelay(PICK_TICKS, () -> {
            helper.assertTrue(picked.stream().anyMatch(spot -> spot.distSqr(MIDDLE) >= HOME_RADIUS * HOME_RADIUS),
                DRIFTER + " ignores its home and only floated for " + picked + ".");
            helper.succeed();
        });
    }

    private static final int HOME_RADIUS = 2;

    /** `must_reach` floats only to spots inside the sealed glass cell the entity is in. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = PICK_TICKS + 20)
    public void aFloatThatMustReachStaysInsideASealedCell(GameTestHelper helper) {
        BlockPos.betweenClosed(MIDDLE.offset(-2, -2, -2), MIDDLE.offset(2, 2, 2))
            .forEach(at -> helper.setBlock(at, Blocks.GLASS));
        BlockPos.betweenClosed(MIDDLE.offset(-1, -1, -1), MIDDLE.offset(1, 1, 1))
            .forEach(at -> helper.setBlock(at, Blocks.AIR));
        AddonEntity drifter = held(helper, "reaching", MIDDLE);
        if (drifter == null) {
            helper.succeed();
            return;
        }

        Set<BlockPos> picked = headings(helper, drifter);
        helper.runAfterDelay(PICK_TICKS, () -> {
            helper.assertFalse(picked.isEmpty(), DRIFTER + " never floated off.");
            picked.forEach(spot -> helper.assertTrue(insideTheCell(spot),
                DRIFTER + " floats only where it can reach and floated for " + spot + "."));
            helper.succeed();
        });
    }

    private static boolean insideTheCell(BlockPos spot) {
        BlockPos from = spot.subtract(MIDDLE);
        return Math.abs(from.getX()) <= 1 && Math.abs(from.getY()) <= 1 && Math.abs(from.getZ()) <= 1;
    }

    /**
     * `navigate_around_surface` with a `surface_xz_dist` and `surface_y_dist` of one picks spots
     * beside the stone wall rather than out in the open.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = PICK_TICKS + 20)
    public void aFloatAroundSurfacesKeepsBesideAWall(GameTestHelper helper) {
        BlockPos.betweenClosed(new BlockPos(0, 0, -2), new BlockPos(0, 6, 9))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        BlockPos from = new BlockPos(4, 3, 4);
        AddonEntity drifter = held(helper, "hugging", from);
        if (drifter == null) {
            helper.succeed();
            return;
        }

        Set<BlockPos> picked = headings(helper, drifter);
        helper.runAfterDelay(PICK_TICKS, () -> {
            helper.assertFalse(picked.isEmpty(), DRIFTER + " never floated off.");
            picked.forEach(spot -> helper.assertTrue(besideASurface(helper, spot),
                DRIFTER + " floats beside a surface and floated for " + spot + ", with none beside it."));
            helper.succeed();
        });
    }

    private static boolean besideASurface(GameTestHelper helper, BlockPos spot) {
        for (Direction direction : Direction.values()) {
            var state = helper.getBlockState(spot.relative(direction));
            if (!state.isAir() && state.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /**
     * Without `allow_navigating_through_liquids` a float never picks a spot it would reach through
     * the curtain of water to its west, nor one in the water.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = PICK_TICKS + 20)
    public void aFloatKeepsOutOfLiquids(GameTestHelper helper) {
        BlockPos.betweenClosed(new BlockPos(1, -1, -3), new BlockPos(3, 3, 11))
            .forEach(at -> helper.setBlock(at, Blocks.GLASS));
        BlockPos.betweenClosed(new BlockPos(2, 0, -2), new BlockPos(2, 3, 10))
            .forEach(at -> helper.setBlock(at, Blocks.WATER));
        AddonEntity drifter = held(helper, "dry", new BlockPos(5, 1, 4));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        Set<BlockPos> picked = headings(helper, drifter);
        helper.runAfterDelay(PICK_TICKS, () -> {
            helper.assertFalse(picked.isEmpty(), DRIFTER + " never floated off.");
            picked.forEach(spot -> helper.assertTrue(spot.getX() >= CURTAIN,
                DRIFTER + " floats through no liquid and floated for " + spot + ", past the water."));
            helper.succeed();
        });
    }

    private static final int CURTAIN = 3;

    /**
     * `additional_collision_buffer` keeps a block of room round the entity at the spot it picks,
     * which between a floor and a ceiling four blocks apart leaves only the middle height.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = PICK_TICKS + 20)
    public void aFloatWithACollisionBufferKeepsClearOfFloorAndCeiling(GameTestHelper helper) {
        BlockPos.betweenClosed(new BlockPos(-2, 0, -2), new BlockPos(9, 0, 9))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        BlockPos.betweenClosed(new BlockPos(-2, 4, -2), new BlockPos(9, 4, 9))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        AddonEntity drifter = held(helper, "wary", new BlockPos(3, 2, 3));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        Set<BlockPos> picked = headings(helper, drifter);
        helper.runAfterDelay(PICK_TICKS, () -> {
            helper.assertFalse(picked.isEmpty(), DRIFTER + " never floated off.");
            picked.forEach(spot -> helper.assertTrue(spot.getY() == 2,
                DRIFTER + " keeps a block clear of floor and ceiling and floated for " + spot + "."));
            helper.succeed();
        });
    }

    /** `float_wander_has_move_control` false leaves the move control free while the entity floats. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = PICK_TICKS)
    public void aFloatWithoutMoveControlHoldsNoControl(GameTestHelper helper) {
        AddonEntity drifter = held(helper, "free", MIDDLE);
        if (drifter == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            helper.assertTrue(drifter.runningGoals().stream().anyMatch(goal -> goal.startsWith("FloatWanderGoal")),
                DRIFTER + " is not floating.");
            helper.assertTrue(drifter.runningControls().isEmpty(),
                DRIFTER + " floats without the move control and holds " + drifter.runningControls() + ".");
        });
    }

    /**
     * A float tempt reaches the sixteen blocks its `within_radius` says, past Java's ten, to a player
     * nine blocks across each way, and stops the six blocks short its `stop_distance` says.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = TEMPT_TICKS + 20)
    public void aFloatTemptReachesAndStopsAsFarAsItsPackSays(GameTestHelper helper) {
        AddonEntity drifter = moving(helper, "tempted", new BlockPos(0, 2, 0));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = holding(helper, drifter, TEMPTED_FROM);
        double[] nearest = {Double.MAX_VALUE};
        helper.onEachTick(() -> nearest[0] = Math.min(nearest[0], across(drifter, player)));
        helper.runAfterDelay(TEMPT_TICKS, () -> {
            helper.assertTrue(nearest[0] < JAVA_REACH,
                DRIFTER + " is tempted thirteen blocks off and came no nearer than " + nearest[0] + ".");
            helper.assertTrue(nearest[0] > STOPS_SHORT,
                DRIFTER + " stops six blocks short and came to " + nearest[0] + ".");
            helper.succeed();
        });
    }

    private static final int TEMPT_TICKS = 160;
    private static final int TEMPTED_FROM = 9;
    private static final double JAVA_REACH = 10;
    private static final double STOPS_SHORT = 4;

    /** A float tempt raises `on_tempt_end` once the player puts the snowball away. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = TEMPT_TICKS)
    public void aFloatTemptRaisesItsEndEvent(GameTestHelper helper) {
        AddonEntity drifter = moving(helper, "tempted", new BlockPos(0, 2, 0));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = holding(helper, drifter, NEAR);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(
                drifter.runningGoals().stream().anyMatch(goal -> goal.startsWith("TemptedGoal")),
                DRIFTER + " is not tempted by a snowball " + NEAR + " blocks off each way."))
            .thenExecute(() -> player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
            .thenWaitUntil(() -> helper.assertTrue(Boolean.TRUE.equals(drifter.value("tp:untempted")),
                DRIFTER + " did not raise its tempt end event."))
            .thenSucceed();
    }

    private static final int NEAR = 5;

    private static ServerPlayer holding(GameTestHelper helper, AddonEntity drifter, int away) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SNOWBALL));
        Platform.placeAt(player, drifter.getX() + away, drifter.getY(), drifter.getZ() + away, 0f, 0f);
        return player;
    }

    /** A random flight with `can_land_on_trees` heads for the top of the leaves beside it. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = PICK_TICKS + 20)
    public void aRandomFlightLandsOnATree(GameTestHelper helper) {
        BlockPos leaves = new BlockPos(6, 1, 3);
        helper.setBlock(leaves, Blocks.OAK_LEAVES);
        AddonEntity drifter = held(helper, "perching", new BlockPos(3, 2, 3));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        Set<BlockPos> picked = headings(helper, drifter);
        helper.runAfterDelay(PICK_TICKS, () -> {
            helper.assertTrue(picked.contains(leaves.above()),
                DRIFTER + " lands on the tree at " + leaves.above() + " and headed for " + picked + ".");
            helper.succeed();
        });
    }

    /** `avoid_damage_blocks` never heads for the air just over a floor of magma. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = PICK_TICKS + 20)
    public void aRandomFlightAvoidsDamagingBlocks(GameTestHelper helper) {
        BlockPos.betweenClosed(new BlockPos(-2, 0, -2), new BlockPos(9, 0, 9))
            .forEach(at -> helper.setBlock(at, Blocks.MAGMA_BLOCK));
        AddonEntity drifter = held(helper, "careful", new BlockPos(3, 2, 3));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        Set<BlockPos> picked = headings(helper, drifter);
        helper.runAfterDelay(PICK_TICKS, () -> {
            helper.assertFalse(picked.isEmpty(), DRIFTER + " never flew off.");
            picked.forEach(spot -> helper.assertFalse(helper.getBlockState(spot.below()).is(Blocks.MAGMA_BLOCK),
                DRIFTER + " avoids damaging blocks and headed for " + spot + ", over magma."));
            helper.succeed();
        });
    }

    /**
     * A breach leaps towards water up to `xz_dist` across, so it carries the entity sideways, and
     * waits its two second `cooldown_time` before the next though its `interval` is one.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = BREACH_TICKS + 20)
    public void aBreachLeapsAcrossAndWaitsItsCooldown(GameTestHelper helper) {
        pool(helper, 1);
        AddonEntity drifter = moving(helper, "breaching", new BlockPos(POOL / 2, 0, POOL / 2));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        List<Long> leaps = new ArrayList<>();
        double[] carried = {0};
        Vec3[] from = {null};
        boolean[] rising = {false};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            boolean risingNow = drifter.getDeltaMovement().y > BREACH_RISE;
            if (risingNow && !rising[0]) {
                leaps.add(now);
                from[0] = drifter.position();
            }
            rising[0] = risingNow;
            if (from[0] != null && !risingNow && drifter.isInWater() && drifter.getDeltaMovement().y <= 0) {
                carried[0] += drifter.position().subtract(from[0]).horizontalDistance();
                from[0] = null;
            }
        });
        helper.runAfterDelay(BREACH_TICKS, () -> {
            helper.assertTrue(leaps.size() >= 2, DRIFTER + " leapt " + leaps.size() + " times.");
            for (int leap = 1; leap < leaps.size(); leap++) {
                long gap = leaps.get(leap) - leaps.get(leap - 1);
                helper.assertTrue(gap >= BREACH_COOLDOWN - 2,
                    DRIFTER + " waits two seconds between leaps and leapt again after " + gap + " ticks.");
            }
            helper.assertTrue(carried[0] > 1.5,
                DRIFTER + " leaps towards water across and was carried " + carried[0] + " blocks in all.");
            helper.succeed();
        });
    }

    private static final int BREACH_TICKS = 200;
    private static final int BREACH_COOLDOWN = 40;
    private static final double BREACH_RISE = 0.3;

    /** `entity_types` names who a swim follows: a tagged pig, past `catch_up_threshold`, is closed on. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = ESCORT_TICKS + 20)
    public void aSwimFollowsTheEntityItsTypesName(GameTestHelper helper) {
        pool(helper, 2);
        Mob pig = escorted(helper, new BlockPos(7, 0, 4), 0f);
        AddonEntity drifter = swimmer(helper, new BlockPos(1, 0, 4));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> helper.assertTrue(drifter.distanceTo(pig) < CLOSED_TO,
            DRIFTER + " follows the pig and is " + drifter.distanceTo(pig) + " from it."));
    }

    private static final int ESCORT_TICKS = 200;
    private static final double CLOSED_TO = 4.5;

    /** Within `match_direction_threshold` a swim heads the way the followed pig faces, east, past it. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = ESCORT_TICKS + 20)
    public void aSwimCloseByMatchesTheWayTheOtherFaces(GameTestHelper helper) {
        pool(helper, 2);
        escorted(helper, new BlockPos(5, 0, 4), EAST);
        AddonEntity drifter = swimmer(helper, new BlockPos(4, 0, 5));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        double start = drifter.getX();
        helper.succeedWhen(() -> helper.assertTrue(drifter.getX() - start > 1.5,
            DRIFTER + " swims east with the pig and has gone " + (drifter.getX() - start) + " east."));
    }

    private static final float EAST = -90f;

    /** `chance_to_stop` of one gives up the moment the followed pig leaves the water. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = ESCORT_TICKS + 20)
    public void aSwimGivesUpOnAnEntityOutOfTheWater(GameTestHelper helper) {
        pool(helper, 2);
        Mob pig = escorted(helper, new BlockPos(6, 0, 4), 0f);
        AddonEntity drifter = swimmer(helper, new BlockPos(1, 0, 4));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        BlockPos ashore = new BlockPos(POOL + 1, 2, 4);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(following(drifter), DRIFTER + " does not follow the pig."))
            .thenExecute(() -> {
                BlockPos at = helper.absolutePos(ashore);
                Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
            })
            .thenExecuteAfter(GIVE_UP_TICKS, () -> helper.assertFalse(following(drifter),
                DRIFTER + " still follows a pig out of the water."))
            .thenSucceed();
    }

    private static final int GIVE_UP_TICKS = 10;

    private static boolean following(AddonEntity drifter) {
        return drifter.runningGoals().stream().anyMatch(goal -> goal.startsWith("SwimWithEntityGoal"));
    }

    private static AddonEntity swimmer(GameTestHelper helper, BlockPos where) {
        AddonEntity drifter = moving(helper, "escorting", where);
        if (drifter != null) drifter.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(SWIM_SPEED);
        return drifter;
    }

    private static final double SWIM_SPEED = 0.5;

    /** A pig carrying the tag the escorting group's `entity_types` asks for, standing where it is put. */
    private static Mob escorted(GameTestHelper helper, BlockPos where, float facing) {
        Mob pig = (Mob) Platform.createMob(BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", "pig")).orElseThrow(), helper.getLevel());
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, facing, 0f);
        pig.setYHeadRot(facing);
        pig.setNoAi(true);
        pig.addTag("tp_escorted");
        helper.getLevel().addFreshEntity(pig);
        return pig;
    }

    /** A jump to a block flies in the long jumping pose, its box shrunk by `scale_factor` to half. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = JUMP_TICKS)
    public void aJumpShrinksTheBoxByItsScaleFactor(GameTestHelper helper) {
        AddonEntity drifter = onAFloor(helper, "leaping", List.of());
        if (drifter == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            helper.assertTrue(drifter.getPose() == Pose.LONG_JUMPING, DRIFTER + " has not jumped.");
            helper.assertTrue(Math.abs(drifter.getBbWidth() - WIDTH * 0.5) < 0.01,
                DRIFTER + " is half as wide while it jumps and is " + drifter.getBbWidth() + " wide.");
        });
    }

    private static final int JUMP_TICKS = 200;
    private static final double WIDTH = 0.6;

    /**
     * `preferred_blocks_chance` of nothing jumps towards blocks other than the preferred gold as well,
     * which a jump's first push across shows: it points at the block the jump was aimed at. Gold lies
     * two blocks off on each side, so wherever a jump lands there is gold far enough off to aim at.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = JUMP_TICKS * 2)
    public void aJumpOnANoughtChanceAimsOffThePreferredBlock(GameTestHelper helper) {
        List<BlockPos> golds = List.of(FLOOR_MIDDLE.east(2), FLOOR_MIDDLE.west(2), FLOOR_MIDDLE.north(2),
            FLOOR_MIDDLE.south(2));
        AddonEntity drifter = onAFloor(helper, "choosy", golds);
        if (drifter == null) {
            helper.succeed();
            return;
        }

        List<Boolean> atGold = new ArrayList<>();
        boolean[] rising = {false};
        helper.onEachTick(() -> {
            Vec3 push = drifter.getDeltaMovement();
            boolean risingNow = push.y > JUMPING;
            if (risingNow && !rising[0]) {
                Vec3 across = push.multiply(1, 0, 1).normalize();
                atGold.add(golds.stream().map(gold -> Vec3.atCenterOf(helper.absolutePos(gold)))
                    .anyMatch(gold -> across.dot(gold.subtract(drifter.position()).multiply(1, 0, 1).normalize())
                        > AIMED_AT));
            }
            rising[0] = risingNow;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(atGold.size() >= JUMPS, DRIFTER + " has jumped " + atGold.size() + " times.");
            helper.assertTrue(atGold.contains(false),
                DRIFTER + " jumps anywhere on a chance of nothing and every jump was aimed at gold.");
        });
    }

    private static final int JUMPS = 5;

    /** Within ten degrees of a gold block. */
    private static final double AIMED_AT = Math.cos(Math.toRadians(10));

    /** `minimum_path_length` of thirty never jumps to a block an open floor lets it walk to. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = JUMP_TICKS)
    public void aJumpSkipsBlocksAShortWalkReaches(GameTestHelper helper) {
        AddonEntity drifter = onAFloor(helper, "strolling", List.of());
        if (drifter == null) {
            helper.succeed();
            return;
        }

        boolean[] jumped = {false};
        helper.onEachTick(() -> jumped[0] |= drifter.getDeltaMovement().y > JUMPING
            || drifter.getPose() == Pose.LONG_JUMPING);
        helper.runAfterDelay(JUMP_TICKS - 20, () -> {
            helper.assertFalse(jumped[0], DRIFTER + " jumped to a block it can walk to in a few steps.");
            helper.succeed();
        });
    }

    private static final double JUMPING = 0.3;
    private static final BlockPos FLOOR_MIDDLE = new BlockPos(4, 0, 4);

    private static AddonEntity onAFloor(GameTestHelper helper, String group, List<BlockPos> golds) {
        BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(8, 0, 8))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        golds.forEach(gold -> helper.setBlock(gold, Blocks.GOLD_BLOCK));
        return moving(helper, group, FLOOR_MIDDLE.above());
    }

    /**
     * A glide sets out at its `start_speed`, well past the tenth Java's phantom starts at, and slows to
     * its `speed_when_turning` through a long turn.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GLIDE_TICKS + 20)
    public void aGlideSetsOutAtItsStartSpeedAndSlowsInTurns(GameTestHelper helper) {
        AddonEntity drifter = moving(helper, "gliding", new BlockPos(3, GLIDE_HEIGHT, 0));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        drifter.setNoGravity(true);
        drifter.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(GLIDE_TOP);
        Vec3 start = drifter.position();
        long begun = helper.getLevel().getGameTime();
        double[] early = {0};
        double[] slowest = {Double.MAX_VALUE};
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime() - begun;
            // Ahead first, then far off behind and to the side, which is a turn of 135 degrees.
            Vec3 wanted = now < TURN_AT ? start.add(0, 0, GLIDE_FAR) : start.add(-GLIDE_FAR, 0, -GLIDE_FAR);
            drifter.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, 1);
            double speed = drifter.getDeltaMovement().horizontalDistance();
            if (now == EARLY) early[0] = speed;
            if (now > TURN_AT + SLOWED_BY && now < TURN_AT + TURN_TICKS) slowest[0] = Math.min(slowest[0], speed);
        });
        helper.runAfterDelay(GLIDE_TICKS, () -> {
            helper.assertTrue(early[0] > EARLY_SPEED,
                DRIFTER + " sets out at nearly half a block a tick and was at " + early[0] + ".");
            helper.assertTrue(slowest[0] < TURNING_SPEED,
                DRIFTER + " slows to a fiftieth through a turn and was never below " + slowest[0] + ".");
            helper.succeed();
        });
    }

    private static final int GLIDE_TICKS = 60;
    /** Over every other test's blocks, so a glide meets nothing. */
    private static final int GLIDE_HEIGHT = 14;
    private static final double GLIDE_TOP = 0.5;
    private static final int GLIDE_FAR = 60;
    private static final int EARLY = 8;
    private static final double EARLY_SPEED = 0.18;
    /** Early enough that the glide is still over the test's own chunks when it turns back. */
    private static final int TURN_AT = 20;
    private static final int SLOWED_BY = 20;
    /** A turn of 135 degrees at the group's five a tick. */
    private static final int TURN_TICKS = 27;
    private static final double TURNING_SPEED = 0.05;

    /** A circle round an anchor over a target climbs `height_above_target_range` over it. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = CIRCLE_TICKS + 20)
    public void aCircleAroundATargetClimbsOverIt(GameTestHelper helper) {
        AddonEntity drifter = moving(helper, "circling", new BlockPos(3, 1, 3));
        Mob pig = escorted(helper, new BlockPos(3, 0, 6), 0f);
        if (drifter == null) {
            helper.succeed();
            return;
        }

        drifter.setTarget(pig);
        helper.succeedWhen(() -> helper.assertTrue(drifter.getY() - pig.getY() > CIRCLE_ABOVE,
            DRIFTER + " circles eight blocks over its target and is " + (drifter.getY() - pig.getY()) + " over it."));
    }

    private static final int CIRCLE_TICKS = 200;
    private static final double CIRCLE_ABOVE = 5;

    /**
     * A slime's turn comes `min_change_direction_time` and a random number of whole seconds up to
     * `add_random_time_range` apart, a second each with a range of nought, and turns at most half
     * its `turn_range` of twenty degrees either way.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = TURN_TICKS_WATCHED + 20)
    public void aSlimeTurnComesEverySecondAndTurnsWithinItsRange(GameTestHelper helper) {
        AddonEntity drifter = moving(helper, "turning", new BlockPos(3, 0, 3));
        if (drifter == null) {
            helper.succeed();
            return;
        }

        List<Long> turns = new ArrayList<>();
        List<Float> turned = new ArrayList<>();
        float[] facing = {drifter.getYRot()};
        helper.onEachTick(() -> {
            float now = drifter.getYRot();
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(now - facing[0])) > 0.01f) {
                turns.add(helper.getLevel().getGameTime());
                turned.add(Math.abs(net.minecraft.util.Mth.wrapDegrees(now - facing[0])));
            }
            facing[0] = now;
        });
        helper.runAfterDelay(TURN_TICKS_WATCHED, () -> {
            helper.assertTrue(turns.size() >= 4, DRIFTER + " turned " + turns.size() + " times.");
            for (int turn = 1; turn < turns.size(); turn++) {
                long gap = turns.get(turn) - turns.get(turn - 1);
                helper.assertTrue(Math.abs(gap - SECOND) <= 1,
                    DRIFTER + " turns once a second and turned again after " + gap + " ticks.");
            }
            turned.forEach(angle -> helper.assertTrue(angle <= HALF_RANGE + 0.01f,
                DRIFTER + " turns at most ten degrees either way and turned " + angle + "."));
            helper.succeed();
        });
    }

    private static final int TURN_TICKS_WATCHED = 120;
    private static final int SECOND = 20;
    private static final float HALF_RANGE = 10f;

    /**
     * Every spot the drifter's navigator is sent to, relative to the test, which runs unturned. The
     * helper's own `relativePos` turns a position half a circle when the test is unturned. A drifter
     * held in place never gets anywhere, so its wander is ended every few ticks for it to pick again.
     */
    private static Set<BlockPos> headings(GameTestHelper helper, AddonEntity drifter) {
        Set<BlockPos> picked = new HashSet<>();
        helper.onEachTick(() -> {
            // Java's strolls stop setting off once a mob has gone five seconds with no player near.
            drifter.setNoActionTime(0);
            // The path the navigator follows, since asking for a path at all moves its target.
            var path = drifter.getNavigation().getPath();
            if (path != null) picked.add(path.getTarget().subtract(helper.absolutePos(BlockPos.ZERO)));
            if (helper.getLevel().getGameTime() % REPICK == 0) drifter.getNavigation().stop();
        });
        return picked;
    }

    private static final int REPICK = 5;

    /** A drifter with its group on and its feet held, so only the spots it picks move. */
    private static AddonEntity held(GameTestHelper helper, String group, BlockPos where) {
        AddonEntity drifter = moving(helper, group, where);
        if (drifter == null) return null;

        drifter.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        drifter.getAttribute(Attributes.FLYING_SPEED).setBaseValue(0);
        return drifter;
    }

    private static AddonEntity moving(GameTestHelper helper, String group, BlockPos where) {
        AddonEntity drifter = BehaviourGameTests.spawn(helper, DRIFTER, where);
        if (drifter == null) return null;

        EntityEvents.trigger(drifter, "tp:drifter_make_" + group, null, ServerHooks.state());
        drifter.setNoAi(false);
        return drifter;
    }

    private static double across(AddonEntity drifter, ServerPlayer player) {
        return Math.hypot(player.getX() - drifter.getX(), player.getZ() - drifter.getZ());
    }

    /** A pool `depth` blocks deep and {@link #POOL} across, walled and floored in stone so it lies still. */
    private static void pool(GameTestHelper helper, int depth) {
        BlockPos.betweenClosed(new BlockPos(-1, -1, -1), new BlockPos(POOL + 1, depth - 1, POOL + 1))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(POOL, depth - 1, POOL))
            .forEach(at -> helper.setBlock(at, Blocks.WATER));
    }

    private static final int POOL = 8;
}
