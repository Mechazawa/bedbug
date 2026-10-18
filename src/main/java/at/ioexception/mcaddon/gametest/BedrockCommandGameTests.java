package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Bedrock commands whose forms Java's own command of the same name has no word for, run through
 * `/bedrock` and read back off the world.
 *
 * Each line is written the way a pack writes it. The entities a line acts on are named by their uuid
 * or by a tag of the test's own, because the tests of one run share a level.
 */
public final class BedrockCommandGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /**
     * `/ride` mounts a group only where the whole of it fits, fills a vehicle until it is full,
     * empties it again, brings a vehicle to its rider and summons either half of the pair.
     */
    @GameTest()
    public void rideMountsEmptiesAndSummons(GameTestHelper helper) {
        Entity boat = place(helper, "minecraft:boat", STAND);
        List<Entity> three = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Entity pig = place(helper, "minecraft:pig", STAND.offset(i + 1, 0, 3));
            pig.addTag("t_ride_three");
            three.add(pig);
        }
        String vehicle = boat.getStringUUID();

        helper.assertTrue(BedrockLines.run(helper, "ride @e[tag=t_ride_three] start_riding " + vehicle
            + " teleport_rider if_group_fits") == 0, "three pigs were put in a boat with two seats.");
        helper.assertTrue(boat.getPassengers().isEmpty(), "a group that did not fit left some of itself aboard.");

        helper.assertTrue(BedrockLines.run(helper, "ride @e[tag=t_ride_three] start_riding " + vehicle) == 2,
            "until_full did not seat two of three pigs.");
        helper.assertTrue(boat.getPassengers().size() == 2, "the boat carries " + boat.getPassengers().size() + ".");

        helper.assertTrue(BedrockLines.run(helper, "ride " + vehicle + " evict_riders") == 1,
            "evict_riders emptied nothing.");
        helper.assertTrue(boat.getPassengers().isEmpty(), "evict_riders left riders in the boat.");

        Entity walker = three.get(2);
        BedrockLines.run(helper, "ride " + walker.getStringUUID() + " start_riding " + vehicle + " teleport_ride");
        helper.assertTrue(walker.getVehicle() == boat, "teleport_ride did not seat the pig.");
        helper.assertTrue(boat.position().distanceTo(walker.position()) < 1.5,
            "teleport_ride left the boat where it was rather than bringing it to the pig.");
        BedrockLines.run(helper, "ride " + walker.getStringUUID() + " stop_riding");
        helper.assertFalse(walker.isPassenger(), "stop_riding left the pig in the boat.");

        BedrockLines.run(helper, "ride " + vehicle + " summon_rider pig minecraft:entity_born t_ride_rosie");
        helper.assertTrue(boat.getPassengers().stream().anyMatch(rider -> named(rider, "t_ride_rosie")),
            "summon_rider did not seat a pig named by the line: " + boat.getPassengers());

        Entity rider = three.get(0);
        String pig = rider.getStringUUID();
        BedrockLines.run(helper, "ride " + pig + " summon_ride minecart");
        Entity first = rider.getVehicle();
        helper.assertTrue(first != null && first.getType() == type("minecraft:minecart"),
            "summon_ride did not put the pig in a minecart: " + first);
        helper.assertTrue(BedrockLines.run(helper, "ride " + pig + " summon_ride minecart no_ride_change") == 0
                && BedrockLines.run(helper, "ride " + pig + " summon_ride minecart skip_riders") == 0
                && rider.getVehicle() == first,
            "no_ride_change or skip_riders moved a pig that was already riding.");
        BedrockLines.run(helper, "ride " + pig + " summon_ride minecart reassign_rides");
        helper.assertTrue(rider.getVehicle() != null && rider.getVehicle() != first,
            "reassign_rides did not move the pig into a minecart of its own.");
        helper.succeed();
    }

    /**
     * `/summon` turns, names and starts what it makes: a rotation, a point and an entity to face, a
     * name written before the position, and a spawn event the pack's own entity answers.
     */
    @GameTest()
    public void summonTurnsNamesAndStartsWhatItMakes(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(STAND.offset(2, 0, 2));
        // Whole numbers, which a position argument puts in the middle of the block the way Bedrock does.
        String where = at.getX() + " " + at.getY() + " " + at.getZ();

        BedrockLines.run(helper, "summon pig " + where + " 90 10 minecraft:entity_born t_summon_turned");
        Entity turned = find(helper, "t_summon_turned");
        helper.assertTrue(Math.abs(Mth.wrapDegrees(turned.getYRot() - 90f)) < 0.01f
                && Math.abs(turned.getXRot() - 10f) < 0.01f,
            "the pig faces " + turned.getYRot() + " / " + turned.getXRot() + " rather than 90 / 10.");

        BedrockLines.run(helper, "summon pig t_summon_named " + where);
        helper.assertTrue(find(helper, "t_summon_named").position().distanceTo(turned.position()) < 0.01,
            "a name written before the position did not put the pig there.");

        BedrockLines.run(helper, "summon pig " + where + " facing " + (at.getX() + 6) + " " + at.getY() + " "
            + at.getZ() + " minecraft:entity_born t_summon_east");
        float east = find(helper, "t_summon_east").getYRot();
        helper.assertTrue(Math.abs(Mth.wrapDegrees(east + 90f)) < 1f, "facing a point east left the pig at " + east);

        Entity north = place(helper, "minecraft:armor_stand", STAND.offset(2, 0, -1));
        BedrockLines.run(helper, "summon pig " + where + " facing " + north.getStringUUID()
            + " minecraft:entity_born t_summon_north");
        float facing = find(helper, "t_summon_north").getYRot();
        helper.assertTrue(Math.abs(Mth.wrapDegrees(facing - 180f)) < 1f,
            "facing an entity to the north left the pig at " + facing);

        if (AddonEntities.typeOf("tp:ember") != null) {
            BedrockLines.run(helper, "summon tp:ember " + where + " 0 0 tp:ember_command t_summon_ember");
            helper.succeedWhen(() -> helper.assertTrue(
                Platform.tagsOf(find(helper, "t_summon_ember")).contains("tp_event_command_translated"),
                "the spawn event the line named never ran on the ember."));
            return;
        }
        helper.succeed();
    }

    /**
     * `/replaceitem` fills a horse's two equippable slots, the saddle and then the armour, and `keep`
     * leaves an occupied slot as it was while it fills an empty one. 1.20.1 has no test of a slot,
     * so the line keeping one is reported there instead.
     */
    @GameTest()
    public void replaceitemFillsEquippableSlotsAndKeepsAnOccupiedOne(GameTestHelper helper) {
        //? if >=26 {
        net.minecraft.world.entity.animal.equine.AbstractHorse horse =
            (net.minecraft.world.entity.animal.equine.AbstractHorse) place(helper, "minecraft:horse", STAND);
        //?} else {
        /*net.minecraft.world.entity.animal.horse.AbstractHorse horse =
            (net.minecraft.world.entity.animal.horse.AbstractHorse) place(helper, "minecraft:horse", STAND);
        *///?}
        horse.setTamed(true);
        String uuid = horse.getStringUUID();

        BedrockLines.run(helper, "replaceitem entity " + uuid + " slot.equippable 0 saddle");
        helper.assertTrue(horse.isSaddled(), "slot.equippable 0 did not saddle the horse.");
        BedrockLines.run(helper, "replaceitem entity " + uuid + " slot.equippable 1 iron_horse_armor");
        helper.assertTrue(Platform.horseArmourOf(horse).is(Items.IRON_HORSE_ARMOR),
            "slot.equippable 1 did not put armour on the horse: " + Platform.horseArmourOf(horse));

        if (!Platform.testsSlots()) {
            helper.succeed();
            return;
        }
        BedrockLines.run(helper, "replaceitem entity " + uuid + " slot.equippable 1 keep golden_horse_armor");
        helper.assertTrue(Platform.horseArmourOf(horse).is(Items.IRON_HORSE_ARMOR),
            "keep replaced the armour the horse already wore.");

        LivingEntity stand = (LivingEntity) place(helper, "minecraft:armor_stand", STAND.offset(3, 0, 3));
        BedrockLines.run(helper, "replaceitem entity " + stand.getStringUUID()
            + " slot.armor.head 0 keep diamond_helmet");
        helper.assertTrue(stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET),
            "keep did not fill an empty slot.");
        helper.succeed();
    }

    /**
     * `/structure` saves a region to memory and to disk, deletes it, and places it again mirrored
     * both ways, turned, without its blocks, at no integrity, into water with and without
     * waterlogging, and a layer at a time.
     *
     * The region is two layers of six blocks, no two alike, so every placement can be checked block
     * by block against where Bedrock puts each one: the lowest corner of what it fills at the
     * position the line names, whichever way it was turned.
     */
    @GameTest()
    public void structureSavesAndLoadsInBedrocksForms(GameTestHelper helper) {
        Map<BlockPos, Block> region = new LinkedHashMap<>();
        region.put(new BlockPos(0, 0, 0), Blocks.STONE);
        region.put(new BlockPos(1, 0, 0), Blocks.DIRT);
        region.put(new BlockPos(2, 0, 0), Blocks.GOLD_BLOCK);
        region.put(new BlockPos(0, 0, 1), Blocks.OAK_PLANKS);
        region.put(new BlockPos(2, 0, 1), Blocks.OAK_SLAB);
        region.put(new BlockPos(1, 1, 0), Blocks.GLASS);
        BlockPos source = new BlockPos(0, 1, 0);
        region.forEach((offset, block) -> helper.setBlock(source.offset(offset), block));

        String name = "t_structure_" + helper.absolutePos(BlockPos.ZERO).asLong();
        BedrockLines.run(helper, "structure save " + name + " " + at(helper, source) + " "
            + at(helper, source.offset(2, 1, 1)) + " false");
        BedrockLines.run(helper, "structure save " + name + "_disk " + at(helper, source) + " "
            + at(helper, source.offset(2, 1, 1)) + " disk");
        MinecraftServer server = helper.getLevel().getServer();
        StructureTemplateManager templates = server.getStructureManager();
        helper.assertFalse(Files.exists(Platform.savedStructureFile(templates, Platform.id("mystructure", name))),
            "a structure saved to memory was written to disk.");
        java.nio.file.Path disk = Platform.savedStructureFile(templates, Platform.id("mystructure", name + "_disk"));
        helper.assertTrue(Files.exists(disk), "a structure saved to disk was not written to " + disk.toAbsolutePath());
        BedrockLines.run(helper, "structure delete " + name + "_disk");
        helper.assertFalse(Files.exists(disk) || templates.get(Platform.id("mystructure", name + "_disk")).isPresent(),
            "structure delete left the structure behind.");

        BlockPos mirrored = new BlockPos(4, 1, 0);
        BedrockLines.run(helper, "structure load " + name + " " + at(helper, mirrored) + " 0_degrees xz");
        region.forEach((offset, block) -> expect(helper, mirrored.offset(2 - offset.getX(), offset.getY(),
            1 - offset.getZ()), block, "mirrored both ways"));

        BlockPos turned = new BlockPos(0, 1, 3);
        BedrockLines.run(helper, "structure load " + name + " " + at(helper, turned) + " 90_degrees");
        region.forEach((offset, block) -> expect(helper, turned.offset(1 - offset.getZ(), offset.getY(),
            offset.getX()), block, "turned a quarter"));

        BlockPos bare = new BlockPos(4, 1, 3);
        BedrockLines.run(helper, "structure load " + name + " " + at(helper, bare) + " 0_degrees none true false");
        BlockPos crumbled = new BlockPos(4, 4, 3);
        BedrockLines.run(helper, "structure load " + name + " " + at(helper, crumbled)
            + " 0_degrees none true true false 0 seven");
        region.forEach((offset, block) -> {
            expect(helper, bare.offset(offset), Blocks.AIR, "loaded without its blocks");
            expect(helper, crumbled.offset(offset), Blocks.AIR, "loaded at no integrity");
        });

        BlockPos wet = new BlockPos(4, 4, 0);
        BlockPos dry = new BlockPos(0, 6, 3);
        helper.setBlock(wet.offset(2, 0, 1), Blocks.WATER);
        helper.setBlock(dry.offset(2, 0, 1), Blocks.WATER);
        BedrockLines.run(helper, "structure load " + name + " " + at(helper, wet) + " 0_degrees none true true true");
        BedrockLines.run(helper, "structure load " + name + " " + at(helper, dry));
        helper.assertTrue(helper.getBlockState(wet.offset(2, 0, 1)).getValue(BlockStateProperties.WATERLOGGED),
            "a slab loaded into water with waterlogged true lost the water.");
        helper.assertFalse(helper.getBlockState(dry.offset(2, 0, 1)).getValue(BlockStateProperties.WATERLOGGED),
            "a slab loaded into water by default kept the water, which Bedrock's default does not.");

        BlockPos animated = new BlockPos(0, 4, 0);
        BedrockLines.run(helper, "structure load " + name + " " + at(helper, animated)
            + " 0_degrees none layer_by_layer 1");
        expect(helper, animated, Blocks.AIR, "the first moment of a structure loaded layer by layer");
        helper.succeedWhen(() -> region.forEach((offset, block) ->
            expect(helper, animated.offset(offset), block, "loaded layer by layer")));
    }

    /**
     * `/tickingarea` forces the chunks of a rectangle and of a circle under their names, marks one to
     * preload, lists them and lets go of a rectangle removed by a position inside it, and
     * `/schedule on_area_loaded` holds a function back until a ticking area loads the chunk it waits
     * for, which the synthetic pack's `tp/loaded` counts.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void tickingAreasForceChunksAndAScheduleWaitsForThem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(BlockPos.ZERO);
        String tag = Long.toString(Math.abs(here.asLong()), 36);
        int x = here.getX();
        int z = here.getZ();

        BedrockLines.run(helper, "tickingarea add " + x + " 0 " + z + " " + (x + 16) + " 0 " + z + " t_rect_" + tag);
        helper.assertTrue(Platform.forcedChunks(level).contains(Platform.chunkKey(x >> 4, z >> 4))
                && Platform.forcedChunks(level).contains(Platform.chunkKey((x + 16) >> 4, z >> 4)),
            "a rectangle did not force both chunks it covers.");

        int farX = x + 20000;
        BedrockLines.run(helper, "tickingarea add circle " + farX + " 0 " + z + " 1 t_circle_" + tag + " true");
        for (int[] step : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            helper.assertTrue(Platform.forcedChunks(level).contains(
                    Platform.chunkKey((farX >> 4) + step[0], (z >> 4) + step[1])),
                "a circle of one chunk did not force the chunk " + step[0] + ", " + step[1] + " from its middle.");
        }
        helper.assertFalse(Platform.forcedChunks(level).contains(Platform.chunkKey((farX >> 4) + 1, (z >> 4) + 1)),
            "a circle of one chunk forced the chunk on its diagonal.");
        helper.assertTrue(BedrockLines.run(helper, "tickingarea preload t_circle_" + tag) == 1,
            "an area added with preload true is not marked to preload.");
        helper.assertTrue(BedrockLines.run(helper, "tickingarea preload t_circle_" + tag + " false") == 0,
            "an area whose preload was set false is still marked.");
        helper.assertTrue(BedrockLines.run(helper, "tickingarea list all-dimensions") >= 2,
            "the list does not hold both areas.");

        helper.assertTrue(BedrockLines.run(helper, "tickingarea remove " + (x + 2) + " 70 " + z) == 1,
            "removing by a position inside the rectangle removed nothing.");
        helper.assertFalse(Platform.forcedChunks(level).contains(Platform.chunkKey(x >> 4, z >> 4)),
            "a removed rectangle still forces its chunk.");
        BedrockLines.run(helper, "tickingarea remove t_circle_" + tag);

        if (AddonEntities.typeOf("tp:ember") == null) {
            helper.succeed();
            return;
        }
        int waitX = x - 30000;
        helper.assertFalse(level.hasChunk(waitX >> 4, z >> 4), "the chunk the schedule waits for is loaded already.");
        BedrockLines.run(helper, "schedule on_area_loaded add " + waitX + " 0 " + z + " " + waitX + " 0 " + z
            + " tp/loaded");
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(loadedCount(helper) == 0, "the function ran before its area loaded.");
            BedrockLines.run(helper, "tickingarea add " + waitX + " 0 " + z + " " + waitX + " 0 " + z
                + " t_wait_" + tag);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(loadedCount(helper) == 1, "the function did not run once its area loaded.");
            BedrockLines.run(helper, "tickingarea remove t_wait_" + tag);
        });
    }

    /**
     * `save query` refuses while the world is still being written and lists its files once `save
     * hold` has stopped that; `setmaxplayers` moves the limit a login is checked against; `packstack`
     * lists Java's data packs with and without the game's own; and `permission` makes a player an
     * operator and a member again.
     *
     * Each of these asks for more than a function's authority, as it does in Bedrock, so they are
     * typed by the console after `/bedrock` rather than run the way a pack runs a line.
     */
    @GameTest()
    public void theServersOwnCommandsAnswerFromTheServer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(typed(helper, "save query").stream().anyMatch(line -> line.contains("not been turned off")),
            "save query answered while writing was on.");
        List<String> listed;
        typed(helper, "save hold");
        try {
            listed = typed(helper, "save query");
        } finally {
            typed(helper, "save resume");
        }
        helper.assertTrue(listed.stream().anyMatch(line -> line.contains("level.dat:")),
            "save query did not list level.dat once writing was held: " + listed);

        int limit = server.getPlayerList().getMaxPlayers();
        typed(helper, "setmaxplayers " + (limit + 7));
        int raised = server.getPlayerList().getMaxPlayers();
        typed(helper, "setmaxplayers " + limit);
        helper.assertTrue(raised == limit + 7, "setmaxplayers left the limit at " + raised + ".");

        long everything = typed(helper, "packstack server").stream().filter(line -> line.startsWith("- ")).count();
        List<String> added = typed(helper, "packstack server exclude-vanilla");
        helper.assertTrue(everything == server.getPackRepository().getSelectedPacks().size()
                && added.stream().noneMatch(line -> line.startsWith("- vanilla:")),
            "packstack server listed " + everything + " packs, and without the game's own " + added);

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            player.addTag("t_permission");
            typed(helper, "permission set @a[tag=t_permission] operator");
            helper.assertTrue(Platform.isOperator(server, player), "permission set operator made no operator.");
            helper.assertFalse(typed(helper, "ops list").stream().anyMatch(line -> line.startsWith("0 operator")),
                "ops list named no operator.");
            typed(helper, "ops set @a[tag=t_permission] member");
            helper.assertFalse(Platform.isOperator(server, player), "permission set member left an operator.");
        } finally {
            Platform.setOperator(server, player, false);
            server.getPlayerList().remove(player);
        }
        helper.succeed();
    }

    /**
     * `place featurerule` places the synthetic pack's ore rule into stone the test lays down far from
     * every other test: six veins of the pack's own lamp between the heights the rule states.
     */
    @GameTest()
    public void aFeatureRuleIsPlacedByItsName(GameTestHelper helper) {
        if (AddonEntities.typeOf("tp:ember") == null || !Platform.convertsWorldgen()) {
            helper.succeed();
            return;
        }

        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos((helper.absolutePos(BlockPos.ZERO).getX() >> 4 << 4) + 4096, 0,
            helper.absolutePos(BlockPos.ZERO).getZ() >> 4 << 4);
        for (BlockPos stone : BlockPos.betweenClosed(origin.offset(0, -48, 0), origin.offset(15, 64, 15))) {
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), 2);
        }
        List<String> heard = typed(helper, "place featurerule tp:shard_ore_rule " + origin.getX() + " 0 "
            + origin.getZ());

        Block lamp = at.ioexception.mcaddon.registry.AddonBlocks.named("tp:lamp");
        long lamps = 0;
        for (BlockPos found : BlockPos.betweenClosed(origin.offset(-8, -56, -8), origin.offset(23, 72, 23))) {
            if (level.getBlockState(found).is(lamp)) lamps++;
        }
        helper.assertTrue(lamps > 0, "the ore rule placed no lamp into the stone: " + heard);
        helper.succeed();
    }

    /**
     * `/gametest create` lays a test out under a structure block naming it, `pos` says where the
     * source stands against that area, `runthis` runs the test the structure block names there, and
     * `runsetuntilfail` runs the synthetic pack's set again until its test fails, which it does on
     * its second round.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void gameTestRunsWhatAStructureBlockNames(GameTestHelper helper) {
        if (AddonEntities.typeOf("tp:ember") == null) {
            helper.succeed();
            return;
        }

        BlockPos stand = new BlockPos(2, 4, 1);
        String here = "execute positioned " + at(helper, stand) + " run ";
        typed(helper, here + "gametest create tp:hut_stands 3 3 3");
        BlockPos block = stand.offset(0, 0, 3);
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(block))
                instanceof net.minecraft.world.level.block.entity.StructureBlockEntity structure
                && structure.getStructureName().equals("tp:hut_stands")
                && structure.getStructureSize().equals(new net.minecraft.core.Vec3i(3, 3, 3)),
            "gametest create left no structure block naming the test over a 3 by 3 by 3 area.");
        expect(helper, block.offset(2, 1, 2), Blocks.POLISHED_ANDESITE, "laid out by gametest create");
        expect(helper, block.offset(1, 0, -1), Blocks.COMMAND_BLOCK, "laid out by gametest create");

        List<String> where = typed(helper, here + "gametest pos");
        helper.assertTrue(where.stream().anyMatch(line -> line.endsWith("tp:hut_stands: 0, -1, -3")),
            "gametest pos did not place the source against the area: " + where);

        List<String> ran = typed(helper, here + "gametest runthis");
        List<String> rounds = typed(helper, "gametest runsetuntilfail tp:until_fail");
        helper.succeedWhen(() -> {
            helper.assertTrue(ran.contains("tp:hut_stands passed."), "runthis reached no verdict: " + ran);
            expect(helper, block.offset(1, 1, 1), Blocks.STONE, "the hut built where the structure block points");
            helper.assertTrue(rounds.stream().anyMatch(line -> line.startsWith("tp:fails_the_second_time failed")),
                "runsetuntilfail never reached the round its test fails in: " + rounds);
            helper.assertTrue(rounds.stream().filter(line -> line.startsWith("tp:fails_the_second_time ")).count() == 2,
                "runsetuntilfail ran its set a number of times other than twice: " + rounds);
        });
    }

    /** What the console hears back from `/bedrock <line>`, typed as an operator would type it. */
    private static List<String> typed(GameTestHelper helper, String line) {
        List<String> heard = new ArrayList<>();
        CommandSource console = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                heard.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(console),
            "bedrock " + line);
        return heard;
    }

    private static int loadedCount(GameTestHelper helper) {
        net.minecraft.world.scores.Scoreboard scores = helper.getLevel().getServer().getScoreboard();
        net.minecraft.world.scores.Objective objective = scores.getObjective("tp_loaded");
        Integer score = objective == null ? null : Platform.score(scores, objective, "area");
        return score == null ? 0 : score;
    }

    private static String at(GameTestHelper helper, BlockPos relative) {
        BlockPos absolute = helper.absolutePos(relative);
        return absolute.getX() + " " + absolute.getY() + " " + absolute.getZ();
    }

    private static void expect(GameTestHelper helper, BlockPos relative, Block block, String placement) {
        Block found = helper.getBlockState(relative).getBlock();
        helper.assertTrue(found == block, "the structure " + placement + " holds " + found + " at " + relative
            + " where " + block + " belongs.");
    }

    private static boolean named(Entity entity, String name) {
        return entity.getCustomName() != null && entity.getCustomName().getString().equals(name);
    }

    /** The entity the test summoned under that name, which a failed lookup fails the test over. */
    private static Entity find(GameTestHelper helper, String name) {
        for (Entity entity : helper.getLevel().getAllEntities()) {
            if (named(entity, name)) return entity;
        }
        helper.fail("nothing named " + name + " was summoned.");
        throw new IllegalStateException();
    }

    private static EntityType<?> type(String bedrock) {
        return AddonEntities.typeOf(bedrock);
    }

    private static Entity place(GameTestHelper helper, String bedrock, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        Entity entity = Platform.createMob(type(bedrock), level);
        helper.assertTrue(entity != null, bedrock + " could not be created.");

        BlockPos where = helper.absolutePos(relative);
        Platform.placeAt(entity, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(entity);
        return entity;
    }
}
