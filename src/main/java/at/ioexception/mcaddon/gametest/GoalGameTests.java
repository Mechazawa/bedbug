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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * The fields inside a pack's behaviours that decide how a goal plays out, read back off the world.
 *
 * `tp:brute` is a plain mob that hits for one point and is not immune to fire. Each of its groups
 * carries one behaviour with the fields a test here asks about, and the brute is held where it
 * stands, so whatever reaches a pig standing apart from it was reached by the goal and not by a walk.
 */
public final class GoalGameTests {
    private static final String BRUTE = "tp:brute";

    /** On the empty structure's floor, where a mob with its goals off stays as well as one with them on. */
    private static final BlockPos STAND = new BlockPos(1, 0, 1);

    /** How long a test waiting on a goal may run, with room for Java's second between goal checks. */
    private static final int GOAL_TICKS = 120;

    /**
     * A melee blow waits out the `cooldown_time` its pack states, in seconds, before the next.
     *
     * The boxing group waits a second and a half; Java's own goal swings once a second whatever it
     * is told, so the second blow lands ten ticks early there.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aMeleeBlowWaitsTheCooldownItsPackStates(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_box");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(1), BLOWS_TO_KILL);
        brute.setTarget(pig);
        List<Long> blows = new ArrayList<>();
        float[] health = {pig.getHealth()};
        helper.onEachTick(() -> {
            if (pig.getHealth() < health[0]) blows.add(helper.getLevel().getGameTime());
            health[0] = pig.getHealth();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(blows.size() >= 2, BRUTE + " struck the pig " + blows.size() + " times.");
            long gap = blows.get(1) - blows.get(0);
            helper.assertTrue(Math.abs(gap - BOXING_COOLDOWN) <= 1,
                BRUTE + " waits a second and a half between blows and struck again after " + gap + " ticks.");
        });
    }

    /** The second and a half the boxing group waits between blows, in Java's ticks. */
    private static final int BOXING_COOLDOWN = 30;

    /** Enough health that the pig outlives every blow a test waits for. */
    private static final float BLOWS_TO_KILL = 20f;

    /**
     * A box attack reaches as far as `horizontal_reach` grows the attacker's box, and the blow and
     * the kill raise the events `on_attack` and `on_kill` name.
     *
     * The pig stands two blocks off, past the reach Java gives every mob, and a single blow kills it.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aBoxAttackReachesItsStatedReachAndRaisesItsEvents(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_box");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(2), 1f);
        brute.setTarget(pig);
        helper.succeedWhen(() -> {
            helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:struck")),
                BRUTE + " reaches a block and a half past its box and never struck the pig two blocks off.");
            helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:felled")),
                BRUTE + " killed the pig and never raised the event on_kill names.");
        });
    }

    /**
     * A melee attack reaches `reach_multiplier` times the attacker's own width.
     *
     * The reaching group says six, which puts a pig three blocks off in reach; Java's own reach falls
     * short of it by more than a block.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aMeleeAttackReachesItsMultipleOfItsWidth(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_reach");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(3), BLOWS_TO_KILL);
        brute.setTarget(pig);
        helper.succeedWhen(() -> helper.assertTrue(pig.getHealth() < BLOWS_TO_KILL,
            BRUTE + " reaches six times its width and never struck the pig three blocks off."));
    }

    /**
     * A burning attacker with `can_spread_on_fire` sets what it hits alight.
     *
     * Java writes the same into its own zombie alone, so a plain mob passes on nothing.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aBurningAttackerSetsItsTargetAlight(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_burn");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(1), BLOWS_TO_KILL);
        brute.setRemainingFireTicks(BURNING);
        brute.setTarget(pig);
        helper.succeedWhen(() -> {
            helper.assertTrue(pig.getHealth() < BLOWS_TO_KILL, BRUTE + " never struck the pig beside it.");
            helper.assertTrue(pig.getRemainingFireTicks() > 0,
                BRUTE + " struck the pig while burning and the pig is not alight.");
        });
    }

    /** Long enough that the brute is still alight when its first blow lands. */
    private static final int BURNING = 200;

    /**
     * A panic starts at a cause `damage_sources` lists and not at a blow `ignore_mob_damage` leaves
     * out, even one whose cause is on the list.
     *
     * The skittish group lists falling, which no panic of Java's starts at, and an attack, while
     * ignoring every mob; Java panics at a mob's blow and never at a fall. The brute has no other
     * goal that moves it, so a path in progress is the panic.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aPanicStartsAtTheCausesItsPackLists(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_skit");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(3), BLOWS_TO_KILL);
        Platform.hurt(brute, helper.getLevel(), brute.damageSources().mobAttack(pig), 1f);
        helper.startSequence()
            .thenExecuteAfter(SETTLE_TICKS, () -> {
                helper.assertFalse(brute.getNavigation().isInProgress(),
                    BRUTE + " ignores every mob's blow and ran from the pig's.");
                brute.invulnerableTime = 0;
                Platform.hurt(brute, helper.getLevel(), brute.damageSources().fall(), 1f);
            })
            .thenWaitUntil(() -> helper.assertTrue(brute.getNavigation().isInProgress(),
                BRUTE + " panics at a fall and stood where it fell."))
            .thenSucceed();
    }

    /** Long enough for a goal checked every other tick to have started had it been going to. */
    private static final int SETTLE_TICKS = 10;

    /**
     * A panic with `force` set starts with nothing having hurt the entity, and keeps the entity
     * running for as long as it lasts.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aForcedPanicNeedsNoHurt(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_bolt");
        if (brute == null) {
            helper.succeed();
            return;
        }

        int[] running = {0};
        int[] since = {-1};
        helper.onEachTick(() -> {
            boolean moving = brute.getNavigation().isInProgress();
            if (moving && since[0] < 0) since[0] = 0;
            if (since[0] < 0) return;
            since[0]++;
            if (moving) running[0]++;
        });
        helper.runAfterDelay(GOAL_TICKS - 20, () -> {
            helper.assertTrue(since[0] > 0, BRUTE + " panics by force and never ran.");
            helper.assertTrue(running[0] * 4 >= since[0] * 3, BRUTE + " panics by force and ran for "
                + running[0] + " of the " + since[0] + " ticks since it set off.");
            helper.succeed();
        });
    }

    /**
     * A panic with `prefer_water` heads for water within reach whatever hurt the entity.
     *
     * Java's own goal heads for water only while the entity burns, and otherwise runs anywhere.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aPanicThatPrefersWaterHeadsForIt(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_wade");
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(STAND.east(3), Blocks.WATER);
        Mob pig = pig(helper, STAND.south(3), BLOWS_TO_KILL);
        Platform.hurt(brute, helper.getLevel(), brute.damageSources().mobAttack(pig), 1f);
        helper.succeedWhen(() -> {
            BlockPos heading = brute.getNavigation().getTargetPos();
            helper.assertTrue(heading != null
                    && helper.getLevel().getFluidState(heading).is(net.minecraft.tags.FluidTags.WATER),
                BRUTE + " prefers water when it panics and ran for " + heading + ".");
        });
    }

    /**
     * An entity fleeing a block raises `on_escape` once the block is out of the box it searches.
     *
     * The shy group flees gold within three blocks, and the gold stands beside the brute in the
     * middle of the floor, which leaves room to run whichever way it picks.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = ESCAPE_TICKS)
    public void anEntityFleeingABlockRaisesItsEscapeOnceClear(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_shy_away", MIDDLE);
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(MIDDLE.east(1), Blocks.GOLD_BLOCK);
        helper.succeedWhen(() -> helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:escaped")),
            BRUTE + " fled the gold beside it and never raised the event on_escape names."));
    }

    /** Room for a few runs away, in case the first one stops short of the edge of the search. */
    private static final int ESCAPE_TICKS = 240;

    private static final BlockPos MIDDLE = new BlockPos(4, 0, 4);

    /**
     * A teleporting entity with a target further off than `target_distance` jumps that far towards
     * it, and a group bringing the component in is enough for that.
     *
     * The blinking group jumps three blocks; the brute's feet are held, so only a jump closes on the
     * pig six blocks off.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aTeleportClosesOnAFarTarget(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_blink");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(6), BLOWS_TO_KILL);
        brute.setTarget(pig);
        helper.succeedWhen(() -> helper.assertTrue(brute.distanceTo(pig) < 4,
            BRUTE + " teleports towards a far target and is still " + brute.distanceTo(pig) + " blocks off."));
    }

    /**
     * An entity with `teleports_on_projectile_hit` blinks away from a projectile, which then does it
     * no harm.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aTeleportDodgesAProjectile(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_blink");
        if (brute == null) {
            helper.succeed();
            return;
        }

        float health = brute.getHealth();
        Platform.hurt(brute, helper.getLevel(), brute.damageSources().thrown(null, null), 1f);
        helper.assertTrue(brute.getHealth() == health,
            BRUTE + " blinks away from projectiles and a thrown one took it from " + health + " to "
                + brute.getHealth() + ".");
        helper.succeed();
    }

    /**
     * A bred child takes what `minecraft:offspring` says it takes from its parents: their owner, their
     * variant, a property `property_inheritance` names, and the dye their two colours make together.
     *
     * Both parents carry the same variant and the same property, so either parent passing them on
     * gives the same answer; a red and a white parent make a pink child under
     * `combine_parent_colors`. Both parents are at the top of the ten to thirty health the breeding
     * group states, so a blended child lands within three of thirty, where its own kind starts at
     * twenty.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = BREED_TICKS)
    public void aBredChildTakesWhatItsParentsPassOn(GameTestHelper helper) {
        java.util.function.Supplier<AddonEntity> born = breed(helper, "tp:brute_breed");
        if (born == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            AddonEntity child = born.get();
            helper.assertTrue(child != null, BRUTE + " was fed in pairs and no child was born.");
            helper.assertTrue(child.ownerUuid() != null, BRUTE + " was born to tamed parents and belongs to nobody.");
            helper.assertTrue(number(child.value("variant")) == PARENT_VARIANT,
                BRUTE + " was born to parents of variant three and is variant " + child.value("variant") + ".");
            helper.assertTrue(number(child.value("tp:hue")) == PARENT_HUE,
                BRUTE + " inherits tp:hue from parents holding five and holds " + child.value("tp:hue") + ".");
            helper.assertTrue(number(child.value("color")) == PINK,
                BRUTE + " was born to a red and a white parent and is colour " + child.value("color") + ".");
            helper.assertTrue(child.getMaxHealth() >= BLENDED_HEALTH_FLOOR,
                BRUTE + " blends its health from two parents at thirty and was born with "
                    + child.getMaxHealth() + ".");
        });
    }

    /**
     * A bred child arrives with `minecraft:entity_born`, and only a placed entity with
     * `minecraft:entity_spawned`. A pack sets a child's young state from the first, and a spawned
     * one's from the second, which often rolls for a grown adult.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = BREED_TICKS)
    public void aBredChildIsBornRatherThanSpawned(GameTestHelper helper) {
        java.util.function.Supplier<AddonEntity> born = breed(helper, "tp:brute_breed");
        if (born == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            AddonEntity child = born.get();
            helper.assertTrue(child != null, BRUTE + " was fed in pairs and no child was born.");
            helper.assertTrue("born".equals(child.value("tp:arrived")),
                BRUTE + " was bred and arrived " + child.value("tp:arrived") + " rather than born.");
        });
    }

    /**
     * A group's `minecraft:behavior.stomp_turtle_egg` looks for an egg as often as its `interval`
     * says, here every tick.
     *
     * The egg is laid a second after the brute first looked and found nothing. Java's own goal waits
     * ten to twenty seconds before looking again, so the egg is still whole when the test ends.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = STOMP_TICKS)
    public void aStomperLooksForEggsAsOftenAsItsGroupSays(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_stomp");
        if (brute == null) {
            helper.succeed();
            return;
        }

        AtomicBoolean laid = new AtomicBoolean();
        helper.runAfterDelay(EGG_LAID_AFTER, () -> {
            helper.setBlock(EGG, Blocks.TURTLE_EGG);
            laid.set(true);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(laid.get(), "The egg is not laid yet.");
            helper.assertTrue(helper.getBlockState(EGG).isAir(),
                BRUTE + " looks for eggs every tick and left the one laid four blocks off whole.");
        });
    }

    /**
     * `minecraft:behavior.eat_block` eats the block its pairs name under the entity, as often as its
     * `success_chance` says, leaves the replacement and raises `on_eat`.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aGrazerEatsTheBlockItsPairsName(GameTestHelper helper) {
        helper.setBlock(STAND.below(), Blocks.HAY_BLOCK);
        AddonEntity brute = withGroup(helper, "tp:brute_graze");
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.DIRT, STAND.below());
            helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:grazed")),
                BRUTE + " ate the hay and never raised on_eat.");
        });
    }

    /**
     * `minecraft:behavior.snacking` eats only the `items` it names, stops at once on a stop chance of
     * one, and waits out its cooldown before it snacks again.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = SNACK_TICKS)
    public void aSnackerEatsOnlyItsItemsAndThenWaits(GameTestHelper helper) {
        AddonEntity snacker = withGroup(helper, "tp:brute_snack");
        AddonEntity choosy = withGroup(helper, "tp:brute_snack", FAR_CORNER);
        if (snacker == null) {
            helper.succeed();
            return;
        }

        snacker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SWEET_BERRIES));
        choosy.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BREAD));
        helper.runAfterDelay(SNACKED_BY, () -> {
            helper.assertTrue(snacker.getMainHandItem().isEmpty(),
                BRUTE + " stops snacking on a stop chance of one and still holds its berries.");
            snacker.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SWEET_BERRIES));
        });
        helper.runAfterDelay(SNACK_TICKS - 10, () -> {
            helper.assertFalse(snacker.getMainHandItem().isEmpty(),
                BRUTE + " snacked again inside a cooldown of a thousand seconds.");
            helper.assertFalse(choosy.getMainHandItem().isEmpty(),
                BRUTE + " snacked on bread, which its items do not name.");
            helper.succeed();
        });
    }

    /** Long past Java's three seconds of holding food before eating it. */
    private static final int SNACK_TICKS = 140;
    private static final int SNACKED_BY = 20;

    /**
     * `minecraft:behavior.lay_egg` lays only where the material above the block is one its
     * `target_materials_above_block` names, so the nearer sand under a snow layer is passed over.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aLayerLaysOnlyUnderTheMaterialItNames(GameTestHelper helper) {
        helper.setBlock(STAND.east(2).below(), Blocks.SAND);
        helper.setBlock(STAND.east(2), Blocks.SNOW);
        helper.setBlock(STAND.south(4).below(), Blocks.SAND);
        AddonEntity brute = withGroup(helper, "tp:brute_nest");
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            helper.assertBlockPresent(Blocks.TURTLE_EGG, STAND.south(4));
            helper.assertBlockNotPresent(Blocks.TURTLE_EGG, STAND.east(2));
        });
    }

    /**
     * `minecraft:behavior.take_flower` takes a golem's flower only while its `filters` hold, and
     * raises `on_take_flower` when it does.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLOWER_TICKS)
    public void aFlowerTakerTakesOnlyWhileItsFiltersHold(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_take_flowers");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Entity golem = Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "iron_golem")).orElseThrow(),
            helper.getLevel());
        BlockPos at = helper.absolutePos(STAND.east(4));
        Platform.placeAt(golem, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        ((Mob) golem).setNoAi(true);
        helper.getLevel().addFreshEntity(golem);
        Platform.offerFlower(golem, true);

        AtomicBoolean befriended = new AtomicBoolean();
        helper.runAfterDelay(FLOWER_REFUSED_FOR, () -> {
            helper.assertTrue(Platform.offersFlower(golem),
                BRUTE + " took the flower while its filters said no.");
            brute.addTag("tp_flower_friend");
            befriended.set(true);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(befriended.get(), BRUTE + " is not allowed the flower yet.");
            helper.assertTrue(!Platform.offersFlower(golem),
                BRUTE + " never took the flower once its filters allowed it.");
            helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:took_flower")),
                BRUTE + " took the flower and never raised on_take_flower.");
        });
    }

    private static final int FLOWER_REFUSED_FOR = 60;
    private static final int FLOWER_TICKS = 200;

    /**
     * `minecraft:behavior.celebrate_survive` sends up fireworks at its `fireworks_interval` for as
     * long as its group is on, with no raid anywhere, and raises `on_celebration_end_event` after its
     * `duration`.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aSurvivorCelebratesWithFireworksWhileItsGroupIsOn(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_survive");
        if (brute == null) {
            helper.succeed();
            return;
        }

        AtomicBoolean launched = new AtomicBoolean();
        helper.onEachTick(() -> launched.compareAndSet(false, !helper.getLevel().getEntitiesOfClass(
            net.minecraft.world.entity.projectile.FireworkRocketEntity.class,
            brute.getBoundingBox().inflate(8)).isEmpty()));
        helper.succeedWhen(() -> {
            helper.assertTrue(launched.get(), BRUTE + " celebrated and sent up no firework.");
            helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:celebrated")),
                BRUTE + " celebrated for its second and never raised on_celebration_end_event.");
        });
    }

    /**
     * `minecraft:equip_item`'s `can_wear_armor` set false leaves a helmet lying at the entity's feet,
     * while a brute whose own group says nothing puts one on.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void anEntityThatCannotWearArmourLeavesAHelmetLying(GameTestHelper helper) {
        AddonEntity dresser = withGroup(helper, "tp:brute_dress");
        AddonEntity plain = withGroup(helper, "tp:brute_go_unarmoured", FAR_CORNER);
        if (dresser == null) {
            helper.succeed();
            return;
        }

        helper.spawnItem(net.minecraft.world.item.Items.IRON_HELMET, STAND);
        helper.spawnItem(net.minecraft.world.item.Items.IRON_HELMET, FAR_CORNER);
        helper.runAfterDelay(GOAL_TICKS - 10, () -> {
            helper.assertFalse(dresser.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty(),
                BRUTE + " can wear armour and never put the helmet on.");
            helper.assertTrue(plain.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty(),
                BRUTE + " put on a helmet though its can_wear_armor is false.");
            helper.succeed();
        });
    }

    /**
     * `minecraft:celebrate_hunt` with `broadcast` off lets the hunter credited with the kill
     * celebrate and leaves the hunter beside it quiet.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aHuntThatIsNotBroadcastIsCelebratedByItsKillerAlone(GameTestHelper helper) {
        AddonEntity killer = withGroup(helper, "tp:brute_hunt_alone");
        AddonEntity bystander = withGroup(helper, "tp:brute_hunt_alone", STAND.east(2));
        if (killer == null) {
            helper.succeed();
            return;
        }

        Mob quarry = pig(helper, MIDDLE, 1);
        Platform.hurt(quarry, helper.getLevel(), quarry.damageSources().mobAttack(killer), KILLING_BLOW);
        helper.succeedWhen(() -> {
            helper.assertTrue(Boolean.TRUE.equals(killer.value("is_celebrating")),
                BRUTE + " killed the pig and is not celebrating.");
            helper.assertFalse(Boolean.TRUE.equals(bystander.value("is_celebrating")),
                BRUTE + " celebrates a kill it did not make, which its hunter did not broadcast.");
        });
    }

    /** Far more than the pig's one health. */
    private static final float KILLING_BLOW = 20;

    /** `minecraft:admire_item`'s `duration` is how long the entity turns over what it holds. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = ADMIRE_TICKS)
    public void anAdmirerAdmiresForItsDuration(GameTestHelper helper) {
        AddonEntity brute = admirer(helper);
        if (brute == null) {
            helper.succeed();
            return;
        }

        AtomicBoolean admired = new AtomicBoolean();
        helper.onEachTick(() -> admired.compareAndSet(false, Boolean.TRUE.equals(brute.value("is_admiring"))));
        helper.runAfterDelay(ADMIRE_TICKS - 10, () -> {
            helper.assertTrue(admired.get(), BRUTE + " never admired the gold it was handed.");
            helper.assertFalse(Boolean.TRUE.equals(brute.value("is_admiring")),
                BRUTE + " is still admiring long after the second its admire_item allows.");
            helper.succeed();
        });
    }

    /** An entity hurt inside `minecraft:admire_item`'s `cooldown_after_being_attacked` admires nothing. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = ADMIRE_TICKS)
    public void anAdmirerHurtRecentlyAdmiresNothing(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_admire");
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.runAfterDelay(1, () -> Platform.hurt(brute, helper.getLevel(), brute.damageSources().generic(), 1));
        helper.runAfterDelay(3, () -> brute.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT)));
        helper.onEachTick(() -> helper.assertFalse(Boolean.TRUE.equals(brute.value("is_admiring")),
            BRUTE + " admired gold a moment after it was hurt."));
        helper.runAfterDelay(ADMIRE_TICKS - 10, helper::succeed);
    }

    /** An entity hurt inside `minecraft:barter`'s `cooldown_after_being_attacked` pays nobody. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = ADMIRE_TICKS)
    public void aBarterHurtRecentlyPaysNobody(GameTestHelper helper) {
        AddonEntity brute = admirer(helper);
        if (brute == null) {
            helper.succeed();
            return;
        }

        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Platform.placeAt(player, brute.getX() + 2, brute.getY(), brute.getZ(), 0f, 0f);
        helper.runAfterDelay(HURT_WHILE_ADMIRING,
            () -> Platform.hurt(brute, helper.getLevel(), brute.damageSources().generic(), 1));
        helper.runAfterDelay(ADMIRE_TICKS - 10, () -> {
            helper.getLevel().getServer().getPlayerList().remove(player);
            helper.assertFalse(brute.getMainHandItem().isEmpty(),
                BRUTE + " paid for its gold a second after it was hurt.");
            helper.succeed();
        });
    }

    /** A brute with its admiring group on, handed a gold ingot. */
    private static AddonEntity admirer(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_admire");
        if (brute != null) {
            brute.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT));
        }
        return brute;
    }

    /** Well past the brute's one second of admiring. */
    private static final int ADMIRE_TICKS = 90;
    private static final int HURT_WHILE_ADMIRING = 10;

    /** A stomper whose `search_range` is two blocks does not set off for an egg four blocks off. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = STOMP_TICKS)
    public void aStomperLooksForEggsNoFurtherThanItsRange(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_stomp_near");
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(EGG, Blocks.TURTLE_EGG);
        double start = brute.getX();
        helper.onEachTick(() -> helper.assertTrue(brute.getX() - start < 1,
            BRUTE + " walked towards an egg twice as far off as its search_range."));
        helper.runAfterDelay(STOMP_TICKS - 10, helper::succeed);
    }

    /**
     * `minecraft:behavior.explore_outskirts` walks out past the edge of the village on the side the
     * entity stands, `explore_dist` beyond it.
     *
     * The village is one bell three blocks south of the brute, so its edge is the bell's north face
     * and the brute walks five blocks north of that, straight away from the bell.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void anExplorerWalksOutPastTheVillageEdge(GameTestHelper helper) {
        helper.setBlock(VILLAGE_BELL, Blocks.BELL);
        AddonEntity brute = withGroup(helper, "tp:brute_explore", MIDDLE);
        if (brute == null) {
            helper.succeed();
            return;
        }

        BlockPos bell = helper.absolutePos(VILLAGE_BELL);
        helper.succeedWhen(() -> {
            double north = bell.getZ() - brute.getZ();
            helper.assertTrue(north >= OUTSKIRTS_REACHED,
                BRUTE + " stands " + north + " blocks north of the bell, short of the outskirts.");
            helper.assertTrue(Math.abs(brute.getX() - (bell.getX() + 0.5)) <= 1,
                BRUTE + " walked off sideways rather than straight out from the village.");
        });
    }

    /** The bell's north face, five blocks out, less the block the brute counts as arriving within. */
    private static final double OUTSKIRTS_REACHED = 4;
    private static final BlockPos VILLAGE_BELL = MIDDLE.south(3);

    /** `minecraft:behavior.stroll_towards_village` walks to the nearest place a village is made of. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aStrollerWalksToTheNearestVillage(GameTestHelper helper) {
        helper.setBlock(FAR_CORNER, Blocks.BELL);
        AddonEntity brute = withGroup(helper, "tp:brute_seek_village");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Vec3 bell = Vec3.atBottomCenterOf(helper.absolutePos(FAR_CORNER));
        helper.succeedWhen(() -> helper.assertTrue(brute.position().distanceTo(bell) <= NEAR_ENOUGH,
            BRUTE + " was sent to the village and stands " + brute.position().distanceTo(bell)
                + " blocks from its bell."));
    }

    private static final BlockPos FAR_CORNER = new BlockPos(6, 0, 6);
    /** The group's two block goal radius, and half a block for where the walk stops. */
    private static final double NEAR_ENOUGH = 2.5;

    /**
     * `minecraft:behavior.hide` stays put until a bell rings, then runs to the nearest bed.
     *
     * The brute is checked a second and a half in, before the bell rings, and then has to reach the
     * bed five blocks off.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aHiderRunsToBedWhenTheBellRings(GameTestHelper helper) {
        var headState = BuiltInRegistries.BLOCK.getOptional(Platform.id("minecraft", "red_bed")).orElseThrow()
            .defaultBlockState()
            .setValue(BedBlock.FACING, Direction.SOUTH);
        helper.setBlock(BED_HEAD, headState.setValue(BedBlock.PART,
            BedPart.HEAD));
        helper.setBlock(BED_HEAD.north(), headState.setValue(BedBlock.PART,
            BedPart.FOOT));
        helper.setBlock(ALARM_BELL, Blocks.BELL);
        AddonEntity brute = withGroup(helper, "tp:brute_hide");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Vec3 bed = Vec3.atBottomCenterOf(helper.absolutePos(BED_HEAD));
        AtomicBoolean rung = new AtomicBoolean();
        helper.runAfterDelay(QUIET_TICKS, () -> {
            helper.assertTrue(brute.position().distanceTo(bed) > NEAR_ENOUGH,
                BRUTE + " ran to bed before any bell rang.");
            ((BellBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(ALARM_BELL))).onHit(Direction.NORTH);
            rung.set(true);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(rung.get(), "The bell has not rung yet.");
            helper.assertTrue(brute.position().distanceTo(bed) <= NEAR_ENOUGH,
                BRUTE + " heard the bell and stands " + brute.position().distanceTo(bed) + " blocks from the bed.");
        });
    }

    private static final BlockPos BED_HEAD = STAND.east(5).south(1);
    private static final BlockPos ALARM_BELL = new BlockPos(1, 0, 6);
    private static final int QUIET_TICKS = 30;

    /** `minecraft:behavior.play` runs to the friend `friend_types` names and keeps up with it. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aPlayerRunsToItsFriend(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_play");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob friend = pig(helper, FAR_CORNER, BLOWS_TO_KILL);
        helper.succeedWhen(() -> helper.assertTrue(brute.distanceTo(friend) <= NEAR_ENOUGH,
            BRUTE + " plays with pigs and stays " + brute.distanceTo(friend) + " blocks from the one nearby."));
    }

    private static final BlockPos EGG = STAND.east(4);
    private static final int EGG_LAID_AFTER = 20;
    /** Room for the walk and the three seconds Java's goal stamps on an egg, short of its next look. */
    private static final int STOMP_TICKS = 220;

    /**
     * A child whose `mutation_factor` is certain takes neither parent's variant, mark variant nor
     * colour, and `mutation_strategy` set to random rolls its variants inside the intervals stated.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = BREED_TICKS)
    public void aBredChildMutatesTheWayItsPackStates(GameTestHelper helper) {
        java.util.function.Supplier<AddonEntity> born = breed(helper, "tp:brute_breed_mutating");
        if (born == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            AddonEntity child = born.get();
            helper.assertTrue(child != null, BRUTE + " was fed in pairs and no child was born.");
            helper.assertTrue(number(child.value("variant")) == MUTATED_VARIANT,
                BRUTE + " always mutates into variant seven and is variant " + child.value("variant") + ".");
            helper.assertTrue(number(child.value("mark_variant")) == MUTATED_MARK,
                BRUTE + " always mutates into mark variant two and is " + child.value("mark_variant") + ".");
            helper.assertTrue(child.value("color") == null,
                BRUTE + " always keeps the colour it was born with and took " + child.value("color") + ".");
        });
    }

    /**
     * Parents sharing a variant have it denied to their child at the chance `deny_parents_variant`
     * states, which then rolls inside the range it names; a child of `inherit_tamed` false belongs
     * to nobody whoever owns its parents.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = BREED_TICKS)
    public void aBredChildIsDeniedItsParentsSharedVariant(GameTestHelper helper) {
        java.util.function.Supplier<AddonEntity> born = breed(helper, "tp:brute_breed_denying");
        if (born == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            AddonEntity child = born.get();
            helper.assertTrue(child != null, BRUTE + " was fed in pairs and no child was born.");
            helper.assertTrue(number(child.value("variant")) == DENIED_VARIANT,
                BRUTE + " is always denied its parents' variant for five and is " + child.value("variant") + ".");
            helper.assertTrue(child.ownerUuid() == null,
                BRUTE + " inherits no taming and belongs to its parents' owner.");
        });
    }

    /**
     * A ranged attack reloads for the time `attack_interval` states, stretched across it by how far
     * into `attack_range` the target stands, both read from the group that declares the behaviour.
     *
     * The archer's interval runs from half a second to two and a half over a range of eight blocks,
     * so a pig four blocks off is shot every second and a half.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = VOLLEY_TICKS)
    public void aRangedAttackReloadsForTheTimeItsRangeStates(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_arm");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(4), BLOWS_TO_KILL);
        brute.setTarget(pig);
        java.util.Set<java.util.UUID> seen = new java.util.HashSet<>();
        List<Long> shots = new ArrayList<>();
        helper.onEachTick(() -> helper.getLevel().getEntitiesOfClass(
                net.minecraft.world.entity.projectile.Projectile.class, brute.getBoundingBox().inflate(VOLLEY_SEARCH),
                shot -> shot.getOwner() == brute && seen.add(shot.getUUID()))
            .forEach(shot -> shots.add(helper.getLevel().getGameTime())));
        helper.succeedWhen(() -> {
            helper.assertTrue(shots.size() >= 2, BRUTE + " shot " + shots.size() + " times.");
            long gap = shots.get(1) - shots.get(0);
            helper.assertTrue(Math.abs(gap - HALFWAY_RELOAD) <= 1,
                BRUTE + " reloads for a second and a half at half its range and shot again after " + gap + " ticks.");
        });
    }

    /** Halfway between the archer's half second and two and a half, in Java's ticks. */
    private static final int HALFWAY_RELOAD = 30;
    private static final int VOLLEY_TICKS = 160;
    private static final double VOLLEY_SEARCH = 12;

    /**
     * A rideable entity under `pull_in_entities` takes on board a mob touching it, raising
     * `on_rider_enter_event`; the mob gets off on top of it under `dismount_mode` `on_top_center`,
     * raising `on_rider_exit_event`; and `passenger_max_width` keeps a wider mob off altogether.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aRideableEntityPullsInWhatTouchesIt(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_ferry", MIDDLE);
        if (brute == null) {
            helper.succeed();
            return;
        }

        brute.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        Mob golem = (Mob) Platform.createMob(BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", "iron_golem")).orElseThrow(), helper.getLevel());
        helper.assertFalse(golem.startRiding(brute),
            BRUTE + " takes riders no wider than a block and took an iron golem.");

        Mob pig = pig(helper, MIDDLE, BLOWS_TO_KILL);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(pig.getVehicle() == brute,
                BRUTE + " pulls in what touches it and the pig beside it is not on board."))
            .thenExecute(() -> {
                helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:boarded")),
                    BRUTE + " took the pig on board and never raised on_rider_enter_event.");
                pig.stopRiding();
                helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:alighted")),
                    BRUTE + " let the pig off and never raised on_rider_exit_event.");
                helper.assertTrue(Math.abs(pig.getY() - brute.getBoundingBox().maxY) < 0.01,
                    BRUTE + " puts riders off on its top and the pig stands at " + pig.getY() + ".");
            })
            .thenSucceed();
    }

    /**
     * A blast breaks blocks whatever the mob griefing rule says, unless its
     * `destroy_affected_by_griefing` asks for the rule, and `max_resistance` caps what a block
     * withstands, so a blast of one breaks obsidian under a cap of nothing. A blast whose
     * `fire_affected_by_griefing` asks for the rule lights nothing with the rule off.
     *
     * The rule is off only while the first two go off, which all happens inside one call on the
     * server's thread, so no other test runs under it.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void aBlastBreaksBlocksAsItsPackStates(GameTestHelper helper) {
        AddonEntity heedless = BehaviourGameTests.spawn(helper, BRUTE, STAND);
        AddonEntity mindful = BehaviourGameTests.spawn(helper, BRUTE, STAND.east(5));
        AddonEntity piercing = BehaviourGameTests.spawn(helper, BRUTE, STAND.south(5));
        // Java lights a blast's fire where it broke a block, so the brute stands in a ring of dirt on
        // obsidian, which outlasts the blast and leaves each broken block's place standing on it.
        BlockPos hearth = STAND.east(5).south(5);
        BlockPos.betweenClosed(hearth.offset(-2, 0, -2), hearth.offset(2, 0, 2))
            .forEach(at -> helper.setBlock(at, Blocks.OBSIDIAN));
        BlockPos.betweenClosed(hearth.offset(-1, 1, -1), hearth.offset(1, 1, 1))
            .forEach(at -> helper.setBlock(at, Blocks.DIRT));
        helper.setBlock(hearth.above(), Blocks.AIR);
        AddonEntity smouldering = BehaviourGameTests.spawn(helper, BRUTE, hearth.above());
        if (heedless == null || mindful == null || piercing == null || smouldering == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(heedless, "tp:brute_make_volatile", null, ServerHooks.state());
        EntityEvents.trigger(mindful, "tp:brute_make_mindful", null, ServerHooks.state());
        EntityEvents.trigger(piercing, "tp:brute_make_piercing", null, ServerHooks.state());
        EntityEvents.trigger(smouldering, "tp:brute_make_smouldering", null, ServerHooks.state());
        helper.setBlock(STAND.south(1), Blocks.DIRT);
        helper.setBlock(STAND.east(5).south(1), Blocks.DIRT);
        helper.setBlock(STAND.south(4), Blocks.OBSIDIAN);

        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        String griefing = Platform.gameRule(server, "mobGriefing");
        Platform.setGameRule(server, "mobGriefing", "false");
        heedless.detonate();
        mindful.detonate();
        smouldering.detonate();
        Platform.setGameRule(server, "mobGriefing", griefing);
        piercing.detonate();

        helper.assertTrue(helper.getBlockState(STAND.south(1)).isAir(),
            BRUTE + " breaks blocks whatever the griefing rule says and left the dirt beside it.");
        helper.assertFalse(helper.getBlockState(STAND.east(5).south(1)).isAir(),
            BRUTE + " heeds the griefing rule and broke the dirt with the rule off.");
        helper.assertTrue(helper.getBlockState(STAND.south(4)).isAir(),
            BRUTE + " caps what a block withstands at nothing and left the obsidian beside it.");
        BlockPos burnt = helper.absolutePos(hearth.above());
        helper.assertTrue(BlockPos.betweenClosedStream(burnt.offset(-3, -1, -3), burnt.offset(3, 2, 3))
                .noneMatch(at -> helper.getLevel().getBlockState(at).is(Blocks.FIRE)),
            BRUTE + " heeds the griefing rule for its fire and lit one with the rule off.");
        helper.succeed();
    }

    /**
     * `minecraft:behavior.drink_potion` drinks the first of its `potions` whose filters hold, named
     * by Bedrock's number: the thirsty group drinks potion nineteen, water breathing, under water and
     * potion twelve, fire resistance, while burning, so a burning brute on land gains fire resistance.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void anEntityDrinksThePotionItsConditionsName(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_thirst");
        if (brute == null) {
            helper.succeed();
            return;
        }

        brute.setRemainingFireTicks(BURNING);
        helper.succeedWhen(() -> {
            List<String> effects = brute.getActiveEffects().stream().map(Platform::effectId).toList();
            helper.assertTrue(effects.contains("minecraft:fire_resistance"),
                BRUTE + " drinks fire resistance while burning and holds " + effects + ".");
            helper.assertFalse(effects.contains("minecraft:water_breathing"),
                BRUTE + " drinks water breathing under water alone and drank it on land.");
        });
    }

    /**
     * `minecraft:behavior.move_outdoors` walks an entity under a roof out to the open sky, within
     * the range its group states, as soon as it is put there; a wander that happened to set off
     * would be a matter of chance over the same few seconds.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = OUTDOOR_TICKS)
    public void anEntityUnderARoofWalksOutUnderTheSky(GameTestHelper helper) {
        roof(helper, new BlockPos(0, 3, 0), new BlockPos(4, 3, 4));
        walksTo(helper, "tp:brute_go_out", new BlockPos(2, 1, 2), true);
    }

    /**
     * `minecraft:behavior.move_indoors` walks an entity under the open sky in under a roof; Java's goal
     * that stood in for it walks only towards a home, which the brute has none of.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = SHELTER_TICKS)
    public void anEntityUnderTheSkyWalksInUnderARoof(GameTestHelper helper) {
        roof(helper, new BlockPos(3, 3, 0), new BlockPos(7, 3, 7));
        walksTo(helper, "tp:brute_go_in", new BlockPos(1, 1, 4), false);
    }

    /** Once the light under the roof has settled, a brute put down with the group on gets to the sky or out of it. */
    private static void walksTo(GameTestHelper helper, String event, BlockPos from, boolean sky) {
        AddonEntity[] brute = {null};
        helper.startSequence()
            .thenExecuteAfter(LIGHT_SETTLES, () -> brute[0] = withGroup(helper, event, from))
            .thenWaitUntil(() -> helper.assertTrue(brute[0] == null
                    || helper.getLevel().canSeeSky(brute[0].blockPosition()) == sky,
                BRUTE + (sky ? " moves outdoors and is still under the roof."
                    : " moves indoors and is still outside.")))
            .thenSucceed();
    }

    private static final int SHELTER_TICKS = 240;

    /** The light settling and five seconds after it, which is plenty for a three block walk. */
    private static final int OUTDOOR_TICKS = 120;

    /** Java works out the light under a new roof over the ticks after it is built. */
    private static final int LIGHT_SETTLES = 20;

    /**
     * A stone floor over the whole structure with a roof over part of it. Java's random walks only
     * end on a block that renders solid, which the structure's own floor is not.
     */
    private static void roof(GameTestHelper helper, BlockPos from, BlockPos to) {
        BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(7, 0, 7))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        BlockPos.betweenClosed(from, to)
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
    }

    /**
     * An entity holds no more of an item than its shareables entry's `max_amount`, nor more of an
     * item no entry names than `all_items_max_amount`; what it will not hold stays on the ground.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void anEntityHoldsNoMoreThanItsShareablesAllow(GameTestHelper helper) {
        AddonEntity brute = BehaviourGameTests.spawn(helper, BRUTE, STAND);
        if (brute == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(brute, "tp:brute_make_frugal", null, ServerHooks.state());
        net.minecraft.world.entity.item.ItemEntity stones = dropped(helper, net.minecraft.world.item.Items.STONE, 5);
        net.minecraft.world.entity.item.ItemEntity apples = dropped(helper, net.minecraft.world.item.Items.APPLE, 3);
        brute.pickUp(stones);
        brute.pickUp(apples);
        brute.pickUp(stones);

        helper.assertTrue(brute.inventory().countItem(net.minecraft.world.item.Items.STONE) == 2,
            BRUTE + " holds two of anything unlisted and holds "
                + brute.inventory().countItem(net.minecraft.world.item.Items.STONE) + " stone.");
        helper.assertTrue(brute.inventory().countItem(net.minecraft.world.item.Items.APPLE) == 1,
            BRUTE + " holds one apple and holds "
                + brute.inventory().countItem(net.minecraft.world.item.Items.APPLE) + ".");
        helper.assertTrue(stones.getItem().getCount() == 3, BRUTE + " left " + stones.getItem().getCount()
            + " of the five stones on the ground.");
        helper.succeed();
    }

    private static net.minecraft.world.entity.item.ItemEntity dropped(GameTestHelper helper,
        net.minecraft.world.item.Item item, int count) {
        BlockPos at = helper.absolutePos(STAND.east(1));
        net.minecraft.world.entity.item.ItemEntity lying = new net.minecraft.world.entity.item.ItemEntity(
            helper.getLevel(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5,
            new net.minecraft.world.item.ItemStack(item, count));
        helper.getLevel().addFreshEntity(lying);
        return lying;
    }

    /**
     * An area attack waits `damage_cooldown` seconds after a hit before the next, a second and a half
     * for the thorny group, where it otherwise hits on every sensor pass the target is not still
     * invulnerable for.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void anAreaAttackWaitsItsCooldown(GameTestHelper helper) {
        AddonEntity brute = BehaviourGameTests.spawn(helper, BRUTE, STAND);
        if (brute == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(brute, "tp:brute_bristle", null, ServerHooks.state());
        Mob pig = pig(helper, STAND.east(1), BLOWS_TO_KILL);
        Platform.placeAt(pig, brute.getX() + 0.6, brute.getY(), brute.getZ(), 0f, 0f);
        List<Long> hits = new ArrayList<>();
        float[] health = {pig.getHealth()};
        helper.onEachTick(() -> {
            if (pig.getHealth() < health[0]) hits.add(helper.getLevel().getGameTime());
            health[0] = pig.getHealth();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(hits.size() >= 2, BRUTE + " struck what touches it " + hits.size() + " times.");
            long gap = hits.get(1) - hits.get(0);
            helper.assertTrue(gap >= THORNY_COOLDOWN,
                BRUTE + " waits a second and a half between hits and hit again after " + gap + " ticks.");
        });
    }

    /** The second and a half the thorny group waits, in Java's ticks. */
    private static final int THORNY_COOLDOWN = 30;

    /**
     * `minecraft:variable_max_auto_step` steps as high as `controlled_value` while a player steers the
     * entity, and back down to `base_value` once nobody does.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void aSteeredEntityStepsAsHighAsItsControlledValue(GameTestHelper helper) {
        AddonEntity brute = BehaviourGameTests.spawn(helper, BRUTE, STAND);
        if (brute == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(brute, "tp:brute_steady_up", null, ServerHooks.state());
        net.minecraft.server.level.ServerPlayer rider = helper.makeMockServerPlayerInLevel();
        helper.assertTrue(rider.startRiding(brute), BRUTE + " is rideable and a player could not get on.");
        helper.assertTrue(Math.abs(Platform.stepHeight(brute) - STEERED_STEP) < 0.01,
            BRUTE + " steps a block and a half while steered and steps " + Platform.stepHeight(brute) + ".");
        rider.stopRiding();
        helper.assertTrue(Math.abs(Platform.stepHeight(brute) - LONE_STEP) < 0.01,
            BRUTE + " steps six tenths with nobody aboard and steps " + Platform.stepHeight(brute) + ".");
        helper.succeed();
    }

    private static final float STEERED_STEP = 1.5f;
    private static final float LONE_STEP = 0.6f;

    /** On a block that cuts a jump short, which is honey in Java, the step is `jump_prevented_value`. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void anEntityOnHoneyStepsAsHighAsItsJumpPreventedValue(GameTestHelper helper) {
        helper.setBlock(STAND, Blocks.HONEY_BLOCK);
        AddonEntity brute = withGroup(helper, "tp:brute_steady_up", STAND.above());
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> helper.assertTrue(Math.abs(Platform.stepHeight(brute) - HONEY_STEP) < 0.01,
            BRUTE + " steps a quarter of a block on honey and steps " + Platform.stepHeight(brute) + "."));
    }

    private static final float HONEY_STEP = 0.25f;

    /**
     * `minecraft:water_movement` keeps `drag_factor` of the entity's speed each tick in water. Two
     * brutes set off down a channel at the same speed, and the one keeping more of it goes further.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void anEntityKeepsTheShareOfItsSpeedItsWaterDragStates(GameTestHelper helper) {
        BlockPos.betweenClosed(new BlockPos(-1, 0, 0), new BlockPos(CHANNEL + 1, 0, 6))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        IntStream.rangeClosed(0, CHANNEL).forEach(x -> {
            helper.setBlock(new BlockPos(x, 0, 2), Blocks.WATER);
            helper.setBlock(new BlockPos(x, 0, 4), Blocks.WATER);
        });
        AddonEntity slick = withGroup(helper, "tp:brute_slip", new BlockPos(0, 0, 2));
        AddonEntity plain = BehaviourGameTests.spawn(helper, BRUTE, new BlockPos(0, 0, 4));
        if (slick == null || plain == null) {
            helper.succeed();
            return;
        }

        plain.setNoAi(false);
        double from = slick.getX();
        helper.runAfterDelay(1, () -> {
            slick.setDeltaMovement(LAUNCH, 0, 0);
            plain.setDeltaMovement(LAUNCH, 0, 0);
        });
        helper.runAfterDelay(GLIDE_TICKS, () -> {
            helper.assertTrue(slick.getX() - from > plain.getX() - from + 1,
                BRUTE + " keeps nineteen twentieths of its speed in water and went " + (slick.getX() - from)
                    + " beside one keeping Java's four fifths, which went " + (plain.getX() - from) + ".");
            helper.succeed();
        });
    }

    private static final int CHANNEL = 12;
    private static final double LAUNCH = 0.5;
    private static final int GLIDE_TICKS = 30;

    /** `minecraft:behavior.random_swim` with an `interval` of nought, as Mojang's fish write it, sets off. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aSwimWithNoIntervalSetsOffAtOnce(GameTestHelper helper) {
        AddonEntity brute = inAPool(helper, "tp:brute_fidget");
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> helper.assertTrue(brute.getNavigation().getTargetPos() != null,
            BRUTE + " swims about at every chance and never set off."));
    }

    /**
     * `minecraft:behavior.random_swim` keeps below the surface unless `avoid_surface` says otherwise, so
     * none of the spots it heads for is in the top layer of the pool.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aSwimKeepsBelowTheSurface(GameTestHelper helper) {
        AddonEntity brute = inAPool(helper, "tp:brute_swim_deep");
        if (brute == null) {
            helper.succeed();
            return;
        }

        int surface = helper.absolutePos(new BlockPos(0, POOL_DEPTH - 1, 0)).getY();
        java.util.Set<BlockPos> headings = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            BlockPos heading = brute.getNavigation().getTargetPos();
            if (heading != null) headings.add(heading);
        });
        helper.runAfterDelay(WANDER_TICKS, () -> {
            helper.assertTrue(!headings.isEmpty(), BRUTE + " swims at every chance and never set off.");
            helper.assertTrue(headings.stream().noneMatch(heading -> heading.getY() == surface),
                BRUTE + " keeps below the surface and headed for " + headings + " with the surface at "
                    + surface + ".");
            helper.succeed();
        });
    }

    /**
     * `minecraft:behavior.swim_wander` gives up a wander after `wander_time` and, its `interval` being a
     * chance of a half each time, soon sets off on another. A quarter of a second a wander leaves time for
     * several in the five seconds the test watches.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aSwimWanderEndsAfterItsWanderTime(GameTestHelper helper) {
        AddonEntity brute = inAPool(helper, "tp:brute_drift");
        if (brute == null) {
            helper.succeed();
            return;
        }

        setsOffAgain(helper, brute, "wanders for a quarter of a second at a time");
    }

    /**
     * `minecraft:behavior.random_fly` flies to a spot `y_offset` higher than the one it picked, which
     * with a `y_dist` of one is well above where the brute stands.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLIGHT_TICKS)
    public void aRandomFlightAimsAsHighAsItsOffset(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_loft");
        if (brute == null) {
            helper.succeed();
            return;
        }

        aimsHigh(helper, brute, "flies four blocks over the spot it picked");
    }

    /** `minecraft:behavior.random_hover` keeps the spot it picks within `hover_height` of the ground. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLIGHT_TICKS)
    public void aRandomHoverAimsWithinItsHeight(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_hover");
        if (brute == null) {
            helper.succeed();
            return;
        }

        aimsHigh(helper, brute, "hovers five to six blocks over the ground");
    }

    /** `minecraft:behavior.float_wander` gives a float up after `float_duration` and floats off again. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aFloatEndsAfterItsDuration(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_float");
        if (brute == null) {
            helper.succeed();
            return;
        }

        setsOffAgain(helper, brute, "floats for a quarter of a second at a time");
    }

    /** `minecraft:behavior.float_wander` under `random_reselect` picks new spots on the way to the last. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aFloatThatReselectsPicksNewSpotsOnTheWay(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_waver");
        if (brute == null) {
            helper.succeed();
            return;
        }

        setsOffAgain(helper, brute, "picks a new spot on the way at every chance");
    }

    /**
     * `minecraft:behavior.follow_mob` looks for a mob to follow as far as `search_range`, past the seven
     * blocks Java's own goal looks, and stops `stop_distance` short of it.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS * 2)
    public void aFollowerLooksAsFarAndStopsAsShortAsItsPackSays(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_tag_along", new BlockPos(0, 0, 4));
        Mob pig = pig(helper, new BlockPos(FOLLOWED, 0, 4), BLOWS_TO_KILL);
        if (brute == null) {
            helper.succeed();
            return;
        }

        double[] nearest = {Double.MAX_VALUE};
        helper.onEachTick(() -> nearest[0] = Math.min(nearest[0], brute.distanceTo(pig)));
        helper.runAfterDelay(GOAL_TICKS * 2 - 10, () -> {
            helper.assertTrue(brute.distanceTo(pig) < FOLLOWED - 3,
                BRUTE + " follows a pig ten blocks off and stands " + brute.distanceTo(pig) + " from it.");
            helper.assertTrue(nearest[0] > KEPT_OFF,
                BRUTE + " stops five blocks short of the pig and came to " + nearest[0] + ".");
            helper.succeed();
        });
    }

    /** `minecraft:behavior.follow_target_captain` walks after the captain and stays `follow_distance` off. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS * 2)
    public void aRaiderKeepsItsFollowDistanceFromTheCaptain(GameTestHelper helper) {
        AddonEntity raider = withGroup(helper, "tp:brute_fall_in", new BlockPos(0, 0, 4));
        AddonEntity captain = heldInPlace(helper, "tp:brute_lead", new BlockPos(FOLLOWED, 0, 4));
        if (raider == null || captain == null) {
            helper.succeed();
            return;
        }

        double[] nearest = {Double.MAX_VALUE};
        helper.onEachTick(() -> nearest[0] = Math.min(nearest[0], raider.distanceTo(captain)));
        helper.runAfterDelay(GOAL_TICKS * 2 - 10, () -> {
            helper.assertTrue(raider.distanceTo(captain) < FOLLOWED - 2,
                BRUTE + " follows its captain ten blocks off and stands " + raider.distanceTo(captain) + " from it.");
            helper.assertTrue(nearest[0] > KEPT_FROM_CAPTAIN,
                BRUTE + " keeps six blocks from its captain and came to " + nearest[0] + ".");
            helper.succeed();
        });
    }

    /**
     * `minecraft:behavior.move_to_block` looks every `tick_interval`, raises `on_reach` on arriving and
     * `on_stay_completed` once it has stood there `stay_duration` ticks.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aWalkToABlockRaisesItsEventsOnArrivingAndStaying(GameTestHelper helper) {
        helper.setBlock(new BlockPos(5, -1, 4), Blocks.GOLD_BLOCK);
        AddonEntity brute = withGroup(helper, "tp:brute_seek", new BlockPos(1, 0, 4));
        if (brute == null) {
            helper.succeed();
            return;
        }

        long[] reachedAt = {-1};
        long[] stayedAt = {-1};
        helper.onEachTick(() -> {
            if (reachedAt[0] < 0 && Boolean.TRUE.equals(brute.value("tp:reached"))) reachedAt[0] = helper.getTick();
            if (stayedAt[0] < 0 && Boolean.TRUE.equals(brute.value("tp:stayed"))) stayedAt[0] = helper.getTick();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(reachedAt[0] >= 0, BRUTE + " walks to the gold and never raised its arrival.");
            helper.assertTrue(stayedAt[0] >= 0, BRUTE + " stays at the gold a second and never raised its stay.");
            helper.assertTrue(stayedAt[0] - reachedAt[0] >= STAY_TICKS,
                BRUTE + " stays twenty ticks and raised its stay " + (stayedAt[0] - reachedAt[0]) + " after arriving.");
        });
    }

    private static final int STAY_TICKS = 20;

    /** `minecraft:behavior.move_to_block` walks to the block moved by `target_offset`, three blocks east. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aWalkToABlockEndsAtItsTargetOffset(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, -1, 4), Blocks.GOLD_BLOCK);
        AddonEntity brute = withGroup(helper, "tp:brute_seek_beside", new BlockPos(1, 0, 4));
        if (brute == null) {
            helper.succeed();
            return;
        }

        double gold = helper.absolutePos(new BlockPos(3, 0, 4)).getX() + 0.5;
        helper.succeedWhen(() -> helper.assertTrue(brute.getX() - gold > OFFSET_REACHED,
            BRUTE + " walks to three blocks east of the gold and stands " + (brute.getX() - gold) + " east of it."));
    }

    /** Within the block and a half its `goal_radius` allows of the spot three blocks east of the gold. */
    private static final double OFFSET_REACHED = 1.5;

    /** `minecraft:behavior.move_to_block` with a `start_chance` of nought never sets off. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aWalkToABlockWithNoChanceNeverStarts(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, -1, 4), Blocks.GOLD_BLOCK);
        AddonEntity brute = withGroup(helper, "tp:brute_seek_reluctantly", new BlockPos(1, 0, 4));
        if (brute == null) {
            helper.succeed();
            return;
        }

        double from = brute.getX();
        helper.runAfterDelay(GOAL_TICKS - 20, () -> {
            helper.assertTrue(Math.abs(brute.getX() - from) < STILL * 10,
                BRUTE + " has no chance to set off and walked " + (brute.getX() - from) + ".");
            helper.succeed();
        });
    }

    /** Inside the six blocks the raider keeps, and outside the three it kept whatever the pack said. */
    private static final double KEPT_FROM_CAPTAIN = 5;

    private static final int FOLLOWED = 10;
    /** Inside the five blocks the brute stops at, and outside the three Java's goal stops at. */
    private static final double KEPT_OFF = 4;

    /** Long enough for a one in 120 chance to come up all but certainly. */
    private static final int FLIGHT_TICKS = 1000;
    /** Over the highest a spot one block up or down from the floor reaches, hovering included. */
    private static final int HIGH_AIM = 4;

    /** Asks that every spot the brute headed for while the test ran was high over the floor. */
    private static void aimsHigh(GameTestHelper helper, AddonEntity brute, String why) {
        // Java's fallback for a flight looks a few blocks under the mob, which under the test floor is open air.
        BlockPos.betweenClosed(new BlockPos(-4, -4, -4), new BlockPos(6, -2, 6))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        int ground = helper.absolutePos(BlockPos.ZERO).getY();
        int[] lowest = {Integer.MAX_VALUE};
        helper.onEachTick(() -> {
            // Java's strolls stop setting off once a mob has gone five seconds with no player near.
            brute.setNoActionTime(0);
            BlockPos heading = brute.getNavigation().getTargetPos();
            if (heading != null) lowest[0] = Math.min(lowest[0], heading.getY() - ground);
        });
        helper.runAfterDelay(FLIGHT_TICKS - 10, () -> {
            helper.assertTrue(lowest[0] != Integer.MAX_VALUE, BRUTE + " " + why + " and never set off.");
            helper.assertTrue(lowest[0] >= HIGH_AIM,
                BRUTE + " " + why + " and headed for a spot " + lowest[0] + " over the floor.");
            helper.succeed();
        });
    }

    /** Waits five seconds and asks that the brute headed for several spots in them. */
    private static void setsOffAgain(GameTestHelper helper, AddonEntity brute, String why) {
        java.util.Set<BlockPos> headings = new java.util.HashSet<>();
        helper.onEachTick(() -> {
            BlockPos heading = brute.getNavigation().getTargetPos();
            if (heading != null && brute.getNavigation().isInProgress()) headings.add(heading);
        });
        helper.runAfterDelay(WANDER_TICKS, () -> {
            helper.assertTrue(headings.size() >= WANDERS,
                BRUTE + " " + why + " and set off " + headings.size() + " times.");
            helper.succeed();
        });
    }

    private static final int WANDER_TICKS = 100;
    private static final int WANDERS = 3;

    /**
     * A brute at the bottom of a pool three blocks deep and nine across, walled in so the water lies
     * still. Java's swim goals try ten spots and keep the last whether it is water or not, so a pool
     * with room in it is what makes a swim set off at most chances.
     */
    private static AddonEntity inAPool(GameTestHelper helper, String event) {
        BlockPos.betweenClosed(new BlockPos(-1, 0, -1), new BlockPos(POOL + 1, POOL_DEPTH - 1, POOL + 1))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(POOL, POOL_DEPTH - 1, POOL))
            .forEach(at -> helper.setBlock(at, Blocks.WATER));
        return withGroup(helper, event, new BlockPos(POOL / 2, 0, POOL / 2));
    }

    private static final int POOL = 8;
    private static final int POOL_DEPTH = 3;

    /**
     * A leap rises at the `yd` its pack states. The springing group says nine tenths, more than
     * twice the four tenths every leap used to take.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aLeapRisesAtTheSpeedItsPackStates(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_spring");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(3), BLOWS_TO_KILL);
        brute.setTarget(pig);
        double[] highest = {0};
        helper.onEachTick(() -> highest[0] = Math.max(highest[0], brute.getDeltaMovement().y));
        helper.succeedWhen(() -> helper.assertTrue(highest[0] > HIGH_LEAP,
            BRUTE + " leaps at nine tenths and rose at no more than " + highest[0] + "."));
    }

    /** Above anything a leap of four tenths reaches. */
    private static final double HIGH_LEAP = 0.6;

    /**
     * `minecraft:behavior.move_to_water` looks for water as far as its `search_range` and walks to
     * it. The pool is five blocks off, past the two Java's own goal for this looks.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = SHELTER_TICKS)
    public void anEntityLooksAsFarForWaterAsItsPackSays(GameTestHelper helper) {
        BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(7, 0, 7))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        helper.setBlock(new BlockPos(6, 0, 4), Blocks.WATER);
        AddonEntity brute = withGroup(helper, "tp:brute_parch", new BlockPos(1, 1, 4));
        if (brute == null) {
            helper.succeed();
            return;
        }

        BlockPos pool = helper.absolutePos(new BlockPos(6, 0, 4));
        helper.succeedWhen(() -> helper.assertTrue(
            Math.hypot(brute.getX() - pool.getX() - 0.5, brute.getZ() - pool.getZ() - 0.5) < AT_THE_POOL,
            BRUTE + " looks eight blocks for water and never reached the pool five blocks off."));
    }

    /**
     * A walk to a block counts as there within the `goal_radius` its pack states. The digging group
     * says three, so a brute held two blocks from the gold it digs for digs it where it stands.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aWalkToABlockArrivesWithinItsGoalRadius(GameTestHelper helper) {
        helper.setBlock(STAND.east(2), Blocks.GOLD_BLOCK);
        AddonEntity brute = heldInPlace(helper, "tp:brute_dig");
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> helper.assertTrue(helper.getBlockState(STAND.east(2)).isAir(),
            BRUTE + " counts three blocks as there and never dug the gold two blocks off."));
    }

    /** Beside the pool, where a mob that walks to water stops. */
    private static final double AT_THE_POOL = 1.5;

    /**
     * A flockmate nearer than `separation_threshold` pushes the entity away, by `separation_weight`.
     * Two crowded brutes a block apart, their feet held, drift apart on the push alone.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLOCK_TICKS)
    public void aFlockmateTooCloseIsPushedAway(GameTestHelper helper) {
        AddonEntity one = flocker(helper, "tp:brute_crowd", 4, 1);
        AddonEntity other = flocker(helper, "tp:brute_crowd", 5, 1);
        if (one == null || other == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> helper.assertTrue(one.distanceTo(other) > CROWDED_APART,
            BRUTE + " keeps three blocks from its flock and stands " + one.distanceTo(other) + " from the next."));
    }

    /** Half a block further apart than the two brutes start. */
    private static final double CROWDED_APART = 1.5;

    /**
     * Under `match_variants` the flock is the ones drawn alike: the middle brute is pulled towards the
     * brute of its own variant on one side and not towards the middle of both, which lies the other way.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLOCK_TICKS)
    public void aFlockOfMatchingVariantsLeavesOthersOut(GameTestHelper helper) {
        AddonEntity middle = flocker(helper, "tp:brute_school", 4, 1);
        AddonEntity alike = flocker(helper, "tp:brute_school", 2, 1);
        AddonEntity unlike = flocker(helper, "tp:brute_school", 7, 2);
        if (middle == null || alike == null || unlike == null) {
            helper.succeed();
            return;
        }

        drawsTowardsAlike(helper, middle, "flocks with its own variant alone");
    }

    /**
     * `high_flock_limit` caps the flock at the nearest so many: under a limit of one the middle brute is
     * pulled towards its nearest flockmate, and not towards the middle of all three.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLOCK_TICKS)
    public void aFlockIsCappedAtItsHighLimit(GameTestHelper helper) {
        AddonEntity middle = flocker(helper, "tp:brute_pair", 4, 1);
        AddonEntity nearest = flocker(helper, "tp:brute_pair", 2, 1);
        AddonEntity further = flocker(helper, "tp:brute_pair", 7, 1);
        AddonEntity furthest = flocker(helper, "tp:brute_pair", 7, 1);
        if (middle == null || nearest == null || further == null || furthest == null) {
            helper.succeed();
            return;
        }

        drawsTowardsAlike(helper, middle, "flocks with its one nearest");
    }

    /**
     * A brute whose `loner_chance` is certain keeps out of the flock beside it, while the flocking
     * brute beside it is drawn towards it all the same.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLOCK_TICKS)
    public void aLonerKeepsOutOfTheFlock(GameTestHelper helper) {
        AddonEntity loner = flocker(helper, "tp:brute_keep_apart", 4, 1);
        AddonEntity other = flocker(helper, "tp:brute_flock_near", 2, 1);
        if (loner == null || other == null) {
            helper.succeed();
            return;
        }

        double lonerFrom = loner.getX();
        double otherFrom = other.getX();
        helper.runAfterDelay(FLOCK_TICKS - 10, () -> {
            helper.assertTrue(other.getX() > otherFrom + DRIFT,
                BRUTE + " flocks and moved " + (other.getX() - otherFrom) + " towards the loner beside it.");
            helper.assertTrue(Math.abs(loner.getX() - lonerFrom) < STILL,
                BRUTE + " is a loner and drifted " + (loner.getX() - lonerFrom) + " towards the brute beside it.");
            helper.succeed();
        });
    }

    /** A solid block nearer than `block_distance` pushes a flocker away, with no flockmate about. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLOCK_TICKS)
    public void aFlockerKeepsOffTheBlocksBesideIt(GameTestHelper helper) {
        AddonEntity brute = flocker(helper, "tp:brute_wall", 4, 1);
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(new BlockPos(5, 0, 4), Blocks.STONE);
        drawsTowardsAlike(helper, brute, "keeps two blocks off a wall");
    }

    /**
     * A flocker floating with nothing to hold it up is pushed up while lower over the ground than its
     * `min_height` and down while higher than its `max_height`.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLOCK_TICKS)
    public void aFlockerKeepsBetweenItsHeights(GameTestHelper helper) {
        AddonEntity low = floating(helper, "tp:brute_band", new BlockPos(2, 0, 4), 0);
        AddonEntity high = floating(helper, "tp:brute_band", new BlockPos(6, 0, 4), HIGH_FLOAT);
        if (low == null || high == null) {
            helper.succeed();
            return;
        }

        double ground = helper.absolutePos(BlockPos.ZERO).getY();
        helper.succeedWhen(() -> {
            helper.assertTrue(low.getY() - ground > 1,
                BRUTE + " keeps two blocks over the ground and floats " + (low.getY() - ground) + " over it.");
            helper.assertTrue(high.getY() - ground < HIGH_FLOAT - 1,
                BRUTE + " keeps three blocks over the ground and floats " + (high.getY() - ground) + " over it.");
        });
    }

    private static final double HIGH_FLOAT = 6;

    /** A flocker `in_water` whose eyes are out of the water is pushed back under by `breach_influence`. */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = FLOCK_TICKS)
    public void aFlockerBreachingTheSurfaceIsPushedBackUnder(GameTestHelper helper) {
        BlockPos pool = new BlockPos(4, 0, 4);
        IntStream.rangeClosed(0, 2).forEach(depth -> {
            Direction.Plane.HORIZONTAL.stream().forEach(side -> helper.setBlock(pool.above(depth).relative(side),
                Blocks.STONE));
            helper.setBlock(pool.above(depth), Blocks.WATER);
        });
        AddonEntity brute = floating(helper, "tp:brute_breach", pool, SURFACING);
        if (brute == null) {
            helper.succeed();
            return;
        }

        double ground = helper.absolutePos(BlockPos.ZERO).getY();
        helper.succeedWhen(() -> helper.assertTrue(brute.getY() - ground < SURFACING - SUNK,
            BRUTE + " breached the surface and floats " + (brute.getY() - ground) + " over the pool's floor."));
    }

    /** Feet in the top block of a pool three deep, with the eyes over its surface. */
    private static final double SURFACING = 2.2;
    private static final double SUNK = 0.3;

    /** A brute with its feet held and no gravity, so flocking alone moves it up or down. */
    private static AddonEntity floating(GameTestHelper helper, String event, BlockPos where, double over) {
        AddonEntity brute = heldInPlace(helper, event, where);
        if (brute == null) return null;

        brute.setNoGravity(true);
        brute.setPos(brute.getX(), helper.absolutePos(where).getY() + over, brute.getZ());
        return brute;
    }

    private static final int FLOCK_TICKS = 60;
    private static final double STILL = 0.05;

    /** Waits for the middle brute to have drifted west, towards the flockmate two blocks off on that side. */
    private static void drawsTowardsAlike(GameTestHelper helper, AddonEntity middle, String why) {
        double from = middle.getX();
        helper.succeedWhen(() -> helper.assertTrue(middle.getX() < from - DRIFT,
            BRUTE + " " + why + " and moved " + (middle.getX() - from) + " along the row."));
    }

    private static final double DRIFT = 0.2;

    /** A brute along the middle row with its feet held, so flocking is all that moves it. */
    private static AddonEntity flocker(GameTestHelper helper, String event, int x, double variant) {
        AddonEntity brute = heldInPlace(helper, event, new BlockPos(x, 0, 4));
        if (brute != null) brute.setValue("variant", variant);
        return brute;
    }

    /**
     * `minecraft:addrider` seats every rider its `riders` list names, each raising its own spawn
     * event, and a group bringing the component in is enough for that.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void anEntitySeatsEveryRiderItsPackLists(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_carry");
        if (brute == null) {
            helper.succeed();
            return;
        }

        helper.succeedWhen(() -> {
            helper.assertTrue(brute.getPassengers().size() == 2,
                BRUTE + " lists two riders and carries " + brute.getPassengers().size() + ".");
            helper.assertTrue(brute.getPassengers().stream().anyMatch(rider -> rider instanceof AddonEntity seated
                    && Boolean.TRUE.equals(seated.value("tp:struck"))),
                BRUTE + " seated a brute whose spawn event never ran.");
        });
    }

    /**
     * A blow carries the ailment `minecraft:attack` names, for its duration in seconds and at its
     * amplifier.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void aBlowCarriesTheAilmentItsAttackNames(GameTestHelper helper) {
        AddonEntity brute = heldInPlace(helper, "tp:brute_envenom");
        if (brute == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, STAND.east(1), BLOWS_TO_KILL);
        brute.setTarget(pig);
        helper.succeedWhen(() -> {
            net.minecraft.world.effect.MobEffectInstance poison = pig.getActiveEffects().stream()
                .filter(active -> Platform.effectId(active).equals("minecraft:poison"))
                .findFirst().orElse(null);
            helper.assertTrue(poison != null, BRUTE + " poisons what it hits and the pig is not poisoned.");
            helper.assertTrue(poison.getAmplifier() == 1,
                BRUTE + " poisons at amplifier one and the pig's poison is at " + poison.getAmplifier() + ".");
            helper.assertTrue(poison.getDuration() > POISON_TICKS - GOAL_TICKS && poison.getDuration() <= POISON_TICKS,
                BRUTE + " poisons for three seconds and the pig's poison has " + poison.getDuration() + " ticks left.");
        });
    }

    /** The three seconds the venomous group poisons for, in Java's ticks. */
    private static final int POISON_TICKS = 3 * 20;

    /**
     * A `minecraft:tameable` stating no `probability` tames at the first of its items, which is
     * Bedrock's default; eight brutes each fed once all belong to the feeder afterwards.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void anEntityIsTamedAtTheFirstItemByDefault(GameTestHelper helper) {
        net.minecraft.world.entity.player.Player feeder = Platform.mockPlayer(helper);
        feeder.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE, TAMED_TOGETHER));
        for (int i = 0; i < TAMED_TOGETHER; i++) {
            AddonEntity brute = BehaviourGameTests.spawn(helper, BRUTE, new BlockPos(i % 4 * 2, 0, i / 4 * 2));
            if (brute == null) {
                helper.succeed();
                return;
            }

            EntityEvents.trigger(brute, "tp:brute_make_tameable", null, ServerHooks.state());
            brute.mobInteract(feeder, net.minecraft.world.InteractionHand.MAIN_HAND);
            helper.assertTrue(feeder.getUUID().equals(brute.ownerUuid()),
                BRUTE + " number " + (i + 1) + " was fed its taming item once and belongs to nobody.");
            helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:tamed")),
                BRUTE + " was tamed and never raised the event tame_event names.");
        }
        helper.succeed();
    }

    private static final int TAMED_TOGETHER = 8;

    /**
     * A mount under `minecraft:tamemount` is broken in by riding: an item it rejects keeps the player
     * off, its food raises its temper without taming it, and with its temper full the first buck
     * leaves it belonging to the rider.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = BREAK_IN_TICKS)
    public void aMountIsBrokenInByRidingOnceItsTemperIsFull(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_make_wild", MIDDLE);
        if (brute == null) {
            helper.succeed();
            return;
        }

        net.minecraft.server.level.ServerPlayer rider = helper.makeMockServerPlayerInLevel();
        net.minecraft.world.InteractionHand hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        rider.setItemInHand(hand, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SADDLE));
        brute.mobInteract(rider, hand);
        helper.assertFalse(rider.isPassenger(), BRUTE + " rejects a saddle and took its holder on board.");

        rider.setItemInHand(hand, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE));
        brute.mobInteract(rider, hand);
        helper.assertTrue(brute.ownerUuid() == null, BRUTE + " is broken in by riding and was tamed by an apple.");

        rider.setItemInHand(hand, net.minecraft.world.item.ItemStack.EMPTY);
        brute.mobInteract(rider, hand);
        helper.assertTrue(rider.getVehicle() == brute, BRUTE + " is rideable and an empty hand did not get on.");
        helper.succeedWhen(() -> {
            helper.assertTrue(rider.getUUID().equals(brute.ownerUuid()),
                BRUTE + " was ridden with its temper full and still belongs to nobody.");
            helper.assertTrue(Boolean.TRUE.equals(brute.value("tp:tamed")),
                BRUTE + " was broken in and never raised the event tame_event names.");
        });
    }

    /** Past the longest wait before a first buck. */
    private static final int BREAK_IN_TICKS = 160;

    /**
     * Two tamed brutes with the group on, the same variant, mark variant and tp:hue, a red and a white
     * coat, both fed: what is born of them, once it is.
     */
    private static java.util.function.Supplier<AddonEntity> breed(GameTestHelper helper, String event) {
        AddonEntity first = withGroup(helper, event, MIDDLE);
        AddonEntity second = withGroup(helper, event, MIDDLE.east(1));
        if (first == null || second == null) return null;

        net.minecraft.world.entity.player.Player owner = Platform.mockPlayer(helper);
        owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 2));
        for (AddonEntity parent : List.of(first, second)) {
            parent.setOwner(owner);
            parent.setValue("variant", PARENT_VARIANT);
            parent.setValue("mark_variant", PARENT_MARK);
            parent.setValue("tp:hue", PARENT_HUE);
            parent.getAttribute(Attributes.MAX_HEALTH).setBaseValue(PARENT_HEALTH);
            parent.mobInteract(owner, net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        first.setValue("color", RED);
        second.setValue("color", WHITE);
        return () -> helper.getLevel().getEntitiesOfClass(AddonEntity.class,
                first.getBoundingBox().inflate(BREED_SEARCH), found -> found != first && found != second)
            .stream().findFirst().orElse(null);
    }

    private static final int BREED_TICKS = 200;
    private static final double BREED_SEARCH = 4;
    private static final double PARENT_VARIANT = 3;
    private static final double PARENT_MARK = 1;
    private static final double MUTATED_VARIANT = 7;
    private static final double MUTATED_MARK = 2;
    private static final double DENIED_VARIANT = 5;
    private static final double PARENT_HUE = 5;
    private static final double PARENT_HEALTH = 30;

    /** Java's horse breeding spreads a child of two equal parents by at most three tenths of the range. */
    private static final float BLENDED_HEALTH_FLOOR = 27;

    /** Bedrock's colour numbers, which count the sixteen dyes in Java's order. */
    private static final double WHITE = 0;
    private static final double PINK = 6;
    private static final double RED = 14;

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }

    /**
     * An `entity_types` list admits whoever any one of its entries admits, and an entry written the
     * older way, an `AND` group over numbered subjects and operators, is read as written: the sharing
     * brute passes the cow beside it and hands its apple to the pig further off.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = GOAL_TICKS)
    public void anEntityTypesListPicksWhoAnItemIsSharedWith(GameTestHelper helper) {
        AddonEntity brute = withGroup(helper, "tp:brute_share");
        if (brute == null) {
            helper.succeed();
            return;
        }

        brute.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE));
        standing(helper, "cow", STAND.south(2));
        Mob pig = standing(helper, "pig", STAND.east(5));
        helper.succeedWhen(() -> {
            helper.assertTrue(brute.getMainHandItem().isEmpty(), BRUTE + " still holds its apple.");
            helper.assertTrue(brute.distanceTo(pig) <= SHARED_NEAR,
                BRUTE + " handed its apple over " + brute.distanceTo(pig) + " blocks from the pig.");
        });
    }

    /** The share goal's default `goal_radius` of two, and a block of slack for where the walk ends. */
    private static final double SHARED_NEAR = 3;

    /** A brute with its group on and its goals running. */
    private static AddonEntity withGroup(GameTestHelper helper, String event) {
        return withGroup(helper, event, STAND);
    }

    private static AddonEntity withGroup(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity brute = BehaviourGameTests.spawn(helper, BRUTE, where);
        if (brute == null) return null;

        EntityEvents.trigger(brute, event, null, ServerHooks.state());
        brute.setNoAi(false);
        return brute;
    }

    /**
     * A brute with its group on, its goals running and its feet held, so only its reach decides what
     * it can hit.
     */
    private static AddonEntity heldInPlace(GameTestHelper helper, String event) {
        return heldInPlace(helper, event, STAND);
    }

    private static AddonEntity heldInPlace(GameTestHelper helper, String event, BlockPos where) {
        AddonEntity brute = withGroup(helper, event, where);
        if (brute != null) brute.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        return brute;
    }

    /** A pig that stays where it is put, blows included, with the health the test asks for. */
    private static Mob pig(GameTestHelper helper, BlockPos where, float health) {
        Mob pig = standing(helper, "pig", where);
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        pig.setHealth(health);
        // A blow would otherwise knock it out of the reach a test is measuring something else at.
        pig.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        return pig;
    }

    /** One of the game's own mobs, with no goals of its own, where the test puts it. */
    private static Mob standing(GameTestHelper helper, String kind, BlockPos where) {
        ServerLevel level = helper.getLevel();
        Entity created = Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", kind)).orElseThrow(), level);
        helper.assertTrue(created instanceof Mob, "A " + kind + " could not be made.");

        Mob mob = (Mob) created;
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(mob, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setNoAi(true);
        level.addFreshEntity(mob);
        return mob;
    }
}
