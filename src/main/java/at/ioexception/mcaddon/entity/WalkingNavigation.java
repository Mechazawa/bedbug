package at.ioexception.mcaddon.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.function.Predicate;

/**
 * Java's ground navigator, planning with what a pack's `minecraft:navigation.walk` or `generic` and
 * its `minecraft:preferred_path` say about single blocks, steps up and the ground under water.
 *
 * Java prices a node by the kind of ground it is. Bedrock also names blocks to keep off, refuses
 * steps up for a mob that may not jump, keeps a mob that is not amphibious off the bottom of deep
 * water,. Each of those is a node this evaluator refuses after Java's own evaluator has placed it.
 */
final class WalkingNavigation extends GroundPathNavigation {
    private Predicate<BlockState> avoided = state -> false;
    private boolean jumps = true;
    private boolean amphibious;

    WalkingNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    void plan(Predicate<BlockState> avoid, boolean canJump, boolean walksUnderWater) {
        this.avoided = avoid;
        this.jumps = canJump;
        this.amphibious = walksUnderWater;
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        nodeEvaluator = new Evaluator();
        return new PathFinder(nodeEvaluator, maxVisitedNodes);
    }

    private final class Evaluator extends WalkNodeEvaluator {
        @Override
        public PathType getPathType(PathfindingContext context, int x, int y, int z) {
            PathType type = super.getPathType(context, x, y, z);
            if (type == PathType.BLOCKED) return type;

            boolean refused = avoided.test(context.getBlockState(new BlockPos(x, y, z)))
                || avoided.test(context.getBlockState(new BlockPos(x, y - 1, z)));
            return refused ? PathType.BLOCKED : type;
        }

        @Override
        protected Node findAcceptedNode(int x, int y, int z, int jumpSize, double nodeHeight, Direction travelDirection,
            PathType blockPathTypeCurrent) {
            Node node = super.findAcceptedNode(x, y, z, jumps ? jumpSize : 0, nodeHeight, travelDirection,
                blockPathTypeCurrent);
            if (node == null || node.costMalus < 0) return node;
            if (underWater(node)) return null;

            return node;
        }

        /**
         * A node on the bottom with water over its head, which only an amphibious mob plans through.
         * A mob already in water may plan its way out through it.
         */
        private boolean underWater(Node node) {
            return !amphibious && !canFloat() && !mob.isInWater() && node.type == PathType.WATER
                && getCachedPathType(node.x, node.y + 1, node.z) == PathType.WATER;
        }
    }
}
