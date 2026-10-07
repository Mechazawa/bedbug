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
 * line was translated on its way to the dispatcher, and the reports say what could not be. The
 * synthetic pack's script, its ember's event and its functions are what run them, so with no pack
 * installed each test succeeds without asserting.
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
        run(helper, "execute as " + stand.getStringUUID() + " run scriptevent \"tp:commands\"");

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
