package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.bedrock.input.InputPermission;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.server.EntityEvents;
import at.ioexception.mcaddon.server.PlayerInputs;
import at.ioexception.mcaddon.server.ServerHooks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
            helper.assertTrue(reportedUnder(report, UNTRANSLATED, "    command sendshowstoreoffer x"),
                "the script's sendshowstoreoffer command is not reported as untranslatable: " + report);
        });
    }

    /**
     * An entity event's commands are translated, and the one with no Java form is reported.
     *
     * The ember's event tags it through a selector with a Bedrock radius and asks for a marketplace
     * offer, which needs Mojang's store and which `/mcaddon entities` has to name.
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
            helper.assertTrue(AddonEntities.report().stream()
                    .anyMatch(line -> line.startsWith("    command sendshowstoreoffer x")),
                "the event's sendshowstoreoffer command is not reported: " + AddonEntities.report());
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
                .anyMatch(line -> line.startsWith("    command project x")),
            "the line that cannot run is not reported: " + AddonEntities.report());
        helper.succeed();
    }

    /**
     * A function line this mod cannot translate is named as the pack loads, in a function nothing
     * runs: `tp:tp/unrun` asks for the paper doll to be hidden, which no Java client draws.
     */
    @GameTest()
    public void aFunctionLineIsReportedBeforeAnythingRunsIt(GameTestHelper helper) {
        if (AddonEntities.typeOf(ENTITY) == null) {
            helper.succeed();
            return;
        }

        helper.assertTrue(helper.getLevel().getServer().getFunctions().get(Platform.id("tp", "tp/unrun")).isPresent(),
            "tp:tp/unrun did not load.");
        helper.assertTrue(AddonEntities.report().stream()
                .anyMatch(line -> line.startsWith("    command hud paperdoll, which Java has no form of x")),
            "the line of tp:tp/unrun that cannot run is not reported: " + AddonEntities.report());
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

    /**
     * A function calling itself through `/bedrock` is ended by the limit on the commands one run may
     * queue, the way one calling itself directly is, rather than by the stack running out.
     */
    @GameTest()
    public void aFunctionCallingItselfStopsAtTheCommandLimit(GameTestHelper helper) {
        if (AddonEntities.typeOf(ENTITY) == null) {
            helper.succeed();
            return;
        }

        Entity stand = place(helper, vanilla("armor_stand"), STAND);
        run(helper, "execute as " + stand.getStringUUID() + " run function tp:tp/loop");
        helper.assertTrue(Platform.tagsOf(stand).contains("tp_looped"), "tp:tp/loop never ran its first line.");
        helper.succeed();
    }

    /**
     * `/bedrock` selects by an entity property, asking the values this mod keeps for each entity:
     * one ember is lit and the other holds the default its pack declared.
     */
    @GameTest()
    public void bedrockSelectsByAnEntityProperty(GameTestHelper helper) {
        EntityType<?> ember = AddonEntities.typeOf(ENTITY);
        if (ember == null) {
            helper.succeed();
            return;
        }

        Entity lit = place(helper, ember, STAND);
        Entity cold = place(helper, ember, STAND.offset(1, 0, 0));
        ServerHooks.state().setProperty(lit.getUUID(), "tp:kindled", true);
        BlockPos at = helper.absolutePos(STAND);
        String near = "bedrock execute positioned " + at.getX() + " " + at.getY() + " " + at.getZ()
            + " as @e[type=tp:ember,r=3,";
        run(helper, near + "has_property={tp:kindled=true}] run tag @s add tp_selected_lit");
        run(helper, near + "has_property={property=tp:kindled,tp:kindled=!true}] run tag @s add tp_selected_cold");
        run(helper, near + "has_property={property=tp:never_declared}] run tag @s add tp_selected_by_nothing");

        helper.assertTrue(Platform.tagsOf(lit).contains("tp_selected_lit")
                && !Platform.tagsOf(lit).contains("tp_selected_cold"),
            "the lit ember was not selected by tp:kindled=true alone: " + Platform.tagsOf(lit));
        helper.assertTrue(Platform.tagsOf(cold).contains("tp_selected_cold")
                && !Platform.tagsOf(cold).contains("tp_selected_lit"),
            "the ember at its declared default was not selected by tp:kindled=!true alone: "
                + Platform.tagsOf(cold));
        for (Entity entity : List.of(lit, cold)) {
            helper.assertFalse(Platform.tagsOf(entity).contains("tp_selected_by_nothing"),
                "an ember was selected as having a property its pack never declared.");
            helper.assertTrue(Platform.tagsOf(entity).stream()
                    .noneMatch(tag -> tag.startsWith("mcaddon.has_property.")),
                "a tag has_property was answered through is still on " + entity + ": " + Platform.tagsOf(entity));
        }
        helper.succeed();
    }

    /**
     * `/bedrock inputpermission` takes a kind of input away from one player, `/bedrock` selects by it
     * through `haspermission`, and a player whose mount permission is off is refused a vehicle.
     */
    @GameTest()
    public void bedrockSelectsByAPlayersInputPermission(GameTestHelper helper) {
        ServerPlayer locked = helper.makeMockServerPlayerInLevel();
        ServerPlayer free = helper.makeMockServerPlayerInLevel();
        try {
            // Both mock players carry one name, and a player argument takes no uuid, so a tag tells them apart.
            locked.addTag("tp_to_lock");
            run(helper, "bedrock inputpermission set @a[tag=tp_to_lock] camera disabled");
            run(helper, "bedrock inputpermission set @a[tag=tp_to_lock] mount disabled");
            helper.assertFalse(PlayerInputs.enabled(locked, InputPermission.CAMERA),
                "/bedrock inputpermission set did not take the camera input away.");
            run(helper, "bedrock execute as @a[haspermission={camera=disabled,jump=enabled}]"
                + " run tag @s add tp_camera_locked");

            helper.assertTrue(Platform.tagsOf(locked).contains("tp_camera_locked"),
                "the player whose camera input was taken away was not selected: " + Platform.tagsOf(locked));
            helper.assertFalse(Platform.tagsOf(free).contains("tp_camera_locked"),
                "a player with every input was selected as one whose camera was taken away.");
            helper.assertTrue(Platform.tagsOf(locked).stream()
                    .noneMatch(tag -> tag.startsWith("mcaddon.haspermission.")),
                "a tag haspermission was answered through is still on the player: " + Platform.tagsOf(locked));

            // The synthetic pack's script tags a player with each category that changes, by number.
            helper.assertTrue(AddonEntities.typeOf(ENTITY) == null
                    || Platform.tagsOf(locked).contains("tp:input_1_false"),
                "the script heard nothing of the camera input taken away: " + Platform.tagsOf(locked));

            Entity minecart = place(helper, vanilla("minecart"), STAND);
            helper.assertFalse(locked.startRiding(minecart), "a player whose mount input is off got into a minecart.");
            helper.assertTrue(free.startRiding(minecart), "a player with every input could not get into a minecart.");
        } finally {
            PlayerInputs.forget(locked);
            helper.getLevel().getServer().getPlayerList().remove(locked);
            helper.getLevel().getServer().getPlayerList().remove(free);
        }
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
