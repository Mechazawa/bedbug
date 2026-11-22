package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.entity.AddonEntity;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How an entity moves under `minecraft:physics`, `minecraft:buoyant`, `minecraft:behavior.float`,
 * `minecraft:movement.sway` and `minecraft:flocking`, and what `minecraft:breathable` gives it.
 *
 * `tp:bobber` carries one group per field and `tp:shoaler` one per flocking field. Most tests tick
 * their entities by hand inside one server tick, so nothing else moves them in between.
 */
public final class PhysicsGameTests {
    private static final String BOBBER = "tp:bobber";

    private static final String SHOALER = "tp:shoaler";

    /** `has_gravity` false holds an entity where it was let go; a plain one falls. */
    @GameTest()
    public void anEntityWithoutGravityHangsInTheAir(GameTestHelper helper) {
        floor(helper);
        AddonEntity weightless = bobber(helper, "weightless", new BlockPos(1, 3, 1));
        AddonEntity plain = bobber(helper, null, new BlockPos(4, 3, 1));
        if (weightless == null) {
            helper.succeed();
            return;
        }

        double weightlessFrom = weightless.getY();
        double plainFrom = plain.getY();
        live(SETTLE, weightless, plain);
        helper.assertTrue(Math.abs(weightless.getY() - weightlessFrom) < STILL,
            BOBBER + " has no gravity and moved " + (weightless.getY() - weightlessFrom) + " up.");
        helper.assertTrue(plain.getY() < plainFrom - 1, BOBBER + " has gravity and did not fall.");
        helper.succeed();
    }

    /** `has_collision` false lets an entity pushed at a wall go through it; a plain one is stopped. */
    @GameTest()
    public void anEntityWithoutCollisionPassesThroughAWall(GameTestHelper helper) {
        floor(helper);
        AddonEntity ghostly = bobber(helper, "ghostly", new BlockPos(1, 1, 1));
        AddonEntity plain = bobber(helper, null, new BlockPos(1, 1, 5));
        if (ghostly == null) {
            helper.succeed();
            return;
        }

        for (int z : new int[] {1, 5}) {
            helper.setBlock(new BlockPos(3, 1, z), Blocks.STONE);
            helper.setBlock(new BlockPos(3, 2, z), Blocks.STONE);
        }
        Vec3 ahead = along(helper, new BlockPos(1, 1, 1), new BlockPos(3, 1, 1));
        Vec3 ghostlyFrom = ghostly.position();
        Vec3 plainFrom = plain.position();
        for (int tick = 0; tick < SETTLE; tick++) {
            for (AddonEntity pushed : List.of(ghostly, plain)) {
                pushed.setDeltaMovement(ahead.scale(PUSH).add(0, pushed.getDeltaMovement().y, 0));
            }
            live(1, ghostly, plain);
        }
        double ghostlyWent = ghostly.position().subtract(ghostlyFrom).dot(ahead);
        double plainWent = plain.position().subtract(plainFrom).dot(ahead);
        helper.assertTrue(ghostlyWent > WALL_BEYOND,
            BOBBER + " has no collision and went " + ghostlyWent + " towards the wall two blocks off.");
        helper.assertTrue(plainWent < WALL_AT,
            BOBBER + " collides and went " + plainWent + " towards the wall two blocks off.");
        helper.succeed();
    }

    /** `push_towards_closest_space` moves an entity caught inside a block out into the air. */
    @GameTest()
    public void anEntityCaughtInABlockIsPushedOut(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(5, 1, 1), Blocks.STONE);
        AddonEntity squeezing = bobber(helper, "squeezing", new BlockPos(1, 1, 1));
        AddonEntity plain = bobber(helper, null, new BlockPos(5, 1, 1));
        if (squeezing == null) {
            helper.succeed();
            return;
        }

        live(SETTLE, squeezing, plain);
        helper.assertTrue(clear(squeezing), BOBBER + " is pushed out of blocks and is still inside one.");
        helper.assertFalse(clear(plain), BOBBER + " is not pushed out of blocks and left one.");
        helper.succeed();
    }

    /** `apply_gravity` false takes gravity away while afloat, so a lift too weak against it still rises. */
    @GameTest()
    public void aBuoyantEntityWithoutGravityRisesOnAWeakLift(GameTestHelper helper) {
        pool(helper, POOL_DEPTH);
        AddonEntity light = bobber(helper, "light", new BlockPos(2, 1, 2));
        AddonEntity heavy = bobber(helper, "heavy", new BlockPos(4, 1, 4));
        if (light == null) {
            helper.succeed();
            return;
        }

        double lightFrom = light.getY();
        double heavyFrom = heavy.getY();
        live(SETTLE * 2, light, heavy);
        helper.assertTrue(light.getY() > lightFrom + RISE,
            BOBBER + " floats without gravity and rose " + (light.getY() - lightFrom) + ".");
        helper.assertTrue(heavy.getY() <= heavyFrom + STILL,
            BOBBER + " floats with gravity on a weak lift and rose " + (heavy.getY() - heavyFrom) + ".");
        helper.succeed();
    }

    /** `movement_type` `none` holds an entity at the surface, where `bobbing` rises and falls. */
    @GameTest()
    public void aBuoyantEntityBobsOnlyWhenItsMovementSaysSo(GameTestHelper helper) {
        pool(helper, POOL_DEPTH);
        AddonEntity steady = bobber(helper, "steady", new BlockPos(2, 1, 2));
        AddonEntity bobbing = bobber(helper, "bobbing", new BlockPos(4, 1, 4));
        if (steady == null) {
            helper.succeed();
            return;
        }

        live(SETTLE * 3, steady, bobbing);
        double[] steadyRange = {steady.getY(), steady.getY()};
        double[] bobbingRange = {bobbing.getY(), bobbing.getY()};
        for (int tick = 0; tick < SETTLE * 2; tick++) {
            live(1, steady, bobbing);
            widen(steadyRange, steady.getY());
            widen(bobbingRange, bobbing.getY());
        }
        double steadySpan = steadyRange[1] - steadyRange[0];
        double bobbingSpan = bobbingRange[1] - bobbingRange[0];
        helper.assertTrue(bobbingSpan > steadySpan + BOB,
            BOBBER + " bobs " + bobbingSpan + " and holds within " + steadySpan + " at the surface.");
        helper.succeed();
    }

    /** `waves` lifts an entity by `big_wave_speed` times the lift as often as `big_wave_probability` says. */
    @GameTest()
    public void aBigWaveThrowsABuoyantEntityUp(GameTestHelper helper) {
        pool(helper, POOL_DEPTH);
        AddonEntity wavy = bobber(helper, "wavy", new BlockPos(2, 1, 2));
        AddonEntity calm = bobber(helper, "calm", new BlockPos(4, 1, 4));
        if (wavy == null) {
            helper.succeed();
            return;
        }

        double wavyHighest = wavy.getY();
        double calmHighest = calm.getY();
        for (int tick = 0; tick < SETTLE; tick++) {
            live(1, wavy, calm);
            wavyHighest = Math.max(wavyHighest, wavy.getY());
            calmHighest = Math.max(calmHighest, calm.getY());
        }
        helper.assertTrue(wavyHighest > calmHighest + 1,
            BOBBER + " meets a big wave every tick and rose no higher than " + wavyHighest
                + " against " + calmHighest + " in calm water.");
        helper.succeed();
    }

    /** `can_auto_step_from_liquid` lets a floating entity pushed against a bank climb onto it. */
    @GameTest()
    public void aFloatingEntityStepsOntoTheBankOnlyWhenAllowed(GameTestHelper helper) {
        BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(6, 1, 6))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        BlockPos.betweenClosed(new BlockPos(1, 1, 1), new BlockPos(3, 1, 5))
            .forEach(at -> helper.setBlock(at, Blocks.WATER));
        AddonEntity climbing = bobber(helper, "climbing", new BlockPos(2, 1, 2));
        AddonEntity stranded = bobber(helper, "stranded", new BlockPos(2, 1, 4));
        if (climbing == null) {
            helper.succeed();
            return;
        }

        Vec3 ahead = along(helper, new BlockPos(2, 1, 2), new BlockPos(4, 1, 2));
        Vec3 climbingFrom = climbing.position();
        Vec3 strandedFrom = stranded.position();
        live(SETTLE, climbing, stranded);
        for (int tick = 0; tick < SETTLE * 2; tick++) {
            for (AddonEntity pushed : List.of(climbing, stranded)) {
                pushed.setDeltaMovement(ahead.scale(PUSH / 2).add(0, pushed.getDeltaMovement().y, 0));
            }
            live(1, climbing, stranded);
        }
        double climbed = climbing.position().subtract(climbingFrom).dot(ahead);
        double stayed = stranded.position().subtract(strandedFrom).dot(ahead);
        helper.assertTrue(climbed > BANK_BEYOND,
            BOBBER + " may step out of the water and went " + climbed + " towards the bank.");
        helper.assertTrue(stayed < BANK_AT,
            BOBBER + " may not step out of the water and went " + stayed + " towards the bank.");
        helper.succeed();
    }

    /** `drag_down_on_buoyancy_removed` pulls an afloat entity down as its buoyancy leaves it. */
    @GameTest()
    public void anEntityLosingItsBuoyancyIsDraggedDown(GameTestHelper helper) {
        pool(helper, POOL_DEPTH);
        AddonEntity anchored = bobber(helper, "anchored", new BlockPos(2, 1, 2));
        if (anchored == null) {
            helper.succeed();
            return;
        }

        live(SETTLE, anchored);
        EntityEvents.trigger(anchored, "tp:bobber_unanchor", null, ServerHooks.state());
        helper.assertTrue(anchored.getDeltaMovement().y < -DRAG,
            BOBBER + " drags down by 0.7 and moves " + anchored.getDeltaMovement().y + " a tick.");
        helper.succeed();
    }

    /**
     * `chance_per_tick_to_float` is the chance of a stroke up each tick: certain, the entity rises
     * from the bottom of a pool, and never, it stays there.
     */
    @GameTest()
    public void aFloatingGoalStrokesUpAtItsChance(GameTestHelper helper) {
        pool(helper, POOL_DEPTH);
        AddonEntity eager = bobber(helper, "eager", new BlockPos(2, 1, 2));
        AddonEntity idle = bobber(helper, "idle", new BlockPos(4, 1, 4));
        if (eager == null) {
            helper.succeed();
            return;
        }

        double eagerFrom = eager.getY();
        double idleFrom = idle.getY();
        live(SETTLE * 2, eager, idle);
        helper.assertTrue(eager.getY() > eagerFrom + 1,
            BOBBER + " floats at every chance and rose " + (eager.getY() - eagerFrom) + ".");
        helper.assertTrue(idle.getY() <= idleFrom + STILL,
            BOBBER + " floats at no chance and rose " + (idle.getY() - idleFrom) + ".");
        helper.succeed();
    }

    /** `sink_with_passengers` keeps a vehicle down while it carries anyone. */
    @GameTest()
    public void aLadenVehicleSinksWhenItsFloatingSaysSo(GameTestHelper helper) {
        pool(helper, POOL_DEPTH);
        AddonEntity laden = bobber(helper, "laden", new BlockPos(2, 1, 2));
        if (laden == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, new BlockPos(2, 2, 2));
        helper.assertTrue(pig.startRiding(laden, true, false), "A pig could not board " + BOBBER + ".");
        double from = laden.getY();
        live(SETTLE * 2, laden);
        helper.assertTrue(laden.getY() <= from + STILL,
            BOBBER + " sinks with a passenger and rose " + (laden.getY() - from) + ".");
        helper.succeed();
    }

    /** `time_under_water_to_dismount_passengers` puts a rider off once the head has been under that long. */
    @GameTest()
    public void aVehicleUnderWaterPutsItsRiderOffAfterItsTime(GameTestHelper helper) {
        pool(helper, POOL_DEPTH);
        AddonEntity ferry = bobber(helper, "ferry", new BlockPos(2, 1, 2));
        if (ferry == null) {
            helper.succeed();
            return;
        }

        Mob pig = pig(helper, new BlockPos(2, 2, 2));
        helper.assertTrue(pig.startRiding(ferry, true, false), "A pig could not board " + BOBBER + ".");
        live(HALF_A_SECOND, ferry);
        helper.assertTrue(pig.getVehicle() == ferry,
            BOBBER + " put its rider off before its head had been under water for a second.");
        live(SETTLE, ferry);
        helper.assertTrue(pig.getVehicle() == null,
            BOBBER + " kept its rider with its head under water for well over a second.");
        helper.succeed();
    }

    /**
     * `sway_amplitude` pushes a swimmer from side to side across its way. Two swimmers told the same
     * way, one swaying and one at an amplitude of nothing, part across it.
     */
    @GameTest()
    public void aSwayingSwimmerWandersAcrossItsHeading(GameTestHelper helper) {
        BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(7, 4, 7))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        BlockPos.betweenClosed(new BlockPos(1, 1, 1), new BlockPos(6, 4, 6))
            .forEach(at -> helper.setBlock(at, Blocks.WATER));
        AddonEntity swaying = bobber(helper, "swaying", new BlockPos(1, 2, 2));
        AddonEntity straight = bobber(helper, "straight", new BlockPos(1, 2, 5));
        if (swaying == null) {
            helper.succeed();
            return;
        }

        Vec3 ahead = along(helper, new BlockPos(1, 2, 2), new BlockPos(6, 2, 2));
        Vec3 across = new Vec3(-ahead.z, 0, ahead.x);
        Vec3 swayingFrom = swaying.position();
        Vec3 straightFrom = straight.position();
        double apart = 0;
        for (int tick = 0; tick < SETTLE; tick++) {
            for (AddonEntity swimming : List.of(swaying, straight)) {
                Vec3 wanted = swimming.position().add(ahead.scale(AHEAD));
                swimming.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, 1.0);
            }
            live(1, swaying, straight);
            Vec3 swayed = swaying.position().subtract(swayingFrom).subtract(straight.position().subtract(straightFrom));
            apart = Math.max(apart, Math.abs(swayed.dot(across)));
        }
        helper.assertTrue(apart > SWAYED,
            BOBBER + " sways and strayed at most " + apart + " across its way from one that does not.");
        helper.succeed();
    }

    /** `totalSupply`, the spelling Mojang's allay writes, states the breath like `total_supply`. */
    @GameTest()
    public void theCamelCaseSupplyStatesTheBreath(GameTestHelper helper) {
        AddonEntity gasping = bobber(helper, "gasping", new BlockPos(1, 1, 1));
        if (gasping == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(gasping.getMaxAirSupply() == 2 * 20,
            BOBBER + " holds its breath for two seconds and holds " + gasping.getMaxAirSupply() + " ticks of air.");
        helper.succeed();
    }

    /** An entity under water lets bubbles rise unless `generates_bubbles` says no. */
    @GameTest(maxTicks = 60)
    public void anEntityUnderWaterBubblesUnlessItsBreathSaysNo(GameTestHelper helper) {
        pool(helper, POOL_DEPTH);
        AddonEntity bubbling = bobber(helper, "bubbling", new BlockPos(2, 1, 2));
        AddonEntity quiet = bobber(helper, "quiet", new BlockPos(4, 1, 4));
        if (bubbling == null) {
            helper.succeed();
            return;
        }

        Watcher watcher = Watcher.join(helper, new BlockPos(3, 5, 3));
        helper.runAfterDelay(40, () -> {
            watcher.leave();
            helper.assertTrue(watcher.bubblesBy(bubbling) >= 2,
                BOBBER + " breathes under water and let " + watcher.bubblesBy(bubbling) + " bubbles rise.");
            helper.assertTrue(watcher.bubblesBy(quiet) == 0,
                BOBBER + " makes no bubbles and let " + watcher.bubblesBy(quiet) + " rise.");
            helper.succeed();
        });
    }

    /** A flock that with the entity numbers fewer than `low_flock_limit` does not draw it in. */
    @GameTest(maxTicks = FLOCK_TICKS)
    public void aFlockBelowItsLowLimitDrawsNobody(GameTestHelper helper) {
        floor(helper);
        AddonEntity one = shoaler(helper, "huddled", 4);
        if (one == null) {
            helper.succeed();
            return;
        }

        shoaler(helper, "huddled", 2);

        Vec3 from = one.position();
        helper.runAfterDelay(FLOCK_TICKS - 10, () -> {
            helper.assertTrue(one.position().distanceTo(from) < STILL,
                SHOALER + " needs three to flock and drifted " + one.position().distanceTo(from) + " to the other.");
            helper.succeed();
        });
    }

    /** A pair meets a `low_flock_limit` of two, so each is drawn towards the other. */
    @GameTest(maxTicks = FLOCK_TICKS)
    public void aFlockAtItsLowLimitDrawsTogether(GameTestHelper helper) {
        floor(helper);
        AddonEntity one = shoaler(helper, "paired", 4);
        if (one == null) {
            helper.succeed();
            return;
        }

        shoaler(helper, "paired", 2);

        Vec3 west = along(helper, new BlockPos(4, 1, 4), new BlockPos(2, 1, 4));
        Vec3 from = one.position();
        helper.succeedWhen(() -> helper.assertTrue(one.position().subtract(from).dot(west) > DRIFT,
            SHOALER + " flocks in a pair and moved " + one.position().subtract(from).dot(west) + " to the other."));
    }

    /**
     * Under `use_center_of_mass` cohesion pulls towards the middle of the flock, east of the shoaler
     * here; without it towards the nearest flockmate, to the west.
     */
    @GameTest(maxTicks = FLOCK_TICKS)
    public void aFlockFollowsItsMiddleOnlyUnderCentreOfMass(GameTestHelper helper) {
        floor(helper);
        AddonEntity middle = shoaler(helper, "massed", 4);
        if (middle == null) {
            helper.succeed();
            return;
        }

        shoaler(helper, "massed", 2);
        shoaler(helper, "massed", 7);
        shoaler(helper, "massed", 7);
        Vec3 west = along(helper, new BlockPos(4, 1, 4), new BlockPos(2, 1, 4));
        Vec3 from = middle.position();
        helper.succeedWhen(() -> helper.assertTrue(middle.position().subtract(from).dot(west) < -DRIFT,
            SHOALER + " follows the middle of its flock and moved " + middle.position().subtract(from).dot(west)
                + " towards its nearest flockmate."));
    }

    /** Without `use_center_of_mass` cohesion pulls towards the nearest flockmate. */
    @GameTest(maxTicks = FLOCK_TICKS)
    public void aFlockFollowsItsNearestWithoutCentreOfMass(GameTestHelper helper) {
        floor(helper);
        AddonEntity middle = shoaler(helper, "trailing", 4);
        if (middle == null) {
            helper.succeed();
            return;
        }

        shoaler(helper, "trailing", 2);
        shoaler(helper, "trailing", 7);
        shoaler(helper, "trailing", 7);
        Vec3 west = along(helper, new BlockPos(4, 1, 4), new BlockPos(2, 1, 4));
        Vec3 from = middle.position();
        helper.succeedWhen(() -> helper.assertTrue(middle.position().subtract(from).dot(west) > DRIFT,
            SHOALER + " follows its nearest flockmate and moved " + middle.position().subtract(from).dot(west)
                + " towards it."));
    }

    private static final int SETTLE = 20;
    private static final int HALF_A_SECOND = 10;
    private static final int FLOCK_TICKS = 60;
    private static final int POOL_DEPTH = 4;
    private static final double STILL = 0.05;
    private static final double PUSH = 0.25;
    private static final double RISE = 0.2;
    private static final double BOB = 0.1;
    private static final double DRAG = 0.5;
    private static final double AHEAD = 3;
    private static final double SWAYED = 0.3;
    private static final double DRIFT = 0.2;
    private static final double WALL_BEYOND = 2.5;
    private static final double WALL_AT = 1.3;
    private static final double BANK_BEYOND = 2;
    private static final double BANK_AT = 1.6;

    private static void live(int ticks, AddonEntity... entities) {
        for (int tick = 0; tick < ticks; tick++) {
            for (AddonEntity entity : entities) {
                entity.tickCount++;
                entity.tick();
            }
        }
    }

    private static void widen(double[] range, double value) {
        range[0] = Math.min(range[0], value);
        range[1] = Math.max(range[1], value);
    }

    /** The way from one block's middle to another's in the world, which a test's rotation may turn. */
    private static Vec3 along(GameTestHelper helper, BlockPos from, BlockPos to) {
        return Vec3.atCenterOf(helper.absolutePos(to)).subtract(Vec3.atCenterOf(helper.absolutePos(from))).normalize();
    }

    /** A stone floor under the test's whole area, level with the bottom of its blocks. */
    private static void floor(GameTestHelper helper) {
        BlockPos.betweenClosed(BlockPos.ZERO, new BlockPos(7, 0, 7)).forEach(at -> helper.setBlock(at, Blocks.STONE));
    }

    private static boolean clear(Entity entity) {
        return entity.level().noCollision(entity, entity.getBoundingBox().deflate(1.0E-7));
    }

    /** A pool {@link #POOL_DEPTH} deep and five across, walled and floored in stone. */
    private static void pool(GameTestHelper helper, int depth) {
        BlockPos.betweenClosed(new BlockPos(0, 0, 0), new BlockPos(6, depth, 6))
            .forEach(at -> helper.setBlock(at, Blocks.STONE));
        BlockPos.betweenClosed(new BlockPos(1, 1, 1), new BlockPos(5, depth, 5))
            .forEach(at -> helper.setBlock(at, Blocks.WATER));
    }

    /**
     * A bobber with the named group on, or none. Its AI runs, because Java moves a mob only while it
     * does; the bobber has no goal but those its groups bring.
     */
    private static AddonEntity bobber(GameTestHelper helper, String group, BlockPos where) {
        AddonEntity bobber = BehaviourGameTests.spawn(helper, BOBBER, where);
        if (bobber == null) return null;

        if (group != null) EntityEvents.trigger(bobber, "tp:bobber_make_" + group, null, ServerHooks.state());
        bobber.setNoAi(false);
        return bobber;
    }

    /** A shoaler along the middle row with its feet held, so flocking is all that moves it. */
    private static AddonEntity shoaler(GameTestHelper helper, String group, int x) {
        AddonEntity shoaler = BehaviourGameTests.spawn(helper, SHOALER, new BlockPos(x, 1, 4));
        if (shoaler == null) return null;

        shoaler.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        EntityEvents.trigger(shoaler, "tp:shoaler_make_" + group, null, ServerHooks.state());
        shoaler.setNoAi(false);
        return shoaler;
    }

    private static Mob pig(GameTestHelper helper, BlockPos where) {
        Entity created = Platform.createMob(
            BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", "pig")).orElseThrow(),
            helper.getLevel());
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(created, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        ((Mob) created).setNoAi(true);
        helper.getLevel().addFreshEntity(created);
        return (Mob) created;
    }

    /** A player with no client, joined through a connection whose far end is a queue, which sees the particles. */
    private record Watcher(ServerPlayer player, EmbeddedChannel channel, List<Vec3> bubbles) {
        static Watcher join(GameTestHelper helper, BlockPos at) {
            MinecraftServer server = helper.getLevel().getServer();
            GameProfile profile = new GameProfile(UUID.randomUUID(), "Watcher");
            ServerPlayer player =
                new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            server.getPlayerList()
                .placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
            BlockPos spot = helper.absolutePos(at);
            Platform.placeAt(player, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0f, 0f);
            player.setGameMode(GameType.SPECTATOR);
            Watcher watcher = new Watcher(player, channel, new ArrayList<>());
            helper.onEachTick(watcher::drain);
            return watcher;
        }

        long bubblesBy(Entity maker) {
            Vec3 eyes = maker.getEyePosition();
            return bubbles.stream().filter(bubble -> bubble.distanceTo(eyes) < 1).count();
        }

        void leave() {
            player.level().getServer().getPlayerList().remove(player);
        }

        private void drain() {
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                if (packet instanceof ClientboundLevelParticlesPacket particles
                    && particles.getParticle() == ParticleTypes.BUBBLE) {
                    bubbles.add(new Vec3(particles.getX(), particles.getY(), particles.getZ()));
                }
            }
        }
    }
}
