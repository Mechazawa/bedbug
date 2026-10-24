package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerDataHolder;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * What the test pack's own script reads back about the world when a player runs one of its probes.
 *
 * Each probe is a script event the pack's script answers by tagging whoever ran it, so the whole
 * path is under test: the module exporting the name, the script calling it, the bridge carrying the
 * call across and the game doing what was asked. With no pack script loaded each test succeeds
 * without asserting.
 */
public final class ScriptGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /** The platform's floor, which the older versions place one block higher. */
    //? if >=26 {
    private static final int FLOOR = 0;
    //?} else {
    /*private static final int FLOOR = 1;
    *///?}

    /** Standing on the platform's floor. */
    private static final BlockPos ON_FLOOR = new BlockPos(1, FLOOR + 1, 1);

    /** The test pack's script module, whose folder under `config/` is its own. */
    private static final String SCRIPT_MODULE = "6d1f3b52-8c4e-4a7d-9e21-5b0c7f3a9d14";

    /** Long enough for a script event and the world events it raises to have been delivered. */
    private static final int DELIVERED = 3;

    /**
     * A player's fog stack answers a script that pushes, pops and removes layers on it.
     *
     * The pack's fog goes on twice, once under a tag of the pack's own and once under none, which
     * Bedrock files as `untagged`; popping the tag takes off the one layer carrying it.
     */
    @GameTest()
    public void aPlayersFogStackAnswersTheScriptStackingIt(GameTestHelper helper) {
        probe(helper, "tp:fog", "", player -> {
            expectTag(helper, player, "tp:fog_pushed_0_1_probe+untagged");
            expectTag(helper, player, "tp:fog_popped_tp:dusk_1");
            expectTag(helper, player, "tp:fog_left_0");
        });
    }

    /**
     * A script's clone keeps what its filter names and a move leaves the place it moved from empty.
     *
     * The row is a stone with a dirt block east of it. Copying it with a filter naming stone puts
     * the stone two blocks up and nothing beside it, and moving the whole row puts both four blocks
     * up and leaves air where they were.
     */
    @GameTest(structure = "mcaddon:platform")
    public void aScriptClonesWhatItsFilterKeepsAndMovesTheRest(GameTestHelper helper) {
        BlockPos stone = ON_FLOOR;
        BlockPos dirt = ON_FLOOR.east();
        helper.setBlock(stone, Blocks.STONE);
        helper.setBlock(dirt, Blocks.DIRT);
        BlockPos at = helper.absolutePos(stone);

        probe(helper, "tp:clone", at.getX() + " " + at.getY() + " " + at.getZ(), player -> {
            helper.assertBlockPresent(Blocks.STONE, stone.above(2));
            helper.assertBlockPresent(Blocks.AIR, dirt.above(2));
            helper.assertBlockPresent(Blocks.STONE, stone.above(4));
            helper.assertBlockPresent(Blocks.DIRT, dirt.above(4));
            helper.assertBlockPresent(Blocks.AIR, stone);
            helper.assertBlockPresent(Blocks.AIR, dirt);
        });
    }

    /**
     * A script reads `config/` as `/reloadconfig` last left it: the default folder's variable, the
     * module folder's variable over the default's, and a secret by its name alone.
     *
     * The greeting and the secret are new on every run, so a script still reading what it read as it
     * started cannot name them.
     */
    @GameTest()
    public void aScriptReadsTheConfigReloadconfigLastRead(GameTestHelper helper) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        String greeting = "greeting" + System.nanoTime();
        String token = "hidden" + System.nanoTime();
        Path config = FabricLoader.getInstance().getGameDir().resolve("config");
        write(config.resolve("default/variables.json"), "{\"tp_shared\": \"default\", \"tp_greeting\": \"default\"}");
        write(config.resolve(SCRIPT_MODULE + "/variables.json"), "{\"tp_greeting\": \"" + greeting + "\"}");
        write(config.resolve(SCRIPT_MODULE + "/secrets.json"), "{\"tp_token\": \"" + token + "\"}");
        // Typed by the console: an owner's command does not read with a function's authority.
        helper.getLevel().getServer().getCommands().performPrefixedCommand(
            helper.getLevel().getServer().createCommandSourceStack().withSuppressedOutput(), "bedrock reloadconfig");

        probe(helper, "tp:config", "", player -> {
            expectTag(helper, player, "tp:config_default_" + greeting);
            expectTag(helper, player, "tp:dedicated_false");
            expectTag(helper, player, "tp:secret_tp_token_true");
            helper.assertTrue(Platform.tagsOf(player).stream().anyMatch(tag -> tag.startsWith("tp:secret_shown_"))
                    && Platform.tagsOf(player).stream().noneMatch(tag -> tag.contains(token)),
                "The pack's script left " + Platform.tagsOf(player) + ", which should show the secret and not "
                    + token + ".");
        });
    }

    /** A script makes a player an operator and takes it away, and each second try is refused. */
    @GameTest()
    public void aScriptMakesAndUnmakesAnOperator(GameTestHelper helper) {
        probe(helper, "tp:operator", "", player -> {
            expectTag(helper, player, "tp:op_done");
            expectTag(helper, player, "tp:op_again_PlayerAlreadyOpError");
            expectTag(helper, player, "tp:deop_done");
            expectTag(helper, player, "tp:deop_again_CannotDeopPlayerError");
            helper.assertFalse(Platform.isOperator(helper.getLevel().getServer(), player),
                "The player the script made an operator and unmade is an operator still.");
        });
    }

    /** A row of three blocks hands back its first block as the nearest to it and its last as farthest. */
    @GameTest()
    public void aVolumeHandsBackItsNearestAndFarthestPositions(GameTestHelper helper) {
        probe(helper, "tp:row", "", player -> expectTag(helper, player, "tp:row_0_2"));
    }

    /**
     * A block tells a script what a note block plays off it, and keeps nothing a block entity of its
     * own would. Stone carries the bass drum and plays nothing under a note block.
     */
    @GameTest(structure = "mcaddon:platform")
    public void aBlockAnswersWhatANoteBlockPlaysOffIt(GameTestHelper helper) {
        helper.setBlock(ON_FLOOR, Blocks.STONE);
        BlockPos stone = helper.absolutePos(ON_FLOOR);
        probe(helper, "tp:block", stone.getX() + " " + stone.getY() + " " + stone.getZ(), player -> {
            expectTag(helper, player, "tp:plays_note.bd_note.none");
            expectTag(helper, player, "tp:keeps_nothing");
        });
    }

    /** The pack's own lamp asks for a store, which `minecraft:dynamic_properties` writes into. */
    @GameTest(structure = "mcaddon:platform")
    public void aBlockKeepingAStoreKeepsWhatAScriptPutsInIt(GameTestHelper helper) {
        net.minecraft.world.level.block.Block lamp = BuiltInRegistries.BLOCK
            .getOptional(Platform.id("tp", "lamp")).orElse(null);
        if (lamp == null) {
            helper.succeed();
            return;
        }

        helper.setBlock(ON_FLOOR, lamp.defaultBlockState());
        BlockPos at = helper.absolutePos(ON_FLOOR);
        probe(helper, "tp:block", at.getX() + " " + at.getY() + " " + at.getZ(),
            player -> expectTag(helper, player, "tp:keeps_7"));
    }

    /**
     * A script hears a wolf being tamed and may refuse to let another be.
     *
     * The pack refuses to let anything carrying `tp:untameable` be tamed and tags whatever a player
     * tamed, so one of the two wolves ends up tame and tagged and the other neither.
     */
    @GameTest()
    public void aScriptHearsATameAndMayRefuseOne(GameTestHelper helper) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        TamableAnimal willing = wolf(helper);
        TamableAnimal unwilling = wolf(helper);
        unwilling.addTag("tp:untameable");
        willing.tame(player);
        unwilling.tame(player);
        helper.runAfterDelay(DELIVERED, () -> {
            helper.getLevel().getServer().getPlayerList().remove(player);
            helper.assertTrue(willing.isTame(), "A wolf nothing refused to have tamed was not tamed.");
            helper.assertTrue(Platform.tagsOf(willing).contains("tp:tamed_by_a_player"),
                "The pack's script never heard a wolf being tamed.");
            helper.assertFalse(unwilling.isTame(),
                "The pack's script refused a tame and the wolf was tamed anyway.");
            helper.succeed();
        });
    }

    /**
     * A script hears a player crouch and stand up again.
     *
     * A crouch is found by comparing the player against the tick before, so the player stands for a
     * few ticks first: one already crouching when first looked at has done nothing to report.
     */
    @GameTest()
    public void aScriptHearsAPlayerCrouchAndStand(GameTestHelper helper) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.runAfterDelay(DELIVERED, () -> player.setShiftKeyDown(true));
        helper.runAfterDelay(DELIVERED * 2, () -> player.setShiftKeyDown(false));
        helper.runAfterDelay(DELIVERED * 3, () -> {
            helper.getLevel().getServer().getPlayerList().remove(player);
            expectTag(helper, player, "tp:crouched");
            expectTag(helper, player, "tp:stood_up");
            helper.succeed();
        });
    }

    /**
     * Using an item reaches the custom component it names as a key of its own, with its parameters.
     *
     * `tp:tally` writes `tp:counting` the way a pack does from 1.21.90, and the pack's script tags
     * whoever used the item with the step the item gave the component.
     */
    @GameTest()
    public void anItemsOwnComponentRunsWithItsParameters(GameTestHelper helper) {
        net.minecraft.world.item.Item tally = BuiltInRegistries.ITEM
            .getOptional(Platform.id("tp", "tally")).orElse(null);
        if (tally == null || ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        net.minecraft.world.item.ItemStack held = new net.minecraft.world.item.ItemStack(tally);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
        held.use(helper.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
        helper.getLevel().getServer().getPlayerList().remove(player);
        expectTag(helper, player, "tp:counted_3");
        helper.succeed();
    }

    /**
     * A villager taking a job raises the `minecraft:become_*` event Bedrock's villager raises, and
     * answers a script with the numbers Bedrock's `villager_v2` states its job and its biome by.
     *
     * The villager becomes a snowy farmer, which Bedrock numbers 1 and 4, and its face is one of six
     * whatever it is. The pack's script tags it with the event and with what each component read
     * when the event reached it.
     */
    @GameTest()
    public void aVillagerTakingAJobTellsTheScriptWhatItBecame(GameTestHelper helper) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", "villager")).orElseThrow();
        Villager villager = (Villager) Platform.createMob(type, helper.getLevel());
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(villager, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        villager.setNoAi(true);
        helper.getLevel().addFreshEntity(villager);

        helper.runAfterDelay(1, () -> villager.setVillagerData(snowyFarmer(villager, helper.getLevel())));
        helper.runAfterDelay(1 + DELIVERED, () -> {
            expectTag(helper, villager, "tp:become_farmer");
            expectTag(helper, villager, "tp:variant_1");
            expectTag(helper, villager, "tp:mark_variant_4");
            helper.assertTrue(Platform.tagsOf(villager).stream().anyMatch(tag -> tag.matches("tp:skin_id_[0-5]")),
                "The pack's script read no skin between 0 and 5 off the villager: " + Platform.tagsOf(villager));
            helper.succeed();
        });
    }

    /**
     * A villager's job reaches a pack's script as the families Bedrock's villager states for it: a
     * jobless one is `unskilled`, a nitwit `nitwit`, and a farmer `farmer` and `peasant`, while a
     * jobless zombie villager is `unskilled` and no `peasant`. A pack choosing between a trade and a
     * refusal by these families greets every jobless villager as a trader when they are missing.
     */
    @GameTest()
    public void aVillagersFamiliesNameItsJob(GameTestHelper helper) {
        Villager jobless = villager(helper, "villager", new BlockPos(3, 2, 1));
        Villager nitwit = villager(helper, "villager", new BlockPos(1, 2, 3));
        Villager farmer = villager(helper, "villager", new BlockPos(3, 2, 3));
        ZombieVillager zombie = villager(helper, "zombie_villager", new BlockPos(4, 2, 2));
        helper.runAfterDelay(1, () -> {
            zombie.setVillagerData(withProfession(zombie, helper.getLevel(), VillagerProfession.NONE));
            nitwit.setVillagerData(withProfession(nitwit, helper.getLevel(), VillagerProfession.NITWIT));
            farmer.setVillagerData(withProfession(farmer, helper.getLevel(), VillagerProfession.FARMER));
        });
        helper.runAfterDelay(2, () -> probe(helper, "tp:families", "", player -> {
            expectTag(helper, jobless, "tp:family_unskilled");
            expectTag(helper, jobless, "tp:family_peasant");
            expectTag(helper, nitwit, "tp:family_nitwit");
            expectTag(helper, farmer, "tp:family_farmer");
            expectTag(helper, farmer, "tp:family_peasant");
            expectTag(helper, zombie, "tp:family_unskilled");
            helper.assertFalse(Platform.tagsOf(farmer).contains("tp:family_unskilled"),
                "A farmer reads as jobless: " + Platform.tagsOf(farmer));
            helper.assertFalse(Platform.tagsOf(zombie).contains("tp:family_peasant"),
                "A zombie villager carries the living villager's trade: " + Platform.tagsOf(zombie));
        }));
    }

    private static <T extends Entity> T villager(GameTestHelper helper, String name, BlockPos relative) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", name)).orElseThrow();
        @SuppressWarnings("unchecked")
        T villager = (T) Platform.createMob(type, helper.getLevel());
        BlockPos at = helper.absolutePos(relative);
        Platform.placeAt(villager, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        ((net.minecraft.world.entity.Mob) villager).setNoAi(true);
        helper.getLevel().addFreshEntity(villager);
        return villager;
    }

    //? if >=1.21.5 {
    private static VillagerData withProfession(VillagerDataHolder villager, ServerLevel level,
        net.minecraft.resources.ResourceKey<VillagerProfession> profession) {
        return villager.getVillagerData().withProfession(level.registryAccess(), profession);
    }
    //?} else {
    /*private static VillagerData withProfession(VillagerDataHolder villager, ServerLevel level,
        VillagerProfession profession) {
        return villager.getVillagerData().setProfession(profession);
    }
    *///?}

    /**
     * A young villager and a young rabbit two ticks from grown each raise the event Bedrock's own
     * entity file names for growing up, and the script reads each grown when it arrives.
     */
    @GameTest()
    public void aYoungVanillaMobGrowingUpTellsTheScript(GameTestHelper helper) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        AgeableMob villager = youngster(helper, "villager", STAND);
        AgeableMob rabbit = youngster(helper, "rabbit", STAND.east(2));
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(Platform.tagsOf(villager).stream().noneMatch(tag -> tag.startsWith("tp:heard_")),
                "A young villager told the script it grew up before it had: " + Platform.tagsOf(villager));
        });
        helper.runAfterDelay(4 + DELIVERED, () -> {
            expectTag(helper, villager, "tp:heard_ageable_grow_up");
            expectTag(helper, villager, "tp:grown_when_heard");
            expectTag(helper, rabbit, "tp:heard_grow_up");
            helper.succeed();
        });
    }

    private static AgeableMob youngster(GameTestHelper helper, String name, BlockPos where) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", name)).orElseThrow();
        AgeableMob mob = (AgeableMob) Platform.createMob(type,
            helper.getLevel());
        BlockPos at = helper.absolutePos(where);
        Platform.placeAt(mob, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        mob.setNoAi(true);
        mob.setAge(-3);
        helper.getLevel().addFreshEntity(mob);
        return mob;
    }

    private static VillagerData snowyFarmer(Villager villager, ServerLevel level) {
        //? if >=1.21.5 {
        return villager.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER)
            .withType(level.registryAccess(), VillagerType.SNOW);
        //?} else {
        /*return villager.getVillagerData().setProfession(VillagerProfession.FARMER).setType(VillagerType.SNOW);
        *///?}
    }

    private static TamableAnimal wolf(GameTestHelper helper) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE
            .getOptional(Platform.id("minecraft", "wolf")).orElseThrow();
        ServerLevel level = helper.getLevel();
        Entity wolf = Platform.createMob(type, level);
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(wolf, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(wolf);
        return (TamableAnimal) wolf;
    }

    /**
     * Runs one of the pack's probes as a player standing in the test, then checks what it left.
     *
     * The player is taken out of the world again whatever the checks find, so no later test meets a
     * stranger in the player list.
     */
    private static void probe(GameTestHelper helper, String id, String message,
        Consumer<ServerPlayer> check) {
        if (ServerHooks.scripts() == null) {
            helper.succeed();
            return;
        }

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos at = helper.absolutePos(STAND);
        Platform.placeAt(player, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        try {
            BedrockLines.run(helper, Platform.commandSourceOf(player, helper.getLevel()),
                "scriptevent " + id + (message.isEmpty() ? "" : " " + message));
        } catch (RuntimeException failed) {
            helper.getLevel().getServer().getPlayerList().remove(player);
            throw failed;
        }
        helper.runAfterDelay(DELIVERED, () -> {
            helper.getLevel().getServer().getPlayerList().remove(player);
            check.accept(player);
            helper.succeed();
        });
    }

    private static void write(Path file, String json) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    private static void expectTag(GameTestHelper helper, Entity entity, String tag) {
        helper.assertTrue(Platform.tagsOf(entity).contains(tag),
            "The pack's script left " + Platform.tagsOf(entity) + " rather than " + tag + ".");
    }
}
