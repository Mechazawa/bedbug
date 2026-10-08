package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Commands an add-on runs while the game is going, in the dialect the add-on writes them in.
 *
 * Each line here is one Java's parser refuses as it stands, so a mark left on the world says the
 * line was translated on its way to the Bedrock command tree, and the reports say what could not
 * be. The synthetic pack's script, its ember's event and its functions are what run them, so with no
 * pack installed each test that needs one succeeds without asserting.
 */
public final class CommandGameTests {
    /** Inside the 8x8x8 the empty structure covers, clear of its edges. */
    private static final BlockPos STAND = new BlockPos(1, 2, 1);

    /** The entity the synthetic pack declares, whose presence says the pack is installed. */
    private static final String ENTITY = "tp:ember";

    /** What `/mcaddon scripts` heads the commands it could not translate with. */
    private static final String UNTRANSLATED = "What the packs' commands asked for that this mod cannot translate:";

    /**
     * A function file calling another by its Bedrock name reaches it.
     *
     * Bedrock names a function by its path alone and Java reads a bare name as one under
     * `minecraft`, so the call only runs the pack's own function when the conversion names the
     * add-on that ships it.
     */
    @GameTest()
    public void aFunctionCallsAnotherByItsBedrockName(GameTestHelper helper) {
        if (AddonEntities.typeOf(ENTITY) == null) {
            helper.succeed();
            return;
        }

        Entity stand = place(helper, vanilla("armor_stand"), STAND);
        run(helper, "execute as " + stand.getStringUUID() + " run function tp:tp/relay");
        helper.assertTrue(Platform.tagsOf(stand).contains("tp_relayed"),
            "tp/relay ran and the function it calls by name did not.");
        helper.succeed();
    }

    /**
     * The commands a script runs are translated the way a function file is.
     *
     * The script runs, as an armour stand holding a sword, a selector asking about its inventory,
     * one naming Bedrock's villager, a function by its path alone and a command Java has nothing
     * like. The first three leave tags, and the last is reported as one this mod cannot translate
     * rather than as one Java failed to parse.
     */
    @GameTest()
    public void aScriptsCommandsAreTranslated(GameTestHelper helper) {
        if (AddonEntities.typeOf(ENTITY) == null) {
            helper.succeed();
            return;
        }
        helper.assertTrue(ServerHooks.scripts() != null, "the synthetic pack's script is not running.");

        LivingEntity stand = (LivingEntity) place(helper, vanilla("armor_stand"), STAND);
        stand.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        Entity villager = place(helper, vanilla("villager"), STAND.offset(2, 0, 0));
        run(helper, "bedrock execute as " + stand.getStringUUID() + " run scriptevent tp:commands");

        helper.succeedWhen(() -> {
            List<String> tags = List.copyOf(Platform.tagsOf(stand));
            helper.assertTrue(tags.contains("tp_holds_a_sword"),
                "hasitem did not find the sword in the main hand: " + tags);
            helper.assertTrue(tags.contains("tp_has_no_diamond"),
                "hasitem found a diamond nobody carries: " + tags);
            helper.assertTrue(tags.contains("tp_relayed"), "a script's bare function call ran nothing: " + tags);
            helper.assertTrue(Platform.tagsOf(villager).contains("tp_selected_as_villager_v2"),
                "a selector naming minecraft:villager_v2 did not select the villager.");

            List<String> report = ServerHooks.scripts().coverage().report();
            helper.assertTrue(reportedUnder(report, UNTRANSLATED, "    command hud x"),
                "the script's hud command is not reported as untranslatable: " + report);
        });
    }

    /**
     * An entity event's commands are translated, and the one with no Java form is reported.
     *
     * The ember's event tags it through a selector with a Bedrock radius and asks for a heads-up
     * display change Java has no command for, which `/mcaddon entities` has to name.
     */
    @GameTest()
    public void anEntityEventsCommandsAreTranslated(GameTestHelper helper) {
        EntityType<?> type = AddonEntities.typeOf(ENTITY);
        if (type == null) {
            helper.succeed();
            return;
        }

        Entity ember = place(helper, type, STAND);
        EntityEvents.trigger(ember, "tp:ember_command", null, ServerHooks.state());

        helper.succeedWhen(() -> {
            helper.assertTrue(Platform.tagsOf(ember).contains("tp_event_command_translated"),
                ENTITY + "'s event ran a selector with a Bedrock radius and it did not reach the ember.");
            helper.assertTrue(AddonEntities.report().stream().anyMatch(line -> line.startsWith("    command hud x")),
                "the event's hud command is not reported: " + AddonEntities.report());
        });
    }

    /**
     * Java's own command tree reads exactly what Java reads, and nothing of Bedrock's.
     *
     * A Java data pack's line means what it means without this mod: `hasitem` is no option of Java's
     * selector, `/particle` and `/transfer` take Java's arguments only, and a command Bedrock has and
     * Java has not is reached through `/bedrock` alone.
     */
    @GameTest()
    public void javasOwnTreeReadsNothingOfBedrocks(GameTestHelper helper) {
        for (String bedrock : List.of("kill @e[hasitem={item=apple}]", "particle \"minecraft:flame\" ~ ~ ~",
            "transfer @a \"example.test\"", "camera @a clear", "camerashake stop @a", "clearspawnpoint",
            "dialogue change @e \"tp:greeting\"", "event entity @e tp:settle", "fog @a pop dusk",
            "gametest runall", "mobevent events_enabled", "music stop", "playanimation @e animation.walk",
            "scriptevent \"tp:demo\"", "structure delete \"tp:hut\"", "toggledownfall")) {
            helper.assertFalse(readsAtTheRoot(helper, bedrock), "Java's own tree reads /" + bedrock + ".");
        }
        for (String java : List.of("kill @e[type=minecraft:pig,tag=near,limit=1]", "particle minecraft:flame ~ ~ ~",
            "execute as @e[type=minecraft:pig] run say hi", "bedrock camera @a clear")) {
            helper.assertTrue(readsAtTheRoot(helper, java), "Java's own tree does not read /" + java + ".");
        }
        helper.succeed();
    }

    /**
     * A function holding a line this mod cannot translate loads, its other lines run, and the line is
     * named among what the packs asked for.
     */
    @GameTest()
    public void aFunctionLosesOnlyTheLineThatCannotRun(GameTestHelper helper) {
        if (AddonEntities.typeOf(ENTITY) == null) {
            helper.succeed();
            return;
        }

        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(server.getFunctions().get(Platform.id("tp", "tp/gap")).isPresent(),
            "tp:tp/gap, which holds a line Java has no form of, did not load.");
        Entity stand = place(helper, vanilla("armor_stand"), STAND);
        run(helper, "execute as " + stand.getStringUUID() + " run function tp:tp/gap");

        List<String> tags = List.copyOf(Platform.tagsOf(stand));
        helper.assertTrue(tags.contains("tp_before_the_gap") && tags.contains("tp_after_the_gap"),
            "the lines either side of the one that cannot run did not both run: " + tags);
        helper.assertTrue(AddonEntities.report().stream()
                .anyMatch(line -> line.startsWith("    command inputpermission x")),
            "the line that cannot run is not reported: " + AddonEntities.report());
        helper.succeed();
    }

    /**
     * `/bedrock` reads a selector asking about an inventory anywhere in an `execute` chain, runs a
     * Bedrock command behind its `run`, and leaves no mark of the question on anybody once it is done.
     */
    @GameTest()
    public void bedrockAsksAnExecuteChainAboutWhatEachEntityCarries(GameTestHelper helper) {
        EntityType<?> ember = AddonEntities.typeOf(ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        LivingEntity armed = (LivingEntity) place(helper, ember, STAND);
        armed.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        Entity bare = place(helper, ember, STAND.offset(1, 0, 0));
        BlockPos at = helper.absolutePos(STAND);
        run(helper, "bedrock execute positioned " + at.getX() + " " + at.getY() + " " + at.getZ()
            + " as @e[type=tp:ember,r=3,hasitem={item=diamond_sword,location=slot.weapon.mainhand}] at @s"
            + " run event entity @s tp:ember_command");

        for (Entity entity : List.of(armed, bare)) {
            helper.assertTrue(Platform.tagsOf(entity).stream().noneMatch(tag -> tag.startsWith("mcaddon.hasitem.")),
                "a tag hasitem was answered through is still on " + entity + ": " + Platform.tagsOf(entity));
        }
        helper.succeedWhen(() -> {
            helper.assertTrue(Platform.tagsOf(armed).contains("tp_event_command_translated"),
                "the ember holding a sword was not selected, or its event never ran: " + Platform.tagsOf(armed));
            helper.assertFalse(Platform.tagsOf(bare).contains("tp_event_command_translated"),
                "an ember holding nothing was selected as holding a sword.");
        });
    }

    /** A function named by its Bedrock path through `/bedrock`, from Java's own `execute`, runs. */
    @GameTest()
    public void bedrockCallsAFunctionByItsBedrockName(GameTestHelper helper) {
        if (AddonEntities.typeOf(ENTITY) == null) {
            helper.succeed();
            return;
        }

        Entity stand = place(helper, vanilla("armor_stand"), STAND);
        run(helper, "execute as " + stand.getStringUUID() + " run bedrock function tp/relayed");
        helper.assertTrue(Platform.tagsOf(stand).contains("tp_relayed"),
            "/bedrock function tp/relayed did not run the pack's function.");
        helper.succeed();
    }

    /** `/bedrock` asks for the level Java runs a function at, which a player who is no operator lacks. */
    @GameTest()
    public void bedrockIsForOperators(GameTestHelper helper) {
        net.minecraft.server.level.ServerPlayer player = helper.makeMockServerPlayerInLevel();
        try {
            helper.assertFalse(readsAtTheRoot(helper, player.createCommandSourceStack(), "bedrock say hello"),
                "a player who is no operator may run /bedrock.");
            helper.assertTrue(readsAtTheRoot(helper, helper.getLevel().getServer().createCommandSourceStack(),
                "bedrock say hello"), "the server console may not run /bedrock.");
        } finally {
            helper.getLevel().getServer().getPlayerList().remove(player);
        }
        helper.succeed();
    }

    private static boolean readsAtTheRoot(GameTestHelper helper, String line) {
        return readsAtTheRoot(helper, helper.getLevel().getServer().createCommandSourceStack(), line);
    }

    private static boolean readsAtTheRoot(GameTestHelper helper, net.minecraft.commands.CommandSourceStack source,
        String line) {
        com.mojang.brigadier.ParseResults<net.minecraft.commands.CommandSourceStack> parsed =
            helper.getLevel().getServer().getCommands().getDispatcher().parse(line, source);
        return parsed.getExceptions().isEmpty() && !parsed.getReader().canRead();
    }

    private static boolean reportedUnder(List<String> report, String heading, String prefix) {
        int at = report.indexOf(heading);
        if (at < 0) return false;
        for (String line : report.subList(at + 1, report.size())) {
            if (!line.startsWith("    ")) return false;
            if (line.startsWith(prefix)) return true;
        }
        return false;
    }

    private static EntityType<?> vanilla(String path) {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(Platform.id("minecraft", path)).orElse(null);
    }

    private static Entity place(GameTestHelper helper, EntityType<?> type, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        Entity entity = Platform.createMob(type, level);
        helper.assertTrue(entity != null, type + " could not be created.");

        BlockPos where = helper.absolutePos(relative);
        Platform.placeAt(entity, where.getX() + 0.5, where.getY(), where.getZ() + 0.5, 0f, 0f);
        level.addFreshEntity(entity);
        return entity;
    }

    /** The way the console runs a line, which is the one way a `function` runs on every version. */
    private static void run(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }
}
