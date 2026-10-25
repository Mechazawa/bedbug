package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.state.properties.SculkSensorPhase;
import net.minecraft.world.level.gameevent.GameEvent;

import java.util.List;

/**
 * Entity component fields read through `tp:sentinel`, whose groups each carry one component.
 *
 * Sounds are heard by `SentinelSoundClientGameTest`; what is asserted here is what the world can
 * show a server: a pose, a score, a block, a mob that appears or goes.
 */
public final class SentinelGameTests {
    private static final String SENTINEL = "tp:sentinel";

    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    /** Past the ten tick sensor pass and Java's goal cadence. */
    private static final int SLACK = 12;

    /** A sentinel with one group switched on, standing still, with its goals running. */
    private static AddonEntity sentinel(GameTestHelper helper, String group, BlockPos where) {
        AddonEntity sentinel = BehaviourGameTests.spawn(helper, SENTINEL, where);
        if (sentinel == null) return null;

        sentinel.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        if (group != null) {
            EntityEvents.trigger(sentinel, "tp:sentinel_make_" + group, null, ServerHooks.state());
        }
        sentinel.setNoAi(false);
        return sentinel;
    }

    private static Mob pig(GameTestHelper helper, BlockPos where) {
        Mob pig = (Mob) Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "pig")).orElseThrow(),
            helper.getLevel());
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        helper.getLevel().addFreshEntity(pig);
        pig.setNoAi(true);
        return pig;
    }

    /**
     * A movement the component switches off reaches no listener: a sculk sensor beside a quiet
     * sentinel stays still, and wakes for a sentinel that declared nothing.
     */
    private static void silence(GameTestHelper helper, String group, Holder<GameEvent> movement) {
        BlockPos sensor = new BlockPos(3, 0, 1);
        AddonEntity quiet = sentinel(helper, group, STAND);
        if (quiet == null) {
            helper.succeed();
            return;
        }

        AddonEntity plain = sentinel(helper, null, new BlockPos(1, 0, 5));
        helper.startSequence()
            .thenExecuteAfter(SLACK, () -> helper.setBlock(sensor, Blocks.SCULK_SENSOR))
            .thenExecuteAfter(60, () -> quiet.gameEvent(movement, quiet))
            .thenExecuteAfter(30, () -> helper.assertTrue(
                SculkSensorBlock.getPhase(helper.getBlockState(sensor)) == SculkSensorPhase.INACTIVE,
                SENTINEL + " switched off " + movement.getRegisteredName() + " and the sensor still heard it."))
            .thenExecuteAfter(30, () -> plain.gameEvent(movement, plain))
            .thenWaitUntil(() -> helper.assertTrue(
                SculkSensorBlock.getPhase(helper.getBlockState(sensor)) != SculkSensorPhase.INACTIVE,
                SENTINEL + " declared nothing and the sensor did not hear " + movement.getRegisteredName() + "."))
            .thenSucceed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void aStepSwitchedOffByMovementTrackingIsNotHeard(GameTestHelper helper) {
        silence(helper, "quiet", GameEvent.STEP);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void aSwimSwitchedOffByMovementTrackingIsNotHeard(GameTestHelper helper) {
        silence(helper, "quiet", GameEvent.SWIM);
    }

    /** A player stands `across` blocks along the ground and `up` blocks above a sniffer, which sniffs at once. */
    private static void sniff(GameTestHelper helper, double across, double up, boolean angered) {
        AddonEntity sentinel = sentinel(helper, "sniffing", STAND);
        if (sentinel == null) {
            helper.succeed();
            return;
        }

        Player player = helper.makeMockServerPlayerInLevel();
        Platform.placeAt(player, sentinel.getX() + across, sentinel.getY() + up, sentinel.getZ(), 0f, 0f);
        player.setNoGravity(true);
        helper.startSequence()
            .thenExecuteAfter(60, () -> {
                Object level = sentinel.value("anger_level");
                boolean raised = level instanceof Number number && number.doubleValue() > 0;
                helper.assertTrue(raised == angered, SENTINEL + (angered
                    ? " sniffed a player inside its suspicion box and was not angered."
                    : " sniffed a player outside its suspicion box and was angered."));
            })
            .thenSucceed();
    }

    /** The box is three across and two up, so a player two across and one up is inside it. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aSniffAngersAPlayerInsideItsSuspicionBox(GameTestHelper helper) {
        sniff(helper, 2, 1, true);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aSniffIgnoresAPlayerBeyondItsHorizontalRadius(GameTestHelper helper) {
        sniff(helper, 4, 0, false);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aSniffIgnoresAPlayerBeyondItsVerticalRadius(GameTestHelper helper) {
        sniff(helper, 1, 4, false);
    }

    /**
     * A pig runs past every tick, which is a vibration, a sentinel digging waits an idle second for
     * the quiet it is given, and one that counts the disturbance never gets it.
     */
    private static void dig(GameTestHelper helper, String group, boolean digs) {
        AddonEntity sentinel = sentinel(helper, group, STAND);
        if (sentinel == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, new BlockPos(4, 0, 1));
        BlockPos at = pig.blockPosition();
        helper.onEachTick(() -> {
            Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
            pig.setDeltaMovement(0.5, 0, 0);
        });
        boolean[] dug = {false};
        helper.onEachTick(() -> dug[0] |= sentinel.getPose() == Pose.DIGGING);
        if (digs) {
            helper.succeedWhen(() -> helper.assertTrue(dug[0],
                SENTINEL + " never dug although nothing it counts disturbed it."));
            return;
        }
        helper.startSequence()
            .thenExecuteAfter(100, () -> helper.assertFalse(dug[0],
                SENTINEL + " dug while something it counts as a disturbance kept happening."))
            .thenSucceed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aDiggerIgnoresVibrationsUnlessItCountsThem(GameTestHelper helper) {
        dig(helper, "digging_calm", true);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aDiggerCountingVibrationsAsDisturbancesNeverDigsAmongThem(GameTestHelper helper) {
        dig(helper, "digging_vibes", false);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aDiggerCountingSuspicionAsADisturbanceNeverDigsAmongIt(GameTestHelper helper) {
        dig(helper, "digging_suspicious", false);
    }

    private static void provoke(GameTestHelper helper, AddonEntity sentinel, Mob attacker) {
        sentinel.setTarget(attacker);
        Platform.hurt(sentinel, helper.getLevel(), sentinel.damageSources().mobAttack(attacker), 1f);
    }

    /**
     * The anger falls a point every second and a half from its threshold, and a sentinel that removes
     * its targets below the threshold lets go of the one it had; the stoic one keeps it.
     */
    private static void cooling(GameTestHelper helper, String identifier, boolean letsGo) {
        AddonEntity sentinel = BehaviourGameTests.spawn(helper, identifier, STAND);
        if (sentinel == null) {
            helper.succeed();
            return;
        }

        Mob attacker = pig(helper, new BlockPos(3, 0, 1));
        helper.startSequence()
            .thenExecuteAfter(2, () -> provoke(helper, sentinel, attacker))
            .thenWaitUntil(() -> helper.assertTrue(sentinel.isAngry() && sentinel.getTarget() == attacker,
                identifier + " was provoked past its threshold and is not angry at the pig."))
            .thenExecuteAfter(80, () -> {
                helper.assertFalse(sentinel.isAngry(), identifier + " is still angry after its anger decayed.");
                helper.assertTrue((sentinel.getTarget() == null) == letsGo, identifier + (letsGo
                    ? " fell below its angry threshold and kept its target."
                    : " fell below its angry threshold and lost a target it was told to keep."));
            })
            .thenSucceed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void anEntityFallingBelowItsAngryThresholdLosesItsTarget(GameTestHelper helper) {
        cooling(helper, SENTINEL, true);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void anEntityToldToKeepItsTargetsKeepsThemBelowTheAngryThreshold(GameTestHelper helper) {
        cooling(helper, "tp:sentinel_stoic", false);
    }

    private static List<Mob> chickens(GameTestHelper helper, AddonEntity around, double radius) {
        return helper.getLevel().getEntitiesOfClass(Mob.class, around.getBoundingBox().inflate(radius),
            mob -> BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath().equals("chicken"));
    }

    /**
     * A summoning spell puts its entities in a ring of `size` blocks one `delay_per_summon` after
     * another, and each one goes after `entity_lifespan`.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void aSummonedRingAppearsOneByOneAndGoesAfterItsLifespan(GameTestHelper helper) {
        AddonEntity caster = sentinel(helper, "summoning", STAND);
        if (caster == null) {
            helper.succeed();
            return;
        }

        caster.setTarget(pig(helper, new BlockPos(5, 0, 5)));
        int[] most = {0};
        helper.onEachTick(() -> most[0] = Math.max(most[0], chickens(helper, caster, 3).size()));
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(chickens(helper, caster, 3).size() == 4,
                SENTINEL + " has not put four chickens in its ring."))
            .thenExecute(() -> chickens(helper, caster, 3).forEach(chicken -> {
                double ring = Math.hypot(chicken.getX() - caster.getX(), chicken.getZ() - caster.getZ());
                helper.assertTrue(Math.abs(ring - 2) < 1, SENTINEL + " summons on a ring of two blocks and put one "
                    + ring + " away.");
            }))
            .thenWaitUntil(() -> helper.assertTrue(chickens(helper, caster, 6).isEmpty(),
                SENTINEL + " summons for three seconds and its chickens are still there."))
            .thenSucceed();
    }

    /** Two of the four are put down, because `summon_cap` counts those standing within the cap's radius. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aSummonStopsAtItsCap(GameTestHelper helper) {
        AddonEntity caster = sentinel(helper, "summoning_capped", STAND);
        if (caster == null) {
            helper.succeed();
            return;
        }

        caster.setTarget(pig(helper, new BlockPos(5, 0, 5)));
        helper.startSequence()
            .thenExecuteAfter(100, () -> helper.assertTrue(chickens(helper, caster, 8).size() == 2,
                SENTINEL + " caps its summons at two and made " + chickens(helper, caster, 8).size() + "."))
            .thenSucceed();
    }

    /** An event spell is sent at the target, which is the one that answers it. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void anEventSpellIsSentToTheTarget(GameTestHelper helper) {
        AddonEntity caster = sentinel(helper, "sending", STAND);
        AddonEntity victim = BehaviourGameTests.spawn(helper, SENTINEL, new BlockPos(4, 0, 1));
        if (caster == null || victim == null) {
            helper.succeed();
            return;
        }

        caster.setTarget(victim);
        helper.succeedWhen(() -> {
            helper.assertTrue(Boolean.TRUE.equals(victim.value("tp:marked")),
                SENTINEL + " sent its event and the target did not get it.");
            helper.assertFalse(Boolean.TRUE.equals(caster.value("tp:marked")), SENTINEL + " sent its event to itself.");
        });
    }

    /** `protection_slot` names the slot whose item keeps the sun off, and the head is the default. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void aSunburntEntityIsProtectedByTheSlotItsPackNames(GameTestHelper helper) {
        AddonEntity named = sentinel(helper, "sunburnt", STAND);
        AddonEntity plain = sentinel(helper, "sunburnt_plain", new BlockPos(1, 0, 4));
        if (named == null || plain == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(named.protectionSlot() == EquipmentSlot.CHEST,
            SENTINEL + " names the chest and was protected by "
            + named.protectionSlot() + ".");
        helper.assertTrue(plain.protectionSlot() == EquipmentSlot.HEAD,
            SENTINEL + " names no slot and was protected by "
            + plain.protectionSlot() + ".");
        helper.succeed();
    }

    /**
     * A drying timer fires `dried_out_event` once its `total_time` has run, and a splash water bottle
     * landing on the entity takes `water_bottle_refill_time` off the count, which for one that had
     * dried out raises `recover_after_dried_out_event`.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
    public void aSplashWaterBottleRefillsADriedOutEntity(GameTestHelper helper) {
        AddonEntity dry = sentinel(helper, "drying", STAND);
        if (dry == null) {
            helper.succeed();
            return;
        }

        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(Boolean.TRUE.equals(dry.value("tp:dried")),
                SENTINEL + " has been out of water for its whole time and did not dry out."))
            .thenExecute(() -> splash(helper, dry))
            .thenWaitUntil(() -> helper.assertTrue(Boolean.TRUE.equals(dry.value("tp:recovered")),
                SENTINEL + " dried out and a splash water bottle on it did not raise its recovery."))
            .thenSucceed();
    }

    private static void splash(GameTestHelper helper, AddonEntity target) {
        //? if >=26 {
        net.minecraft.world.item.ItemStack bottle = net.minecraft.world.item.alchemy.PotionContents
            .createItemStack(net.minecraft.world.item.Items.SPLASH_POTION,
                net.minecraft.world.item.alchemy.Potions.WATER);
        var potion = new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion(
            helper.getLevel(), target.getX(), target.getY() + 1, target.getZ(), bottle);
        helper.getLevel().addFreshEntity(potion);
        //?}
    }

    /**
     * A door in the way is broken after `break_time` where the world's difficulty reaches
     * `min_difficulty`, and the same door stands against one that wants a harder world.
     */
    private static void door(GameTestHelper helper, String group, boolean breaks) {
        for (int z = -4; z <= 12; z++) {
            for (int y = 0; y <= 3; y++) helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
        }
        BlockPos door = new BlockPos(3, 0, 3);
        var closed = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.EAST);
        helper.setBlock(door, closed);
        helper.setBlock(door.above(), closed.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        AddonEntity breaker = sentinel(helper, group, new BlockPos(1, 0, 3));
        if (breaker == null) {
            helper.succeed();
            return;
        }

        breaker.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.25);
        BlockPos beyond = helper.absolutePos(new BlockPos(6, 0, 3));
        helper.onEachTick(() -> {
            if (!breaker.getNavigation().isDone()) return;

            breaker.getNavigation().moveTo(beyond.getX() + 0.5, beyond.getY(), beyond.getZ() + 0.5, 1.0);
        });
        if (breaks) {
            helper.succeedWhen(() -> helper.assertTrue(!(helper.getBlockState(door).getBlock()
                instanceof net.minecraft.world.level.block.DoorBlock), SENTINEL + " has not broken the door."));
            return;
        }
        helper.startSequence()
            .thenExecuteAfter(150, () -> helper.assertTrue(helper.getBlockState(door).getBlock()
                instanceof net.minecraft.world.level.block.DoorBlock, SENTINEL + " broke a door below its difficulty."))
            .thenSucceed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aDoorIsBrokenInTheBreakTimeThePackNames(GameTestHelper helper) {
        door(helper, "door_breaking", true);
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void aDoorStandsBelowTheMinimumDifficulty(GameTestHelper helper) {
        if (helper.getLevel().getDifficulty() == Difficulty.HARD) {
            helper.succeed();
            return;
        }
        door(helper, "door_breaking_hard", false);
    }
}
