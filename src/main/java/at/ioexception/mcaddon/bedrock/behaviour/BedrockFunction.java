package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * A Bedrock loot function, run as the loot is rolled rather than rewritten as a Java one.
 *
 * `enchant_book_for_trading` names a list of enchantments with a level range each and hands back a
 * book carrying one of them. Java's own functions enchant either at a stated level or at random out
 * of a whole set, and neither picks one name out of a list and then rolls its own range, so the
 * Bedrock object reaches a function of this mod's own type and is answered here. The type goes in
 * the loot function registry, which the game builds itself rather than reading from a data pack.
 *
 * A data value that is settled per roll is the other kind: a range covers several of the items Java
 * split one Bedrock id into, and a colour index names the one the entity being rolled for is
 * wearing, and neither is a question a Java function asks of the stack it is handed.
 */
public final class BedrockFunction implements LootItemFunction {

    /** The function name a converted table writes as this one, each answered by `apply`. */
    public static final String NAME = McAddon.MOD_ID + ":bedrock";

    /** One entry of an `enchantments` list: which enchantment, and the levels it may land on. */
    private record Choice(String name, int lowest, int highest) {
        int roll(RandomSource random) {
            return lowest >= highest ? lowest : lowest + random.nextInt(highest - lowest + 1);
        }
    }

    private final JsonObject bedrock;

    private BedrockFunction(String stated) {
        JsonObject parsed = null;
        try {
            parsed = Json.parse(stated).getAsJsonObject();
        } catch (RuntimeException error) {
            McAddon.LOGGER.warn("Could not read a Bedrock loot function: {}", error.toString());
        }
        this.bedrock = parsed == null ? new JsonObject() : parsed;
    }

    private static MapCodec<BedrockFunction> mapCodec() {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("bedrock").forGetter(one -> one.bedrock.toString()))
            .apply(instance, BedrockFunction::new));
    }

    //? if >=26 {
    private static final MapCodec<BedrockFunction> TYPE = mapCodec();

    @Override
    public MapCodec<? extends LootItemFunction> codec() {
        return TYPE;
    }
    //?} else if >=1.21 {
    /*private static final net.minecraft.world.level.storage.loot.functions.LootItemFunctionType<BedrockFunction>
        TYPE = new net.minecraft.world.level.storage.loot.functions.LootItemFunctionType<>(mapCodec());

    @Override
    public net.minecraft.world.level.storage.loot.functions.LootItemFunctionType<BedrockFunction> getType() {
        return TYPE;
    }
    *///?} else {
    /*private static final net.minecraft.world.level.storage.loot.functions.LootItemFunctionType
        TYPE = new net.minecraft.world.level.storage.loot.functions.LootItemFunctionType(
            new net.minecraft.world.level.storage.loot.Serializer<BedrockFunction>() {
                @Override
                public void serialize(JsonObject json, BedrockFunction function,
                    com.google.gson.JsonSerializationContext context) {
                    json.addProperty("bedrock", function.bedrock.toString());
                }

                @Override
                public BedrockFunction deserialize(JsonObject json,
                    com.google.gson.JsonDeserializationContext context) {
                    return new BedrockFunction(Json.text(json, "bedrock", "{}"));
                }
            });

    @Override
    public net.minecraft.world.level.storage.loot.functions.LootItemFunctionType getType() {
        return TYPE;
    }
    *///?}

    public static void register() {
        Registry.register(BuiltInRegistries.LOOT_FUNCTION_TYPE, McAddon.id("bedrock"), TYPE);
    }

    @Override
    public ItemStack apply(ItemStack stack, LootContext context) {
        return switch (Json.text(bedrock, "function", "").replaceFirst("^minecraft:", "")) {
            case "enchant_book_for_trading" ->
                book(stack, context.getRandom(), context.getLevel().registryAccess());
            case "set_data", "random_aux_value", "set_data_from_color_index" -> picked(stack, context);
            default -> stack;
        };
    }

    /**
     * The item a data value landed on, for the values that are not settled until the table is rolled.
     *
     * What each value in the range names was worked out as the table was converted, because a Java
     * id no longer says which Bedrock family the item came out of. A range picks one of them at
     * random, and a colour index picks the one the entity's own colour points at. Only the count
     * carries over from whatever ran before, which is what a Bedrock table sets beside a data value.
     */
    private ItemStack picked(ItemStack stack, LootContext context) {
        List<JsonElement> choices = Json.list(bedrock, "items");
        if (choices.isEmpty()) return stack;

        String name = Json.text(bedrock, "function", "");
        int index = name.endsWith("set_data_from_color_index")
            ? colour(Platform.lootSubject(context)) : context.getRandom().nextInt(choices.size());

        JsonElement named = index >= 0 && index < choices.size() ? choices.get(index) : null;
        Identifier id = named != null && named.isJsonPrimitive()
            ? Identifier.tryParse(named.getAsString()) : null;
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null) {
            AddonEntities.unsupported("loot function " + name + " value " + index);
            return stack;
        }
        return new ItemStack(item, stack.getCount());
    }

    /**
     * The colour index the entity carries, which is what Bedrock's `minecraft:color` puts on it.
     *
     * An entity that was never given one reads as zero, the first of the family, which is the answer
     * Mojang's own tables are written for: a sheared mooshroom rolls one of these for a mushroom
     * that has a single form.
     */
    private static int colour(net.minecraft.world.entity.Entity subject) {
        return subject instanceof at.ioexception.mcaddon.entity.AddonEntity addon
            && addon.value("color") instanceof Number dye ? dye.intValue() : 0;
    }

    /**
     * A book carrying one of the enchantments the function listed, at a level out of its own range.
     *
     * The costs the same function states beside them price a villager's offer, which a loot roll has
     * no side to put a price on, so those four fields are named rather than acted on.
     */
    private ItemStack book(ItemStack stack, RandomSource random,
        net.minecraft.core.RegistryAccess registries) {
        List<Choice> choices = choices();
        if (choices.isEmpty()) return stack;

        for (String priced : List.of("base_cost", "base_random_cost", "per_level_cost",
            "per_level_random_cost")) {
            if (bedrock.has(priced)) {
                AddonEntities.unsupported("loot function enchant_book_for_trading " + priced);
            }
        }

        Choice chosen = choices.get(random.nextInt(choices.size()));
        return Platform.enchantedBook(stack, registries, chosen.name(), chosen.roll(random));
    }

    private List<Choice> choices() {
        List<Choice> choices = new ArrayList<>();
        for (JsonElement element : Json.list(bedrock, "enchantments")) {
            if (!element.isJsonObject()) continue;

            JsonObject entry = element.getAsJsonObject();
            String name = Json.text(entry, "name", null);
            if (name == null) continue;

            int lowest = Math.max(1, (int) Json.number(entry, "min", 1));
            choices.add(new Choice(name, lowest,
                Math.max(lowest, (int) Json.number(entry, "max", lowest))));
        }
        return choices;
    }
}
