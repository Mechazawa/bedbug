package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.bedrock.behaviour.Descriptor;
import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * What a `minecraft:navigation.*` component lets the pathfinder do, put onto the navigator Java holds.
 *
 * Java asks its node evaluator about doors and floating, its ground navigator about the sun, and the
 * mob itself what each kind of ground costs, so every allowance a pack states has somewhere to go.
 * Each default here is Bedrock's: a pack that says nothing about damaging blocks walks through them,
 * where Java would have priced them for every mob.
 */
final class Pathing {
    private final boolean floats;
    private final boolean swims;
    private final boolean walks;
    private final boolean breaches;
    private final boolean avoidsWater;
    private final boolean avoidsDamage;
    private final boolean avoidsSun;
    private final boolean opensDoors;
    private final boolean passesDoors;
    private final boolean crossesLava;
    private final boolean jumps;
    private final boolean amphibious;
    private final boolean avoidsPortals;
    private final List<Descriptor> avoidedBlocks;
    private final boolean startsInAir;
    private final boolean followsDoorAnnotations;

    private Pathing(JsonObject body) {
        this.swims = Goals.flag(body, "can_swim", false);
        this.walks = Goals.flag(body, "can_walk", true);
        this.breaches = Goals.flag(body, "can_breach", false);
        this.floats = Goals.flag(body, "can_path_over_water", false) || Goals.flag(body, "can_float", false)
            || swims || !Goals.flag(body, "can_sink", true);
        this.avoidsWater = Goals.flag(body, "avoid_water", false);
        this.avoidsDamage = Goals.flag(body, "avoid_damage_blocks", false);
        this.avoidsSun = Goals.flag(body, "avoid_sun", false);
        this.opensDoors = Goals.flag(body, "can_open_doors", false) || Goals.flag(body, "can_break_doors", false);
        this.passesDoors = Goals.flag(body, "can_pass_doors", true);
        this.crossesLava = Goals.flag(body, "can_path_over_lava", false)
            || Goals.flag(body, "can_walk_in_lava", false);
        this.jumps = Goals.flag(body, "can_jump", true);
        this.amphibious = Goals.flag(body, "is_amphibious", false);
        this.avoidsPortals = Goals.flag(body, "avoid_portals", false);
        this.avoidedBlocks = Descriptor.listed(body, "blocks_to_avoid");
        this.startsInAir = Goals.flag(body, "can_path_from_air", false);
        this.followsDoorAnnotations = Goals.flag(body, "using_door_annotation", true);
    }

    /** The allowances a navigation component states, or Bedrock's defaults where the entity has none. */
    static Pathing of(JsonObject body) {
        return new Pathing(body == null ? new JsonObject() : body);
    }

    /**
     * The navigator for an entity that both swims and walks, or swims and never walks, or null for
     * one the caller picks a navigator for by the component's name.
     *
     * `minecraft:navigation.generic` is how Bedrock describes a fish, a turtle and an axolotl alike,
     * and only the allowances inside it tell them apart.
     */
    PathNavigation navigatorFor(Mob mob, Level level) {
        if (!swims) return null;
        if (!walks) return breaches ? new BreachingNavigation(mob, level) : new WaterBoundPathNavigation(mob, level);
        return new AmphibiousPathNavigation(mob, level);
    }

    /**
     * Puts the allowances on the navigator and the prices on the mob.
     *
     * A closed door a mob may break is one its path may run through, which is how Java's own zombie
     * is told it can reach a villager behind one. Under `using_door_annotation`, which every vanilla
     * entity writes as true, a door annotation the entity carries lets its path through a closed door
     * as well.
     */
    void applyTo(Mob mob, PathNavigation navigation, boolean annotatedForDoors) {
        navigation.getNodeEvaluator().setCanOpenDoors(opensDoors || followsDoorAnnotations && annotatedForDoors);
        navigation.getNodeEvaluator().setCanPassDoors(passesDoors);
        navigation.setCanFloat(floats);
        if (navigation instanceof GroundPathNavigation ground) ground.setAvoidSun(avoidsSun);
        if (navigation instanceof WalkingNavigation walking) {
            walking.plan(avoidsPortals, jumps, amphibious);
        }
        if (navigation instanceof AirNavigation air) air.startsInAir = startsInAir;

        Platform.priceWater(mob, avoidsWater);
        Platform.priceDamagingBlocks(mob, avoidsDamage);
        Platform.priceLava(mob, !crossesLava);
    }

    /**
     * Whether a node is closed to the mob: a block the component lists fills a cell the mob's box
     * takes there, or is the block it would stand on.
     */
    boolean avoids(Mob mob, BlockGetter level, int x, int y, int z) {
        if (avoidedBlocks.isEmpty()) return false;

        int across = Mth.floor(mob.getBbWidth() + 1);
        int up = Mth.ceil(mob.getBbHeight());
        return BlockPos.betweenClosedStream(x, y - 1, z, x + across - 1, y + up - 1, z + across - 1)
            .map(level::getBlockState)
            .anyMatch(state -> avoidedBlocks.stream().anyMatch(avoided -> avoided.matches(state)));
    }

    /**
     * Java's flying navigator for `minecraft:navigation.fly` and `hover`, which plans from the air
     * only under `can_path_from_air` and otherwise waits for the ground or a liquid, as Java's ground
     * navigator does.
     */
    static final class AirNavigation extends FlyingPathNavigation {
        private boolean startsInAir;

        AirNavigation(Mob mob, Level level) {
            super(mob, level);
        }

        @Override
        protected boolean canUpdatePath() {
            return super.canUpdatePath() && (startsInAir || mob.onGround() || mob.isInLiquid());
        }
    }

    /**
     * A swimming navigator whose paths may leave the water for a leap, which Java grants its own
     * dolphin by checking the entity type as the navigator is built.
     */
    private static final class BreachingNavigation extends WaterBoundPathNavigation {
        BreachingNavigation(Mob mob, Level level) {
            super(mob, level);
        }

        @Override
        protected net.minecraft.world.level.pathfinder.PathFinder createPathFinder(int maxVisitedNodes) {
            nodeEvaluator = new net.minecraft.world.level.pathfinder.SwimNodeEvaluator(true);
            nodeEvaluator.setCanPassDoors(false);
            return new net.minecraft.world.level.pathfinder.PathFinder(nodeEvaluator, maxVisitedNodes);
        }

        @Override
        protected boolean canUpdatePath() {
            return true;
        }
    }
}
