package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.List;

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

        helper.setBlock(STAND.east(3), net.minecraft.world.level.block.Blocks.WATER);
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

        helper.setBlock(MIDDLE.east(1), net.minecraft.world.level.block.Blocks.GOLD_BLOCK);
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
            .forEach(at -> helper.setBlock(at, net.minecraft.world.level.block.Blocks.OBSIDIAN));
        BlockPos.betweenClosed(hearth.offset(-1, 1, -1), hearth.offset(1, 1, 1))
            .forEach(at -> helper.setBlock(at, net.minecraft.world.level.block.Blocks.DIRT));
        helper.setBlock(hearth.above(), net.minecraft.world.level.block.Blocks.AIR);
        AddonEntity smouldering = BehaviourGameTests.spawn(helper, BRUTE, hearth.above());
        if (heedless == null || mindful == null || piercing == null || smouldering == null) {
            helper.succeed();
            return;
        }

        EntityEvents.trigger(heedless, "tp:brute_make_volatile", null, ServerHooks.state());
        EntityEvents.trigger(mindful, "tp:brute_make_mindful", null, ServerHooks.state());
        EntityEvents.trigger(piercing, "tp:brute_make_piercing", null, ServerHooks.state());
        EntityEvents.trigger(smouldering, "tp:brute_make_smouldering", null, ServerHooks.state());
        helper.setBlock(STAND.south(1), net.minecraft.world.level.block.Blocks.DIRT);
        helper.setBlock(STAND.east(5).south(1), net.minecraft.world.level.block.Blocks.DIRT);
        helper.setBlock(STAND.south(4), net.minecraft.world.level.block.Blocks.OBSIDIAN);

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
                .noneMatch(at -> helper.getLevel().getBlockState(at).is(net.minecraft.world.level.block.Blocks.FIRE)),
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

    /** The light settling and three seconds after it, which is plenty for a three block walk. */
    private static final int OUTDOOR_TICKS = 80;

    /** Java works out the light under a new roof over the ticks after it is built. */
    private static final int LIGHT_SETTLES = 20;

    /**
     * A stone floor over the whole structure with a roof over part of it. Java's random walks only
     * end on a block that renders solid, which the structure's own floor is not.
     */
    private static void roof(GameTestHelper helper, BlockPos from, BlockPos to) {
        BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(7, 0, 7))
            .forEach(at -> helper.setBlock(at, net.minecraft.world.level.block.Blocks.STONE));
        BlockPos.betweenClosed(from, to)
            .forEach(at -> helper.setBlock(at, net.minecraft.world.level.block.Blocks.STONE));
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
        AddonEntity brute = withGroup(helper, event);
        if (brute != null) brute.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        return brute;
    }

    /** A pig that stays where it is put, blows included, with the health the test asks for. */
    private static Mob pig(GameTestHelper helper, BlockPos where, float health) {
        ServerLevel level = helper.getLevel();
        Entity created = Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "pig")).orElseThrow(), level);
        helper.assertTrue(created instanceof Mob, "A pig could not be made.");

        Mob pig = (Mob) created;
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(pig, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        pig.setNoAi(true);
        pig.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        pig.setHealth(health);
        // A blow would otherwise knock it out of the reach a test is measuring something else at.
        pig.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        level.addFreshEntity(pig);
        return pig;
    }
}
