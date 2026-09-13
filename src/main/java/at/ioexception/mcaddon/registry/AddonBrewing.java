package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.RecipeDefinition;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The brewing stand recipes a behaviour pack declares.
 *
 * A data pack cannot state one: the game builds its brewing in code, from a list assembled as a
 * server or a client starts. A mod can add to that list, which is what this does, so a pack's own
 * `minecraft:recipe_brewing_mix` and `minecraft:recipe_brewing_container` reach a brewing stand the
 * same way the game's own do.
 *
 * A mix names a potion on each side as `minecraft:potion_type:<name>`, and the name behind that
 * prefix is already the name Java's own potion registry knows, apart from night vision, which
 * Bedrock writes as one word. A potion or an item Java has none of is named rather than registered
 * as something else.
 */
public final class AddonBrewing {

    /** The three bottles Java already brews in, which need no telling that they are containers. */
    private static final Set<Item> VANILLA_CONTAINERS =
        Set.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);

    private AddonBrewing() {
    }

    public static void register(PackSet packs) {
        List<RecipeDefinition> brewing = new ArrayList<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;
            RecipeDefinition.readAll(pack).stream()
                .filter(recipe -> recipe.kind().brews() && recipe.brewing() != null)
                .forEach(brewing::add);
        }
        if (brewing.isEmpty()) return;

        McAddon.LOGGER.info("Registered {} brewing recipes from add-ons.", brewing.size());
        install(brewing);
    }

    /** The potion an id names, with Bedrock's one-word night vision spelled Java's way. */
    private static Identifier potionId(String named) {
        if (named == null) return null;

        return identifier(named.toLowerCase(Locale.ROOT)
            .replaceFirst("^minecraft:potion_type:", "")
            .replace("nightvision", "night_vision"));
    }

    private static Item item(String named) {
        Identifier id = identifier(named);
        Optional<Item> found = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
        if (found.isEmpty()) AddonEntities.unsupported("brewing ingredient " + named);
        return found.orElse(null);
    }

    private static Identifier identifier(String named) {
        if (named == null) return null;

        Names.Identifier split = Names.split(named, "minecraft");
        return split.namespace().isEmpty() || split.path().isEmpty() ? null
            : Platform.id(split.namespace(), split.path());
    }

    private static Potion potionOf(String named) {
        Identifier id = potionId(named);
        Optional<Potion> found = id == null ? Optional.empty()
            : BuiltInRegistries.POTION.getOptional(id);
        if (found.isEmpty()) AddonEntities.unsupported("brewing potion " + named);
        return found.orElse(null);
    }

    //? if >=1.21 {
    /**
     * The game builds its brewing once per server and once per client, so the recipes are added as
     * it does rather than written anywhere.
     */
    private static void install(List<RecipeDefinition> brewing) {
        net.fabricmc.fabric.api.registry.FabricPotionBrewingBuilder.BUILD.register(
            builder -> brewing.forEach(recipe -> add(recipe, builder)));
    }

    private static void add(RecipeDefinition recipe,
        net.minecraft.world.item.alchemy.PotionBrewing.Builder builder) {
        RecipeDefinition.Brewing parts = recipe.brewing();
        Item reagent = item(parts.reagent());
        if (reagent == null) return;

        if (recipe.kind() == RecipeDefinition.Kind.BREWING_CONTAINER) {
            Item from = item(parts.input());
            Item into = item(parts.output());
            if (from == null || into == null) return;

            accept(builder, from);
            accept(builder, into);
            builder.addContainerRecipe(from, reagent, into);
            return;
        }

        Potion from = potionOf(parts.input());
        Potion into = potionOf(parts.output());
        if (from == null || into == null) return;
        builder.addMix(BuiltInRegistries.POTION.wrapAsHolder(from), reagent,
            BuiltInRegistries.POTION.wrapAsHolder(into));
    }

    /** A bottle the game already brews in is on the list; anything else a pack names is added. */
    private static void accept(net.minecraft.world.item.alchemy.PotionBrewing.Builder builder,
        Item container) {
        if (!VANILLA_CONTAINERS.contains(container)) builder.addContainer(container);
    }
    //?} else {
    /*// The list is static and built once, so the recipes go straight into it. There is no way to
    // say a bottle of a pack's own is a container to brew in, so a container recipe naming anything
    // but the three the game already brews in is named instead.
    private static void install(List<RecipeDefinition> brewing) {
        brewing.forEach(AddonBrewing::add);
    }

    private static void add(RecipeDefinition recipe) {
        RecipeDefinition.Brewing parts = recipe.brewing();
        Item reagent = item(parts.reagent());
        if (reagent == null) return;

        net.minecraft.world.item.crafting.Ingredient ingredient =
            net.minecraft.world.item.crafting.Ingredient.of(reagent);
        if (recipe.kind() == RecipeDefinition.Kind.BREWING_CONTAINER) {
            Item from = item(parts.input());
            Item into = item(parts.output());
            if (!(from instanceof net.minecraft.world.item.PotionItem bottle)
                || !(into instanceof net.minecraft.world.item.PotionItem result)) {
                AddonEntities.unsupported("brewing container " + parts.output());
                return;
            }
            net.fabricmc.fabric.api.registry.FabricBrewingRecipeRegistry
                .registerItemRecipe(bottle, ingredient, result);
            return;
        }

        Potion from = potionOf(parts.input());
        Potion into = potionOf(parts.output());
        if (from == null || into == null) return;
        net.fabricmc.fabric.api.registry.FabricBrewingRecipeRegistry
            .registerPotionRecipe(from, ingredient, into);
    }
    *///?}
}
