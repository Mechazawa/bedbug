package at.ioexception.mcaddon.gametest;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.registry.ActiveAddons;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.registry.AddonFogs;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.resource.AddonSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Switching which add-ons answer, which is what a client does as it joins a server and leaves it.
 *
 * The switch happens inside one test body on the server thread, so no other test sees the empty
 * set, and the installed set is active again before the body returns.
 */
public final class ActiveAddonsGameTests {
    private static final String ITEM = "tp:dust";
    private static final String ENTITY = "tp:ember";
    private static final String FOG = "tp:dusk";
    private static final String BLOCK = "tp:lamp";
    private static final String PACK = McAddon.MOD_ID + "/tp";

    /**
     * An empty set leaves every add-on registered and offers none of them; the installed set
     * brought back offers the same objects again.
     */
    @GameTest()
    public void anEmptySetLeavesTheAddOnsRegisteredAndOffersNone(GameTestHelper helper) {
        Item dust = AddonItems.named(ITEM);
        EntityType<?> ember = AddonEntities.typeOf(ENTITY);
        net.minecraft.world.level.block.Block lamp = AddonBlocks.named(BLOCK);
        if (dust == null || ember == null || lamp == null) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        AddonSet installed = ActiveAddons.installed();
        Identifier dustId = BuiltInRegistries.ITEM.getKey(dust);
        Identifier lampId = BuiltInRegistries.BLOCK.getKey(lamp);
        Identifier stoneId = BuiltInRegistries.ITEM.getKey(Items.STONE);
        helper.assertFalse(ActiveAddons.isDormant(dustId) || ActiveAddons.isDormant(lampId),
            ITEM + " or " + BLOCK + " is dormant to begin with.");
        helper.assertTrue(listed(level, dust), ITEM + " is not in the add-ons' creative tab to begin with.");
        helper.assertTrue(offered(), PACK + " is not offered to a pack repository to begin with.");
        //? if >=1.21 {
        helper.assertTrue(brews(level, dust), ITEM + " brews nothing to begin with.");
        //?}

        boolean listedWhileEmpty;
        boolean offeredWhileEmpty;
        boolean brewsWhileEmpty = false;
        boolean fogWhileEmpty;
        Object registeredWhileEmpty;
        Object definedWhileEmpty;
        boolean dormantWhileEmpty;
        boolean stoneDormantWhileEmpty;
        ActiveAddons.activate(AddonSet.EMPTY);
        try {
            listedWhileEmpty = listed(level, dust);
            offeredWhileEmpty = offered();
            // From 26.3 brewing is a recipe in the add-on's own data pack, so the pack no longer
            // being offered is what takes it away, and the server's recipes change only on a reload.
            //? if >=1.21 && <26.3 {
            brewsWhileEmpty = brews(level, dust);
            //?}
            fogWhileEmpty = AddonFogs.definitionOf(FOG) != null;
            registeredWhileEmpty = BuiltInRegistries.ITEM.getOptional(BuiltInRegistries.ITEM.getKey(dust)).orElse(null);
            definedWhileEmpty = AddonEntities.definitionOf(ember);
            dormantWhileEmpty = ActiveAddons.isDormant(dustId) && ActiveAddons.isDormant(lampId);
            stoneDormantWhileEmpty = ActiveAddons.isDormant(stoneId);
        } finally {
            ActiveAddons.activate(installed);
        }

        helper.assertTrue(registeredWhileEmpty == dust, ITEM + " left the item registry with its add-on.");
        helper.assertTrue(definedWhileEmpty != null, ENTITY + " lost its definition while dormant.");
        helper.assertFalse(listedWhileEmpty, ITEM + " stayed in the creative tab with its add-on gone.");
        helper.assertFalse(offeredWhileEmpty, PACK + " was still offered with its add-on gone.");
        helper.assertFalse(brewsWhileEmpty, ITEM + " still brewed with its add-on gone.");
        helper.assertFalse(fogWhileEmpty, FOG + " was still described with its add-on gone.");
        // A client's model bakery before 26 leaves out the models of what is dormant.
        helper.assertTrue(dormantWhileEmpty, ITEM + " and " + BLOCK + " were not dormant with their add-on gone.");
        helper.assertFalse(stoneDormantWhileEmpty, "An item of the game's own was dormant.");
        helper.assertFalse(ActiveAddons.isDormant(dustId) || ActiveAddons.isDormant(lampId),
            ITEM + " or " + BLOCK + " stayed dormant with its add-on back.");

        helper.assertTrue(AddonItems.named(ITEM) == dust && AddonEntities.typeOf(ENTITY) == ember,
            "The installed set came back with other objects than it left with.");
        helper.assertTrue(listed(level, dust) && offered(), "The installed set did not come back whole.");
        //? if >=1.21 {
        helper.assertTrue(brews(level, dust), ITEM + " did not brew again with its add-on back.");
        //?}
        helper.succeed();
    }

    /** An add-on left out of the active set reads as inactive and installs none of its structures. */
    @GameTest()
    public void anInactiveAddOnInstallsNoStructure(GameTestHelper helper) {
        if (!ActiveAddons.isActive("tp")) {
            helper.succeed();
            return;
        }
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates =
            at.ioexception.mcaddon.platform.Platform.structureTemplates(server);
        Identifier hut = at.ioexception.mcaddon.platform.Platform.id("tp", "hut");
        AddonSet installed = ActiveAddons.installed();
        java.util.function.Predicate<at.ioexception.mcaddon.convert.ConvertedPack> notTp =
            one -> !one.namespace().equals("tp");

        boolean activeWhileOff;
        boolean sameSetWhileOff;
        boolean hutWhileOff;
        templates.remove(hut);
        ActiveAddons.activate(installed.only(notTp));
        try {
            activeWhileOff = ActiveAddons.isActive("tp");
            sameSetWhileOff = ActiveAddons.isActive(installed.only(notTp));
            at.ioexception.mcaddon.structure.PackStructures.install(server);
            hutWhileOff = templates.get(hut).isPresent();
        } finally {
            ActiveAddons.activate(installed);
            at.ioexception.mcaddon.structure.PackStructures.install(server);
        }

        helper.assertFalse(activeWhileOff, "tp read as active while left out of the active set.");
        helper.assertTrue(sameSetWhileOff, "Another object holding the same add-ons did not read as active.");
        helper.assertFalse(hutWhileOff, "tp:hut was installed while tp was not active.");
        helper.assertTrue(templates.get(hut).isPresent(), "tp:hut did not come back with tp active.");
        helper.succeed();
    }

    private static boolean listed(ServerLevel level, Item item) {
        CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(McAddon.id("addons")).orElseThrow();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true,
            level.registryAccess()));
        return tab.getDisplayItems().stream().anyMatch(stack -> stack.is(item));
    }

    private static boolean offered() {
        PackRepository repository = new PackRepository();
        repository.reload();
        return repository.getAvailableIds().contains(PACK);
    }

    //? if >=1.21 {
    /**
     * Whether a brewing stand takes the item: one built now, or from 26.3 the server's own recipes.
     * 1.20.1 builds its brewing once, for good.
     */
    private static boolean brews(ServerLevel level, Item item) {
        //? if >=26.3 {
        /*return level.recipeAccess()
            .propertySet(net.minecraft.world.item.crafting.RecipePropertySet.BREWING_REAGENTS)
            .test(new net.minecraft.world.item.ItemStack(item));
        *///?} else {
        return net.minecraft.world.item.alchemy.PotionBrewing.bootstrap(level.enabledFeatures())
            .isIngredient(new net.minecraft.world.item.ItemStack(item));
        //?}
    }
    //?}
}
