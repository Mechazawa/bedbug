package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonItems;
import at.ioexception.mcaddon.script.ScriptCoverage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ComposterBlock;
import org.graalvm.polyglot.HostAccess;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the game knows about a kind of item.
 *
 * A script's ItemStack is a value object and not a slot in the world, so every answer here is about
 * an item type. What a pack writes onto one stack (its lore, its dynamic properties, what it may be
 * placed on) stays on the JavaScript side, which asks this class once per type and keeps the answer.
 */
public final class Items extends Surface {
    /**
     * Bedrock names an enchantment slot after the kind of tool, and vanilla item ids already carry
     * that kind. Longer suffixes come first so `crossbow` is not read as a bow.
     */
    private static final Map<String, String> SLOT_BY_SUFFIX = new LinkedHashMap<>();

    static {
        SLOT_BY_SUFFIX.put("crossbow", "Crossbow");
        SLOT_BY_SUFFIX.put("bow", "Bow");
        SLOT_BY_SUFFIX.put("_sword", "Sword");
        SLOT_BY_SUFFIX.put("_pickaxe", "Pickaxe");
        SLOT_BY_SUFFIX.put("_axe", "Axe");
        SLOT_BY_SUFFIX.put("_shovel", "Shovel");
        SLOT_BY_SUFFIX.put("_hoe", "Hoe");
        SLOT_BY_SUFFIX.put("helmet", "ArmorHead");
        SLOT_BY_SUFFIX.put("_chestplate", "ArmorTorso");
        SLOT_BY_SUFFIX.put("_leggings", "ArmorLegs");
        SLOT_BY_SUFFIX.put("_boots", "ArmorFeet");
        SLOT_BY_SUFFIX.put("elytra", "Elytra");
        SLOT_BY_SUFFIX.put("trident", "Spear");
        SLOT_BY_SUFFIX.put("shield", "Shield");
        SLOT_BY_SUFFIX.put("shears", "Shears");
        SLOT_BY_SUFFIX.put("fishing_rod", "FishingRod");
        SLOT_BY_SUFFIX.put("flint_and_steel", "Flintsteel");
        SLOT_BY_SUFFIX.put("on_a_stick", "CarrotStick");
    }

    public Items(MinecraftServer server, ScriptCoverage coverage) {
        super(server, coverage);
    }

    @HostAccess.Export
    public boolean exists(String itemId) {
        return item(itemId) != null;
    }

    @HostAccess.Export
    public String[] all() {
        return BuiltInRegistries.ITEM.stream().map(AddonItems::identifierOf).distinct().toArray(String[]::new);
    }

    @HostAccess.Export
    public boolean blockExists(String blockId) {
        return blockId != null && AddonBlocks.named(blockId) != null;
    }

    /** Everything a script's ItemStack needs about its type, as one JSON object. */
    @HostAccess.Export
    public String describe(String itemId) {
        JsonObject described = new JsonObject();
        Item item = item(itemId);
        if (item == null) return described.toString();

        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        ItemStack stack = new ItemStack(item);
        described.addProperty("id", idOf(item));
        described.addProperty("localizationKey", item.getDescriptionId());
        described.addProperty("maxAmount", stack.getMaxStackSize());
        describeFood(described, stack, item);
        describeCooldown(described, stack, id);
        describeEnchantable(described, stack, item, id);
        describeDyeable(described, stack, item);
        describePotion(described, id);
        describeCompostable(described, item);
        return described.toString();
    }

    /** Zero for an enchantment the game does not have, which is how a bad id is told apart. */
    @HostAccess.Export
    public int enchantmentMaxLevel(String enchantmentId) {
        Identifier parsed = enchantmentId == null ? null : Identifier.tryParse(enchantmentId);
        if (parsed == null) return 0;

        //? if >=26 {
        return server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
            .getOptional(parsed)
            .map(net.minecraft.world.item.enchantment.Enchantment::getMaxLevel)
            .orElse(0);
        //?} else if >=1.21 {
        /*return server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
            .getOptional(parsed)
            .map(net.minecraft.world.item.enchantment.Enchantment::getMaxLevel)
            .orElse(0);
        *///?} else {
        /*return net.minecraft.core.registries.BuiltInRegistries.ENCHANTMENT
            .getOptional(parsed)
            .map(net.minecraft.world.item.enchantment.Enchantment::getMaxLevel)
            .orElse(0);
        *///?}
    }

    /** Ticks left of a player's cooldown on this kind of item, rounded from the fraction Java keeps. */
    @HostAccess.Export
    public int cooldownRemaining(String playerId, String itemId) {
        Item item = item(itemId);
        if (item == null || !(entity(playerId) instanceof ServerPlayer player)) return 0;

        //? if >=1.21.2 {
        ItemStack stack = new ItemStack(item);
        net.minecraft.world.item.component.UseCooldown cooldown =
            stack.get(net.minecraft.core.component.DataComponents.USE_COOLDOWN);
        if (cooldown != null) {
            return Math.round(player.getCooldowns().getCooldownPercent(stack, 0.0F) * cooldown.ticks());
        }
        //?}
        return 0;
    }

    @HostAccess.Export
    public boolean startCooldown(String playerId, String itemId, int ticks) {
        Item item = item(itemId);
        if (item == null || !(entity(playerId) instanceof ServerPlayer player)) return false;

        //? if >=1.21.2 {
        player.getCooldowns().addCooldown(new ItemStack(item), ticks);
        //?} else {
        /*player.getCooldowns().addCooldown(item, ticks);
        *///?}
        return true;
    }

    private void describeFood(JsonObject into, ItemStack stack, Item item) {
        JsonObject food = new JsonObject();
        //? if >=1.21 {
        net.minecraft.world.food.FoodProperties properties =
            stack.get(net.minecraft.core.component.DataComponents.FOOD);
        if (properties == null) return;

        food.addProperty("nutrition", properties.nutrition());
        food.addProperty("saturationModifier", modifier(properties.nutrition(), properties.saturation()));
        food.addProperty("canAlwaysEat", properties.canAlwaysEat());
        //?} else {
        /*net.minecraft.world.food.FoodProperties properties = item.getFoodProperties();
        if (properties == null) return;

        food.addProperty("nutrition", properties.getNutrition());
        food.addProperty("saturationModifier", properties.getSaturationModifier());
        food.addProperty("canAlwaysEat", properties.canAlwaysEat());
        *///?}
        food.addProperty("usingConvertsTo", convertsTo(stack, item));
        into.add("food", food);
    }

    /**
     * Bedrock states a food's saturation as a multiplier of twice its nutrition. Java states the
     * saturation outright from 1.21 on, so the multiplier has to be worked back out of it.
     */
    private static float modifier(int nutrition, float saturation) {
        return nutrition <= 0 ? 0.0F : saturation / (nutrition * 2.0F);
    }

    /** The item eating one of these leaves behind, which Bedrock reports with the food component. */
    private static String convertsTo(ItemStack stack, Item item) {
        //? if >=1.21.2 {
        net.minecraft.world.item.component.UseRemainder remainder =
            stack.get(net.minecraft.core.component.DataComponents.USE_REMAINDER);
        return remainder == null ? "" : idOf(remainder.convertInto().typeHolder().value());
        //?} else if >=1.21 {
        /*net.minecraft.world.food.FoodProperties properties =
            stack.get(net.minecraft.core.component.DataComponents.FOOD);
        return properties == null ? ""
            : properties.usingConvertsTo().map(left -> idOf(left.getItem())).orElse("");
        *///?} else {
        /*Item left = item.getCraftingRemainingItem();
        return left == null ? "" : idOf(left);
        *///?}
    }

    /**
     * Only 1.21.2 and later carry how long an item's cooldown lasts. Before that a cooldown is
     * written into the code of the few items that have one, so there is nothing to report.
     */
    private void describeCooldown(JsonObject into, ItemStack stack, String itemId) {
        //? if >=1.21.2 {
        net.minecraft.world.item.component.UseCooldown cooldown =
            stack.get(net.minecraft.core.component.DataComponents.USE_COOLDOWN);
        if (cooldown == null) return;

        JsonObject described = new JsonObject();
        described.addProperty("cooldownTicks", cooldown.ticks());
        described.addProperty("cooldownCategory",
            cooldown.cooldownGroup().map(Identifier::toString).orElse(itemId));
        into.add("cooldown", described);
        //?}
    }

    private void describeEnchantable(JsonObject into, ItemStack stack, Item item, String itemId) {
        // The component arrived in 1.21.2; before that an item said how enchantable it was itself.
        //? if >=1.21.2 {
        if (stack.get(net.minecraft.core.component.DataComponents.ENCHANTABLE) == null) return;
        //?} else {
        /*if (item.getEnchantmentValue() <= 0) return;
        *///?}
        JsonArray slots = new JsonArray();
        String slot = enchantmentSlot(pathOf(itemId));
        if (slot != null) slots.add(slot);

        JsonObject described = new JsonObject();
        described.add("slots", slots);
        into.add("enchantable", described);
    }

    private static String enchantmentSlot(String path) {
        return SLOT_BY_SUFFIX.entrySet().stream()
            .filter(candidate -> path.endsWith(candidate.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }

    /**
     * The colour a dye starts from. An item says it is dyeable either by carrying a colour of its
     * own or by standing in the tag the game dyes from, and which of the two applies moved between
     * versions.
     */
    private void describeDyeable(JsonObject into, ItemStack stack, Item item) {
        //? if >=1.21 {
        net.minecraft.world.item.component.DyedItemColor dyed =
            stack.get(net.minecraft.core.component.DataComponents.DYED_COLOR);
        boolean tagged = item.builtInRegistryHolder().tags()
            .anyMatch(tag -> tag.location().toString().equals("minecraft:dyeable"));
        if (dyed == null && !tagged) return;

        int colour = dyed == null ? net.minecraft.world.item.component.DyedItemColor.LEATHER_COLOR : dyed.rgb();
        //?} else {
        /*if (!(item instanceof net.minecraft.world.item.DyeableLeatherItem)) return;

        int colour = net.minecraft.world.item.DyeableLeatherItem.DEFAULT_LEATHER_COLOR;
        *///?}
        JsonObject described = new JsonObject();
        described.add("defaultColor", rgb(colour));
        into.add("dyeable", described);
    }

    private static JsonObject rgb(int packed) {
        JsonObject colour = new JsonObject();
        colour.addProperty("red", (packed >> 16 & 0xFF) / 255.0F);
        colour.addProperty("green", (packed >> 8 & 0xFF) / 255.0F);
        colour.addProperty("blue", (packed & 0xFF) / 255.0F);
        return colour;
    }

    /**
     * How a potion is thrown, which is all a detached stack can say: the effect a potion carries
     * lives on the stack in the world, and a script's ItemStack is built from a type id alone.
     */
    private void describePotion(JsonObject into, String itemId) {
        String delivery = switch (pathOf(itemId)) {
            case "potion" -> "regular";
            case "splash_potion" -> "splash";
            case "lingering_potion" -> "lingering";
            default -> null;
        };
        if (delivery == null) return;

        JsonObject described = new JsonObject();
        described.addProperty("potionDeliveryType", delivery);
        into.add("potion", described);
    }

    /** Java keeps the chance as a fraction; Bedrock reports it as a percentage. */
    private void describeCompostable(JsonObject into, Item item) {
        if (!ComposterBlock.COMPOSTABLES.containsKey(item)) return;

        JsonObject described = new JsonObject();
        described.addProperty("compostingChance",
            Math.round(ComposterBlock.COMPOSTABLES.getFloat(item) * 100.0F));
        into.add("compostable", described);
    }

    private static Item item(String itemId) {
        return itemId == null ? null : AddonItems.named(itemId);
    }

    /** The id a pack knows the item by, which is Bedrock's spelling for one of the game's own. */
    private static String idOf(Item item) {
        return AddonItems.identifierOf(item);
    }

    private static String pathOf(String itemId) {
        return itemId.substring(itemId.indexOf(':') + 1);
    }
}
