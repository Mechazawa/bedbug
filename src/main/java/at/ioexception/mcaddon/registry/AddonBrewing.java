package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.RecipeDefinition;
import at.ioexception.mcaddon.convert.Names;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackRole;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.platform.Platform;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The brewing stand recipes a behaviour pack declares.
 *
 * Up to 26.2 a data pack cannot state one: the game builds its brewing in code, from a list
 * assembled as a server or a client starts, and `register` adds to that list. From 26.3 a brewing
 * stand reads `minecraft:brewing` recipes out of the data packs, and `recipes` is what the
 * converter writes into each add-on's own. Either way a pack's own `minecraft:recipe_brewing_mix`
 * and `minecraft:recipe_brewing_container` reach a brewing stand the same way the game's own do,
 * from the add-ons that are active.
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

    /** One `minecraft:brewing` recipe, with the Bedrock recipe it comes from and its file's suffix. */
    public record Written(RecipeDefinition recipe, String suffix, JsonObject json) {
    }

    private AddonBrewing() {
    }

    public static void register() {
        int installed = brewingOf(ActiveAddons.installed().packs()).size();
        if (installed > 0) McAddon.LOGGER.info("Registered {} brewing recipes from add-ons.", installed);
        //? if >=1.21 && <26.3 {
        // The game builds its brewing once per server and once per client, so the recipes are
        // added as it does, from the add-ons active at that moment: a client builds its own as it
        // joins a server.
        net.fabricmc.fabric.api.registry.FabricPotionBrewingBuilder.BUILD.register(builder ->
            brewingOf(ActiveAddons.active().packs()).forEach(recipe -> add(recipe, builder)));
        //?} elif <1.21 {
        /*// The list is static and built once, so only the installed add-ons ever reach it.
        brewingOf(ActiveAddons.installed().packs()).forEach(AddonBrewing::add);
        *///?}
    }

    /**
     * The `minecraft:brewing` recipes these Bedrock recipes become.
     *
     * A Java brewing recipe names one bottle and one potion on each side. A mix becomes one recipe
     * per bottle it can stand in: the game's three and every bottle a container recipe among these
     * names. A container recipe becomes one recipe per potion, which keeps its potion as the bottle
     * changes. `items` gives the Java id an item name reads as, or null where there is none.
     */
    public static List<Written> recipes(List<RecipeDefinition> recipes, Function<String, String> items) {
        List<RecipeDefinition> brewing = recipes.stream()
            .filter(recipe -> recipe.kind().brews() && recipe.brewing() != null)
            .toList();
        Set<String> bottles = new LinkedHashSet<>();
        VANILLA_CONTAINERS.stream()
            .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
            .sorted()
            .forEach(bottles::add);
        brewing.stream()
            .filter(recipe -> recipe.kind() == RecipeDefinition.Kind.BREWING_CONTAINER)
            .flatMap(recipe -> Stream.of(recipe.brewing().input(), recipe.brewing().output()))
            .filter(Objects::nonNull)
            .map(items)
            .filter(Objects::nonNull)
            .forEach(bottles::add);
        return brewing.stream().flatMap(recipe -> written(recipe, bottles, items)).toList();
    }

    private static Stream<Written> written(RecipeDefinition recipe, Set<String> bottles,
        Function<String, String> items) {
        RecipeDefinition.Brewing parts = recipe.brewing();
        String reagent = javaItem(parts.reagent(), items);
        if (reagent == null) return Stream.empty();

        if (recipe.kind() == RecipeDefinition.Kind.BREWING_CONTAINER) {
            String from = javaItem(parts.input(), items);
            String into = javaItem(parts.output(), items);
            if (from == null || into == null) return Stream.empty();

            return BuiltInRegistries.POTION.keySet().stream()
                .map(Identifier::toString)
                .sorted()
                .map(potion -> new Written(recipe, suffix(potion),
                    brewing(from, potion, reagent, into, potion)));
        }

        Identifier from = knownPotion(parts.input());
        Identifier into = knownPotion(parts.output());
        if (from == null || into == null) return Stream.empty();

        return bottles.stream().map(bottle -> new Written(recipe, suffix(bottle),
            brewing(bottle, from.toString(), reagent, bottle, into.toString())));
    }

    private static JsonObject brewing(String bottle, String potion, String reagent, String result,
        String brewed) {
        JsonObject potions = new JsonObject();
        potions.addProperty("potions", potion);
        JsonObject input = new JsonObject();
        input.addProperty("item", bottle);
        input.add("potion_contents", potions);

        JsonObject reagentItem = new JsonObject();
        reagentItem.addProperty("item", reagent);

        JsonObject contents = new JsonObject();
        contents.addProperty("potion", brewed);
        JsonObject components = new JsonObject();
        components.add("minecraft:potion_contents", contents);
        JsonObject output = new JsonObject();
        output.addProperty("id", result);
        output.add("components", components);

        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:brewing");
        json.add("input", input);
        json.add("reagent", reagentItem);
        json.add("output", output);
        return json;
    }

    private static String suffix(String id) {
        return "_" + id.replace(':', '_');
    }

    private static String javaItem(String named, Function<String, String> items) {
        String found = named == null ? null : items.apply(named);
        if (found == null) AddonEntities.unsupported("brewing ingredient " + named);
        return found;
    }

    private static List<RecipeDefinition> brewingOf(PackSet packs) {
        List<RecipeDefinition> brewing = new ArrayList<>();
        for (BedrockPack pack : packs.packs()) {
            if (pack.role() != PackRole.BEHAVIOUR) continue;
            RecipeDefinition.readAll(pack).stream()
                .filter(recipe -> recipe.kind().brews() && recipe.brewing() != null)
                .forEach(brewing::add);
        }
        return brewing;
    }

    /** The potion an id names, with Bedrock's one-word night vision spelled Java's way. */
    private static Identifier potionId(String named) {
        if (named == null) return null;

        return identifier(named.toLowerCase(Locale.ROOT)
            .replaceFirst("^minecraft:potion_type:", "")
            .replace("nightvision", "night_vision"));
    }

    private static Identifier identifier(String named) {
        if (named == null) return null;

        Names.Identifier split = Names.split(named, "minecraft");
        return split.namespace().isEmpty() || split.path().isEmpty() ? null
            : Platform.id(split.namespace(), split.path());
    }

    /** The id of the potion a name stands for, or null after naming it when Java has none. */
    private static Identifier knownPotion(String named) {
        Identifier id = potionId(named);
        boolean known = id != null && BuiltInRegistries.POTION.containsKey(id);
        if (!known) AddonEntities.unsupported("brewing potion " + named);
        return known ? id : null;
    }

    //? if <26.3 {
    private static Item item(String named) {
        Item found = named == null ? null : AddonItems.named(named);
        if (found == null) AddonEntities.unsupported("brewing ingredient " + named);
        return found;
    }

    private static net.minecraft.world.item.alchemy.Potion potionOf(String named) {
        Identifier id = knownPotion(named);
        return id == null ? null : BuiltInRegistries.POTION.getOptional(id).orElse(null);
    }
    //?}

    //? if >=1.21 && <26.3 {
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

        net.minecraft.world.item.alchemy.Potion from = potionOf(parts.input());
        net.minecraft.world.item.alchemy.Potion into = potionOf(parts.output());
        if (from == null || into == null) return;
        builder.addMix(BuiltInRegistries.POTION.wrapAsHolder(from), reagent,
            BuiltInRegistries.POTION.wrapAsHolder(into));
    }

    // A bottle the game already brews in is on the list; anything else a pack names is added.
    private static void accept(net.minecraft.world.item.alchemy.PotionBrewing.Builder builder,
        Item container) {
        if (!VANILLA_CONTAINERS.contains(container)) builder.addContainer(container);
    }
    //?} elif <1.21 {
    /*// There is no way to say a bottle of a pack's own is a container to brew in, so a container
    // recipe naming anything but the three the game already brews in is named instead.
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

        net.minecraft.world.item.alchemy.Potion from = potionOf(parts.input());
        net.minecraft.world.item.alchemy.Potion into = potionOf(parts.output());
        if (from == null || into == null) return;
        net.fabricmc.fabric.api.registry.FabricBrewingRecipeRegistry
            .registerPotionRecipe(from, ingredient, into);
    }
    *///?}
}
