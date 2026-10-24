package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.bedrock.behaviour.Filter;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Bedrock's vanilla item tags, asked through a filter. */
public final class ItemTagGameTests {
    private static boolean holds(ServerPlayer player, String tag) {
        Filter filter = Filter.parse(JsonParser.parseString(
            "{\"test\":\"has_equipment_tag\",\"subject\":\"self\",\"domain\":\"hand\",\"value\":\"" + tag + "\"}"));
        return filter.matches(new Filter.Context(player, null, null, null));
    }

    /** `minecraft:is_axe` is Bedrock's spelling, which no Java tag path carries. */
    @GameTest()
    public void aFilterAsksTheHeldItemAboutBedrocksTags(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        helper.assertTrue(holds(player, "minecraft:is_axe"), "An iron axe is not a minecraft:is_axe.");
        helper.assertTrue(holds(player, "is_axe"), "An iron axe is not an is_axe.");
        helper.assertTrue(!holds(player, "minecraft:sign"), "An iron axe is a minecraft:sign.");

        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.OAK_SIGN));
        helper.assertTrue(holds(player, "minecraft:sign"), "An oak sign is not a minecraft:sign.");
        helper.assertTrue(!holds(player, "minecraft:is_axe"), "An oak sign is a minecraft:is_axe.");
        helper.succeed();
    }
}
