package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;

import java.util.stream.IntStream;

/**
 * The fields of `minecraft:navigation.*` and `minecraft:preferred_path`, read back off the paths the
 * entity's navigator plans.
 *
 * `tp:wayfarer` walks with an empty `minecraft:navigation.walk`, and each of its groups carries one
 * field that changes it. Every test asks a plain wayfarer for the same path first, so a layout that
 * no wayfarer could cross fails as a broken test rather than passing as a refusal.
 */
public final class PathingGameTests {
    private static final String WAYFARER = "tp:wayfarer";
    private static final String EMPTY = "fabric-gametest-api-v1:empty";

    /** A follow range long enough for a search that prices its steps to reach round a ridge. */
    private static final double LONG_FOLLOW = 64;

    /** The corridor's middle, where each test puts what the wayfarer has to cross. */
    private static final int MIDDLE = 4;

    /** `avoid_portals` keeps a path out of a portal it could otherwise walk through. */
    @GameTest(structure = EMPTY)
    public void aPathKeepsOutOfAPortalUnderAvoidPortals(GameTestHelper helper) {
        corridor(helper);
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState();
        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(3, 1, MIDDLE)), portal, Block.UPDATE_CLIENTS);
        helper.getLevel().setBlock(helper.absolutePos(new BlockPos(3, 2, MIDDLE)), portal, Block.UPDATE_CLIENTS);
        crosses(helper, "tp:wayfarer_portal_shy", new BlockPos(3, 1, 6), false, "a nether portal");
    }

    /** `blocks_to_avoid` keeps a path off a block it names. */
    @GameTest(structure = EMPTY)
    public void aPathKeepsOffABlockItAvoidsByName(GameTestHelper helper) {
        corridor(helper);
        helper.setBlock(new BlockPos(3, 0, MIDDLE), Blocks.GOLD_BLOCK);
        crosses(helper, "tp:wayfarer_block_shy", new BlockPos(3, 1, 6), false, "a gold block");
    }

    /** `blocks_to_avoid` also names blocks by tag, here every trapdoor. */
    @GameTest(structure = EMPTY)
    public void aPathKeepsOffABlockItAvoidsByTag(GameTestHelper helper) {
        corridor(helper);
        helper.setBlock(new BlockPos(3, 1, MIDDLE), Blocks.OAK_TRAPDOOR);
        crosses(helper, "tp:wayfarer_block_shy", new BlockPos(3, 1, 6), false, "a trapdoor");
    }

    /** `can_jump` false leaves out every step up a block. */
    @GameTest(structure = EMPTY)
    public void aMobThatMayNotJumpPlansNoStepUp(GameTestHelper helper) {
        corridor(helper);
        IntStream.rangeClosed(MIDDLE, 6).forEach(z -> helper.setBlock(new BlockPos(3, 1, z), Blocks.STONE));
        crosses(helper, "tp:wayfarer_grounded", new BlockPos(3, 2, 6), false, "a step up");
    }

    /**
     * Without `is_amphibious` a mob that sinks plans no walk along the bottom of water over its head,
     * and with it the same walk is planned.
     */
    @GameTest(structure = EMPTY)
    public void onlyAnAmphibiousMobWalksTheBottomOfDeepWater(GameTestHelper helper) {
        corridor(helper);
        for (int z = 1; z <= 6; z++) {
            boolean pool = z == 3 || z == MIDDLE;
            for (int y = 1; y <= 2; y++) {
                BlockPos at = new BlockPos(3, y, z);
                if (pool) {
                    helper.getLevel().setBlock(helper.absolutePos(at), Blocks.WATER.defaultBlockState(),
                        Block.UPDATE_CLIENTS);
                } else if (y == 1) {
                    helper.setBlock(at, Blocks.STONE);
                }
            }
        }
        AddonEntity plain = wayfarer(helper, null, new BlockPos(3, 2, 1));
        AddonEntity amphibious = wayfarer(helper, "tp:wayfarer_amphibious", new BlockPos(3, 2, 1));
        if (plain == null || amphibious == null) {
            helper.succeed();
            return;
        }

        BlockPos across = new BlockPos(3, 2, 6);
        helper.assertFalse(reaches(helper, plain, across),
            WAYFARER + " is not amphibious and planned a walk along the bottom of deep water.");
        helper.assertTrue(reaches(helper, amphibious, across),
            WAYFARER + " is amphibious and planned no walk along the bottom of deep water.");
        helper.succeed();
    }

    /** `preferred_path_blocks` and `default_block_cost` send a walk the long way round over a path. */
    @GameTest(structure = EMPTY)
    public void aPreferredPathIsWalkedTheLongWayRound(GameTestHelper helper) {
        floor(helper);
        for (int step = 1; step <= 6; step++) {
            helper.setBlock(new BlockPos(step, 0, 3), Blocks.DIRT_PATH);
            if (step <= 3) {
                helper.setBlock(new BlockPos(1, 0, step), Blocks.DIRT_PATH);
                helper.setBlock(new BlockPos(6, 0, step), Blocks.DIRT_PATH);
            }
        }
        AddonEntity plain = wayfarer(helper, null, new BlockPos(1, 1, 1));
        AddonEntity paved = wayfarer(helper, "tp:wayfarer_paved", new BlockPos(1, 1, 1));
        if (plain == null || paved == null) {
            helper.succeed();
            return;
        }

        BlockPos target = new BlockPos(6, 1, 1);
        helper.assertFalse(visits(helper, plain, target, node -> node.getZ() == 3),
            WAYFARER + " prefers no block and took the long way round.");
        helper.assertTrue(visits(helper, paved, target, node -> node.getZ() == 3),
            WAYFARER + " prices stone over a dirt path and walked straight across the stone.");
        helper.succeed();
    }

    /** `jump_cost` sends a walk round a ridge it would otherwise climb over. */
    @GameTest(structure = EMPTY)
    public void aJumpCostSendsAWalkRoundARidge(GameTestHelper helper) {
        floor(helper);
        for (int z = 0; z <= 4; z++) {
            helper.setBlock(new BlockPos(3, 1, z), Blocks.STONE);
            helper.setBlock(new BlockPos(4, 1, z), Blocks.STONE);
        }
        AddonEntity plain = wayfarer(helper, null, new BlockPos(1, 1, 1));
        AddonEntity level = wayfarer(helper, "tp:wayfarer_level", new BlockPos(1, 1, 1));
        if (plain == null || level == null) {
            helper.succeed();
            return;
        }

        BlockPos target = new BlockPos(6, 1, 1);
        // Java's search stops at a walked distance of the follow range, and it counts a node's walk
        // from whichever neighbour last looked at it, so a priced search falls short at sixteen.
        level.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(LONG_FOLLOW);
        helper.assertTrue(visits(helper, plain, target, node -> node.getY() == 2),
            WAYFARER + " pays nothing to jump and walked round the ridge.");
        helper.assertFalse(visits(helper, level, target, node -> node.getY() == 2),
            WAYFARER + " pays twenty to jump and climbed over the ridge.");
        helper.succeed();
    }

    /** A hovering mob plans from the air only under `can_path_from_air`. */
    @GameTest(structure = EMPTY)
    public void aHoveringMobPlansFromTheAirOnlyWhenItMay(GameTestHelper helper) {
        AddonEntity grounded = wayfarer(helper, "tp:wayfarer_hovering", new BlockPos(2, 4, 2));
        AddonEntity airborne = wayfarer(helper, "tp:wayfarer_hovering_from_air", new BlockPos(5, 4, 2));
        if (grounded == null || airborne == null) {
            helper.succeed();
            return;
        }

        grounded.setNoGravity(true);
        airborne.setNoGravity(true);
        BlockPos ahead = helper.absolutePos(new BlockPos(3, 4, 6));
        helper.assertTrue(grounded.getNavigation().createPath(ahead, 0) == null,
            WAYFARER + " may not path from the air and planned a path from there.");
        helper.assertTrue(airborne.getNavigation().createPath(ahead, 0) != null,
            WAYFARER + " may path from the air and planned no path from there.");
        helper.succeed();
    }

    /**
     * A door annotation lets the path through a closed door, so the break door goal it brings finds
     * the door, unless `using_door_annotation` is false.
     */
    @GameTest(structure = EMPTY, maxTicks = 200)
    public void aBreakDoorAnnotationLetsThePathThroughADoor(GameTestHelper helper) {
        door(helper, "tp:wayfarer_door_annotated", true);
    }

    @GameTest(structure = EMPTY, maxTicks = 200)
    public void aDoorAnnotationIsIgnoredWithoutUsingDoorAnnotation(GameTestHelper helper) {
        door(helper, "tp:wayfarer_door_unannotated", false);
    }

    private static void door(GameTestHelper helper, String group, boolean breaks) {
        for (int z = -4; z <= 12; z++) {
            for (int y = 0; y <= 3; y++) helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        }
        BlockPos door = new BlockPos(3, 0, 3);
        BlockState closed = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        helper.setBlock(door, closed);
        helper.setBlock(door.above(), closed.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        AddonEntity breaker = wayfarer(helper, group, new BlockPos(1, 0, 3));
        if (breaker == null) {
            helper.succeed();
            return;
        }

        breaker.setNoAi(false);
        breaker.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.25);
        BlockPos beyond = helper.absolutePos(new BlockPos(6, 0, 3));
        helper.onEachTick(() -> {
            if (breaker.getNavigation().isDone()) {
                breaker.getNavigation().moveTo(beyond.getX() + 0.5, beyond.getY(), beyond.getZ() + 0.5, 1.0);
            }
        });
        if (breaks) {
            helper.succeedWhen(() -> helper.assertFalse(helper.getBlockState(door).getBlock() instanceof DoorBlock,
                WAYFARER + " carries a break door annotation and never broke the door in its way."));
            return;
        }
        helper.startSequence()
            .thenExecuteAfter(150, () -> helper.assertTrue(helper.getBlockState(door).getBlock() instanceof DoorBlock,
                WAYFARER + " ignores its door annotation and broke the door all the same."))
            .thenSucceed();
    }

    /**
     * A plain wayfarer reaches the far end of the corridor and one carrying the group does or does
     * not, as `reaches` says.
     */
    private static void crosses(GameTestHelper helper, String group, BlockPos target, boolean reaches,
        String obstacle) {
        AddonEntity plain = wayfarer(helper, null, new BlockPos(3, 1, 1));
        AddonEntity changed = wayfarer(helper, group, new BlockPos(3, 1, 1));
        if (plain == null || changed == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(reaches(helper, plain, target),
            WAYFARER + " planned no path across " + obstacle + " with an empty navigation.");
        helper.assertTrue(reaches(helper, changed, target) == reaches,
            WAYFARER + " in " + group + (reaches ? " planned no path across " : " planned a path across ")
                + obstacle + ".");
        helper.succeed();
    }

    private static boolean reaches(GameTestHelper helper, AddonEntity entity, BlockPos target) {
        Path path = entity.getNavigation().createPath(helper.absolutePos(target), 0);
        return path != null && path.canReach();
    }

    private static boolean visits(GameTestHelper helper, AddonEntity entity, BlockPos target,
        java.util.function.Predicate<BlockPos> node) {
        Path path = entity.getNavigation().createPath(helper.absolutePos(target), 0);
        helper.assertTrue(path != null && path.canReach(), WAYFARER + " planned no path to its target.");
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return IntStream.range(0, path.getNodeCount())
            .mapToObj(i -> path.getNodePos(i).subtract(origin))
            .anyMatch(node);
    }

    /** A corridor one block wide along Z at X 3, closed at both ends, over a stone floor. */
    private static void corridor(GameTestHelper helper) {
        floor(helper);
        for (int z = 0; z <= 7; z++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(2, y, z), Blocks.STONE);
                helper.setBlock(new BlockPos(4, y, z), Blocks.STONE);
                if (z == 0 || z == 7) helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
            }
        }
    }

    private static void floor(GameTestHelper helper) {
        BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(7, 0, 7)).forEach(at -> helper.setBlock(at, Blocks.STONE));
    }

    /** A wayfarer standing still where the test puts it, carrying the group the event names. */
    private static AddonEntity wayfarer(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity wayfarer = BehaviourGameTests.spawn(helper, WAYFARER, where);
        if (wayfarer == null) return null;

        if (event != null) EntityEvents.trigger(wayfarer, event, null, ServerHooks.state());
        wayfarer.setOnGround(where.getY() <= 2);
        return wayfarer;
    }
}
