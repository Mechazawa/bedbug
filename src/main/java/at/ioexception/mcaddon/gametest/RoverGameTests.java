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

import java.util.List;

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

    /**
     * `home_block_list` holds an entity to its home only while a listed block stands there: one
     * standing in a cobweb is held, one standing in air is not, and the first lets go once the cobweb
     * is broken.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void aHomeHoldsOnlyWhileItsBlockStands(GameTestHelper helper) {
        helper.setBlock(STAND, Blocks.COBWEB);
        AddonEntity webbed = rover(helper, "tp:rover_make_home_listed", STAND);
        if (webbed == null) {
            helper.succeed();
            return;
        }
        AddonEntity bare = rover(helper, "tp:rover_make_home_listed", STAND.south(3));
        helper.assertTrue(Platform.hasHome(webbed), ROVER + " standing in the cobweb its home lists is not held.");
        helper.assertFalse(Platform.hasHome(bare), ROVER + " standing where its home lists no block is held.");

        helper.setBlock(STAND, Blocks.AIR);
        helper.succeedWhen(() -> helper.assertFalse(Platform.hasHome(webbed),
            ROVER + " is still held to a home whose cobweb is gone."));
    }

    /**
     * Without `ignore_visibility` a pig behind a wall frightens nothing; with it, the same pig sends
     * the rover off.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void anEntityFleesOnlyWhatItSeesUnlessToldOtherwise(GameTestHelper helper) {
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        }
        AddonEntity seeing = rover(helper, "tp:rover_make_avoid_seen", new BlockPos(2, 0, 1));
        if (seeing == null) {
            helper.succeed();
            return;
        }
        AddonEntity blind = rover(helper, "tp:rover_make_avoid_blind", new BlockPos(2, 0, 3));
        pig(helper, new BlockPos(4, 0, 2));
        helper.runAfterDelay(40, () -> {
            helper.assertFalse(seeing.value("is_avoiding_mobs") == Boolean.TRUE || moved(helper, seeing, 2, 1),
                ROVER + " fled a pig it cannot see.");
            helper.assertTrue(moved(helper, blind, 2, 3),
                ROVER + " stating ignore_visibility did not flee the pig behind the wall.");
            helper.succeed();
        });
    }

    /** The flight ends `max_flee` blocks from the pig, with `on_escape_event`. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void anEntityEscapesAtItsMaxFleeAndSaysSo(GameTestHelper helper) {
        AddonEntity rover = rover(helper, "tp:rover_make_avoid_escaping", STAND);
        if (rover == null) {
            helper.succeed();
            return;
        }
        pig(helper, STAND.east(2));
        helper.succeedWhen(() -> helper.assertTrue(rover.declaresNow("minecraft:is_chested"),
            ROVER + " never raised on_escape_event."));
    }

    /** `remove_target` drops what the entity was after as it starts to flee. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void anEntityFleeingDropsItsTarget(GameTestHelper helper) {
        AddonEntity rover = rover(helper, "tp:rover_make_avoid_dropping", STAND);
        if (rover == null) {
            helper.succeed();
            return;
        }
        Mob pig = pig(helper, STAND.east(2));
        rover.setTarget(pig);
        helper.succeedWhen(() -> {
            helper.assertTrue(rover.value("is_avoiding_mobs") == Boolean.TRUE, ROVER + " never fled.");
            helper.assertTrue(rover.getTarget() == null, ROVER + " fled its target and kept it.");
        });
    }

    /**
     * `probability_per_strength` of nothing never flees a llama, which carries a strength, and
     * always flees a pig, which carries none.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void aStrongCandidateIsFledByItsStrength(GameTestHelper helper) {
        AddonEntity nearLlama = rover(helper, "tp:rover_make_avoid_wary", STAND);
        if (nearLlama == null) {
            helper.succeed();
            return;
        }
        Mob llama = still(helper, "llama", STAND.east(2));
        helper.runAfterDelay(40, () -> {
            helper.assertFalse(nearLlama.value("is_avoiding_mobs") == Boolean.TRUE,
                ROVER + " fled a llama at a chance of nothing per point of strength.");
            llama.discard();
            pig(helper, STAND.east(2));
            helper.succeedWhen(() -> helper.assertTrue(nearLlama.value("is_avoiding_mobs") == Boolean.TRUE,
                ROVER + " did not flee a pig, which has no strength to be weighed."));
        });
    }

    /** `avoid_mob_sound` is made as the flight starts and again every `sound_interval` seconds. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aFleeingEntityMakesItsAvoidSound(GameTestHelper helper) {
        KeeperGoalGameTests.Listener listener = KeeperGoalGameTests.Listener.join(helper, STAND.south(3));
        AddonEntity rover = rover(helper, "tp:rover_make_avoid_aloud", STAND);
        if (rover == null) {
            listener.leave();
            helper.succeed();
            return;
        }
        pig(helper, STAND.east(2));
        helper.succeedWhen(() -> {
            List<Long> heard = listener.heard("tp:rover.retreat", rover);
            helper.assertTrue(heard.size() >= 2, ROVER + " made its retreat sound " + heard.size() + " times.");
            helper.assertTrue(heard.get(1) - heard.get(0) >= 9 && heard.get(1) - heard.get(0) <= 12,
                ROVER + " left " + (heard.get(1) - heard.get(0)) + " ticks between half second sounds.");
            listener.leave();
        });
    }

    /** Outside `sprint_distance` the flight runs at the walking speed. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aFleeingEntitySprintsOnlyWithinItsSprintDistance(GameTestHelper helper) {
        AddonEntity rover = rover(helper, "tp:rover_make_avoid_pacing", STAND);
        if (rover == null) {
            helper.succeed();
            return;
        }
        pig(helper, STAND.east(4));
        helper.succeedWhen(() -> {
            helper.assertTrue(rover.value("is_avoiding_mobs") == Boolean.TRUE, ROVER + " never fled.");
            helper.assertTrue(rover.getMoveControl().getSpeedModifier() == 0.5, ROVER
                + " four blocks off with a sprint_distance of 2 runs at " + rover.getMoveControl().getSpeedModifier());
        });
    }

    /** `avoid_target_xz` and `avoid_target_y` bound the spot the flight heads for. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aFleeingEntityPicksItsSpotWithinItsAvoidTargetBox(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        AddonEntity rover = rover(helper, "tp:rover_make_avoid_near", new BlockPos(2, 1, 4));
        if (rover == null) {
            helper.succeed();
            return;
        }
        BlockPos start = rover.blockPosition();
        pig(helper, new BlockPos(4, 1, 4));
        helper.succeedWhen(() -> {
            helper.assertTrue(rover.getNavigation().getPath() != null, ROVER + " never planned a flight.");
            BlockPos spot = rover.getNavigation().getPath().getTarget();
            helper.assertTrue(Math.abs(spot.getX() - start.getX()) <= 1 && Math.abs(spot.getZ() - start.getZ()) <= 1,
                ROVER + " with an avoid_target_xz of 1 heads for " + spot + " from " + start + ".");
        });
    }

    /** `tempt_sound` is made while tempted, `sound_interval` seconds apart. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 100)
    public void aTemptedEntityMakesItsTemptSound(GameTestHelper helper) {
        KeeperGoalGameTests.Listener listener = KeeperGoalGameTests.Listener.join(helper, STAND.east(3));
        listener.player().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        listener.player().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE));
        AddonEntity rover = rover(helper, "tp:rover_make_tempted_aloud", STAND);
        if (rover == null) {
            listener.leave();
            helper.succeed();
            return;
        }
        rover.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).setBaseValue(0);
        helper.succeedWhen(() -> {
            List<Long> heard = listener.heard("tp:rover.tempt", rover);
            helper.assertTrue(heard.size() >= 2, ROVER + " made its tempt sound " + heard.size() + " times.");
            long gap = heard.get(1) - heard.get(0);
            helper.assertTrue(gap >= 9 && gap <= 12, ROVER + " left " + gap + " ticks between half second sounds.");
            listener.leave();
        });
    }

    /**
     * A note block played nearby holds back a follower stating `ignore_vibration` false, while one
     * ignoring vibrations walks to its owner.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 60)
    public void aNoteBlockHoldsBackAFollowerMindingVibrations(GameTestHelper helper) {
        KeeperGoalGameTests.Listener owner = KeeperGoalGameTests.Listener.join(helper, new BlockPos(7, 0, 2));
        owner.player().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        AddonEntity minding = rover(helper, "tp:rover_make_following_minding", new BlockPos(1, 0, 1));
        if (minding == null) {
            owner.leave();
            helper.succeed();
            return;
        }
        AddonEntity ignoring = rover(helper, "tp:rover_make_following", new BlockPos(1, 0, 3));
        minding.setOwner(owner.player());
        ignoring.setOwner(owner.player());
        helper.getLevel().gameEvent(null, net.minecraft.world.level.gameevent.GameEvent.NOTE_BLOCK_PLAY,
            helper.absolutePos(new BlockPos(1, 0, 2)));
        helper.runAfterDelay(40, () -> {
            helper.assertFalse(moved(helper, minding, 1, 1),
                ROVER + " minding vibrations followed its owner just after a note block played.");
            helper.assertTrue(moved(helper, ignoring, 1, 3), ROVER + " ignoring vibrations did not follow its owner.");
            owner.leave();
            helper.succeed();
        });
    }

    /**
     * A potion is drunk over its use duration: while drinking the entity holds it and moves at its
     * speed plus `speed_modifier`, and the effect lands at the end.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 80)
    public void aDrinkTakesTimeAndSlowsTheDrinker(GameTestHelper helper) {
        AddonEntity rover = rover(helper, "tp:rover_make_drinking", STAND);
        if (rover == null) {
            helper.succeed();
            return;
        }
        var speed = rover.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(rover.isUsingItem(), ROVER + " is not drinking ten ticks in.");
            helper.assertFalse(rover.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE),
                ROVER + " has the potion's effect before it finished drinking.");
            helper.assertTrue(Math.abs(speed.getValue()) < 1.0E-6,
                ROVER + " drinking with a speed_modifier of -0.25 moves at " + speed.getValue() + ".");
            helper.succeedWhen(() -> {
                helper.assertTrue(rover.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE),
                    ROVER + " finished no drink.");
                helper.assertTrue(Math.abs(speed.getValue() - 0.25) < 1.0E-6,
                    ROVER + " kept its drinking speed of " + speed.getValue() + " after the drink.");
            });
        });
    }

    /** A `magic` shooter throws nothing while it drinks; one that is not magic throws. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 40)
    public void aMagicShooterDoesNotShootWhileDrinking(GameTestHelper helper) {
        AddonEntity magic = rover(helper, "tp:rover_make_magic", STAND);
        if (magic == null) {
            helper.succeed();
            return;
        }
        AddonEntity plain = rover(helper, "tp:rover_make_plain_shooter", STAND.south(4));
        Mob pig = pig(helper, STAND.east(5));
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(magic.isUsingItem() && plain.isUsingItem(), ROVER + " is not drinking ten ticks in.");
            magic.performRangedAttack(pig, 1f);
            plain.performRangedAttack(pig, 1f);
            helper.assertTrue(snowballsBy(helper, magic) == 0, ROVER + " shot a magic snowball while drinking.");
            helper.assertTrue(snowballsBy(helper, plain) == 1, ROVER + " that is not magic shot nothing.");
            helper.succeed();
        });
    }

    /**
     * A sleeping entity takes the box `minecraft:behavior.sleep` states: Mojang's villager numbers,
     * a block across and three tenths high, resting six tenths above the floor of its bed's block.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 20)
    public void aSleeperTakesTheBoxItsSleepGoalStates(GameTestHelper helper) {
        AddonEntity sleeper = BehaviourGameTests.spawn(helper, ROVER, STAND);
        if (sleeper == null) {
            helper.succeed();
            return;
        }
        EntityEvents.trigger(sleeper, "tp:rover_make_sleeper", null, ServerHooks.state());
        NapperGameTests.bed(helper, STAND.south(2));
        BlockPos bed = helper.absolutePos(STAND.south(2));
        sleeper.startSleeping(bed);
        helper.runAfterDelay(2, () -> {
            net.minecraft.world.phys.AABB box = sleeper.getBoundingBox();
            helper.assertTrue(Math.abs(box.getXsize() - 1) < 1.0E-4 && Math.abs(box.getYsize() - 0.3) < 1.0E-4,
                ROVER + " sleeps in a box " + box.getXsize() + " across and " + box.getYsize() + " high.");
            helper.assertTrue(Math.abs(box.minY - (bed.getY() + 0.6)) < 1.0E-4,
                ROVER + " sleeps " + (box.minY - bed.getY()) + " above its bed's floor.");
            helper.succeed();
        });
    }

    private static long snowballsBy(GameTestHelper helper, AddonEntity shooter) {
        return helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                shooter.getBoundingBox().inflate(3))
            .stream().filter(shot -> shot.getOwner() == shooter).count();
    }

    private static boolean moved(GameTestHelper helper, AddonEntity rover, int x, int z) {
        BlockPos at = helper.absolutePos(new BlockPos(x, 0, z));
        return rover.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(at)) > 0.5;
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
        return pig(helper, STAND.east(APART));
    }

    private static Mob pig(GameTestHelper helper, BlockPos where) {
        return still(helper, "pig", where);
    }

    private static Mob still(GameTestHelper helper, String kind, BlockPos where) {
        Mob pig = (Mob) Platform.createMob(BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", kind)).orElseThrow(), helper.getLevel());
        BlockPos at = helper.absolutePos(where);
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
