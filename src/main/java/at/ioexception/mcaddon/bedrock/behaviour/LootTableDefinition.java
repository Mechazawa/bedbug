package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.BedrockEntityNames;
import at.ioexception.mcaddon.convert.BedrockItemNames;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A Bedrock loot table, rewritten as a Java one.
 *
 * The two formats share their shape: pools with rolls and weighted entries. What differs is naming,
 * so the conversion is a rename of a handful of fields rather than a translation. The data value
 * beside a Bedrock item is the exception: Java split the id it sits on into an item per value, so
 * the pair has to be read as the one item it names.
 *
 * @param path the pack-relative path, which is how an entity's `minecraft:loot` names it
 */
public record LootTableDefinition(String path, JsonObject java) {

    /**
     * The Java structure tag each Bedrock explorer map destination points at, named without a `#`.
     *
     * 26.3 names five of the tags after the structure they hold rather than after the map.
     */
    private static final Map<String, String> MAP_DESTINATIONS = Map.ofEntries(
        Map.entry("buriedtreasure", "minecraft:on_treasure_maps"),
        //? if >=26.3 {
        /*Map.entry("mansion", "minecraft:on_woodland_mansion_maps"),
        Map.entry("monument", "minecraft:on_ocean_monument_maps"),
        Map.entry("jungle_temple", "minecraft:on_jungle_pyramid_maps"),
        Map.entry("swamp_hut", "minecraft:on_swamp_hut_maps"),
        Map.entry("trial_chambers", "minecraft:on_buried_trial_chambers_maps"),
        *///?} else {
        Map.entry("mansion", "minecraft:on_woodland_explorer_maps"),
        Map.entry("monument", "minecraft:on_ocean_explorer_maps"),
        Map.entry("jungle_temple", "minecraft:on_jungle_explorer_maps"),
        Map.entry("swamp_hut", "minecraft:on_swamp_explorer_maps"),
        Map.entry("trial_chambers", "minecraft:on_trial_chambers_maps"),
        //?}
        Map.entry("village_desert", "minecraft:on_desert_village_maps"),
        Map.entry("village_plains", "minecraft:on_plains_village_maps"),
        Map.entry("village_savanna", "minecraft:on_savanna_village_maps"),
        Map.entry("village_snowy", "minecraft:on_snowy_village_maps"),
        Map.entry("village_taiga", "minecraft:on_taiga_village_maps"));

    /** The three destinations every supported version ships a structure tag for. */
    private static final List<String> ALWAYS_MAPPED = List.of("buriedtreasure", "mansion", "monument");

    /**
     * The Java structure tag a Bedrock explorer map points at, or null for one it cannot.
     *
     * A destination this reads as null is reported as it is read, so the loot function and the trade
     * function that both ask for a map say the same thing about a destination neither can supply.
     */
    static String mapDestination(String destination) {
        String tag = MAP_DESTINATIONS.get(destination);
        if (tag == null || (!ALWAYS_MAPPED.contains(destination) && !Platform.everyExplorerMapTag())) {
            AddonEntities.unsupported("exploration map destination " + destination);
            return null;
        }
        return tag;
    }

    /**
     * The path inside a data pack that a Bedrock table path is written to.
     *
     * An entity's `minecraft:loot` names the file by the same pack-relative string the table itself
     * came from, so the writer and the entity reading it back have to agree on one rule.
     */
    public static String javaPath(String table) {
        String inside = table.startsWith("loot_tables/")
            ? table.substring("loot_tables/".length()) : table;
        int dot = inside.lastIndexOf('.');
        return at.ioexception.mcaddon.convert.Names.sanitise(dot < 0 ? inside : inside.substring(0, dot));
    }

    public static List<LootTableDefinition> readAll(BedrockPack pack) {
        List<LootTableDefinition> tables = new ArrayList<>();
        for (Path file : pack.list("loot_tables", ".json")) {
            try {
                String relative = pack.relative(file).toString().replace('\\', '/');
                tables.add(new LootTableDefinition(relative, convert(Json.parseObject(file))));
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return tables;
    }

    private static JsonObject convert(JsonObject bedrock) {
        JsonObject java = new JsonObject();
        java.addProperty("type", "minecraft:entity");

        JsonArray pools = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "pools")) {
            if (element.isJsonObject()) pools.add(pool(element.getAsJsonObject()));
        }
        java.add("pools", pools);
        return java;
    }

    private static JsonObject pool(JsonObject bedrock) {
        JsonObject pool = new JsonObject();
        pool.add("rolls", range(bedrock.get("rolls")));
        if (bedrock.has("bonus_rolls")) pool.add("bonus_rolls", range(bedrock.get("bonus_rolls")));

        JsonArray entries = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "entries")) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = entry(element.getAsJsonObject());
            if (entry != null) entries.add(entry);
        }
        pool.add("entries", entries);

        JavaLoot.conditions(pool, conditions(bedrock));

        // A tier picks entries by an item's own rarity, which a Java pool has no field for.
        if (bedrock.has("tiers")) AddonEntities.unsupported("loot pool tiers");
        return pool;
    }

    private static JsonObject entry(JsonObject bedrock) {
        String type = bare(Json.text(bedrock, "type", "item"));
        String name = Json.text(bedrock, "name", null);

        JsonObject entry = new JsonObject();
        switch (type) {
            case "item" -> {
                if (name == null) return null;
                String dropped = dropped(name, bedrock);
                entry.addProperty("type", dropped == null ? "minecraft:empty" : "minecraft:item");
                if (dropped != null) entry.addProperty("name", dropped);
            }
            case "loot_table" -> {
                if (name == null) return null;
                entry.addProperty("type", "minecraft:loot_table");
                entry.addProperty("value", name);
            }
            case "empty" -> entry.addProperty("type", "minecraft:empty");
            default -> {
                AddonEntities.unsupported("loot entry " + type);
                entry.addProperty("type", "minecraft:empty");
            }
        }

        if (bedrock.has("weight")) entry.add("weight", bedrock.get("weight"));
        if (bedrock.has("quality")) entry.add("quality", bedrock.get("quality"));

        JsonArray functions = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "functions")) {
            if (!element.isJsonObject()) continue;
            JsonObject function = function(element.getAsJsonObject(), name);
            if (function != null) functions.add(function);
        }
        JavaLoot.functions(entry, functions);
        JavaLoot.conditions(entry, conditions(bedrock));
        return entry;
    }

    /**
     * Only the functions whose Java equivalent takes the same arguments are carried across.
     *
     * A function naming a data value is about the entry's own item, so which item that is travels
     * with it: the number picks one out of the family Java split that Bedrock id into.
     */
    private static JsonObject function(JsonObject bedrock, String item) {
        String name = bare(Json.text(bedrock, "function", ""));
        JsonObject function;

        switch (name) {
            case "set_count" -> {
                function = JavaLoot.function("minecraft:set_count");
                function.add("count", range(bedrock.get("count")));
                if (bedrock.has("add")) function.add("add", bedrock.get("add"));
            }
            case "set_damage" -> {
                function = JavaLoot.function("minecraft:set_damage");
                function.add("damage", range(bedrock.get("damage")));
                if (bedrock.has("add")) function.add("add", bedrock.get("add"));
            }
            case "enchant_randomly" -> function = JavaLoot.function("minecraft:enchant_randomly");
            case "furnace_smelt" -> function = JavaLoot.function("minecraft:furnace_smelt");
            case "explosion_decay" -> function = JavaLoot.function("minecraft:explosion_decay");
            case "looting_enchant" ->
                function = Platform.lootingCount(range(bedrock.get("count")), bedrock.get("limit"));
            case "enchant_with_levels" -> {
                function = JavaLoot.function("minecraft:enchant_with_levels");
                function.add("levels", range(bedrock.get("levels")));
                Platform.enchantmentChoice(function, Json.bool(bedrock, "treasure", false));
            }
            case "set_potion" -> {
                function = JavaLoot.function("minecraft:set_potion");
                function.addProperty("id", namespaced(Json.text(bedrock, "id", "water")));
            }
            case "specific_enchants" -> {
                function = JavaLoot.function("minecraft:set_enchantments");
                function.add("enchantments", enchantments(bedrock));
            }
            case "exploration_map" -> {
                String tag = mapDestination(Json.text(bedrock, "destination", "buriedtreasure"));
                if (tag == null) return null;
                function = JavaLoot.function("minecraft:exploration_map");
                // From 26.3 the destination is a set of structures, which names a tag with a `#`.
                //? if >=26.3 {
                /*function.addProperty("destination", "#" + tag);
                *///?} else {
                function.addProperty("destination", tag);
                //?}
            }
            case "set_ominous_bottle_amplifier" -> {
                if (!Platform.hasOminousBottle()) {
                    AddonEntities.unsupported("loot function " + name);
                    return null;
                }
                function = JavaLoot.function("minecraft:set_ominous_bottle_amplifier");
                function.add("amplifier", range(bedrock.get("amplifier")));
            }
            case "enchant_random_gear" -> {
                // Java enchants the armour a mob spawns in by the same rule: the stated chance
                // scaled by how hard the place is, then a level out of the range its own spawning
                // draws from.
                JsonObject levels = new JsonObject();
                levels.addProperty("min", 5);
                levels.addProperty("max", 22);

                function = JavaLoot.function("minecraft:enchant_with_levels");
                function.add("levels", range(levels));
                Platform.enchantmentChoice(function, false);

                JsonObject chance = new JsonObject();
                chance.addProperty("condition", "random_regional_difficulty_chance");
                chance.addProperty("max_chance", Json.number(bedrock, "chance", 1));

                JsonArray conditions = new JsonArray();
                conditions.add(BedrockCondition.json(chance));
                JavaLoot.conditions(function, conditions);
            }
            case "set_armor_trim" -> {
                String material = bare(Json.text(bedrock, "material", ""));
                String pattern = bare(Json.text(bedrock, "pattern", ""));
                if (!Platform.shipsTrim(material, pattern)) {
                    AddonEntities.unsupported("loot function set_armor_trim "
                        + material + " " + pattern);
                    return null;
                }
                function = Platform.armorTrim(material, pattern);
            }
            case "set_banner_details" -> {
                // Type 1 is the banner an illager captain carries; type 0 is a plain banner, which
                // wants no pattern at all and so needs no function beside the entry.
                if ((int) Json.number(bedrock, "type", 0) != 1) return null;
                function = JavaLoot.function("minecraft:set_banner_pattern");
                function.add("patterns", ominousBanner());
                function.addProperty("append", false);
            }
            case "set_stew_effect" -> {
                JsonArray effects = stewEffects(bedrock);
                if (effects.isEmpty()) return null;
                function = JavaLoot.function("minecraft:set_stew_effect");
                function.add("effects", effects);
            }
            case "enchant_book_for_trading" -> {
                return BedrockFunction.json(bedrock);
            }
            case "set_data", "random_aux_value", "set_data_from_color_index" -> {
                return dataValue(bedrock, item);
            }
            default -> {
                AddonEntities.unsupported("loot function " + name);
                return null;
            }
        }
        return function;
    }

    /** The values a data value may land on, which a pack writes as a number, a `{min, max}` or a pair. */
    private record Span(int lowest, int highest) {
        static Span tryFrom(JsonElement stated) {
            if (stated == null) return null;
            if (stated.isJsonPrimitive()) {
                int value = (int) stated.getAsDouble();
                return new Span(value, value);
            }
            if (stated.isJsonArray() && stated.getAsJsonArray().size() == 2) {
                return new Span(stated.getAsJsonArray().get(0).getAsInt(),
                    stated.getAsJsonArray().get(1).getAsInt());
            }
            if (!stated.isJsonObject()) return null;

            JsonObject range = stated.getAsJsonObject();
            int lowest = (int) Json.number(range, "min", 0);
            return new Span(lowest, (int) Json.number(range, "max", lowest));
        }

        @Override
        public String toString() {
            return lowest == highest ? String.valueOf(lowest) : lowest + "-" + highest;
        }
    }

    /** The three functions that name a data value, and where each of them states what it says. */
    private static final List<String> DATA_FUNCTIONS =
        List.of("set_data", "random_aux_value", "set_data_from_color_index");

    /** `random_aux_value` states what it may land on under `values`, and `set_data` under `data`. */
    private static JsonElement statedData(JsonObject function) {
        return function.has("values") ? function.get("values") : function.get("data");
    }

    /** The one function on an entry that names a data value, of which a pack writes at most one. */
    private static JsonObject dataFunction(JsonObject entry) {
        for (JsonElement element : Json.list(entry, "functions")) {
            if (!element.isJsonObject()) continue;

            JsonObject function = element.getAsJsonObject();
            if (DATA_FUNCTIONS.contains(bare(Json.text(function, "function", "")))) return function;
        }
        return null;
    }

    /**
     * The item an entry hands out, or null for one this version of the game has no id for.
     *
     * A data value picks one item out of the family Java split a Bedrock id into, so an entry
     * stating a number outright is settled here rather than as the table is rolled. A range and a
     * colour index name the first of what they could land on, and the roll swaps in the rest.
     */
    private static String dropped(String name, JsonObject bedrock) {
        JsonObject stated = dataFunction(bedrock);
        Span span = stated == null ? null : Span.tryFrom(statedData(stated));
        return shipped(name, span == null ? 0 : span.lowest());
    }

    /**
     * The Java item a Bedrock id and a data value name, or null when this version ships none, which
     * is reported as it is read.
     *
     * Bedrock spells a few hundred vanilla items its own way, and Java's own names move between the
     * supported versions too: Java made an item of the field masoned and the bordure indented
     * banner pattern later than the rest, and the pale oak sapling later still.
     */
    private static String shipped(String item, int data) {
        String java = BedrockItemNames.java(item, data);
        if (java == null) AddonEntities.unsupported("loot item " + item + (data == 0 ? "" : " " + data));
        return java;
    }

    /**
     * What a data value leaves behind once the entry's own item has taken the settled part of it.
     *
     * A stew and a goat horn keep one id whatever the number says, because Java carries what the
     * number picked inside the stack, so each becomes the Java function that puts it there. Every
     * other family is a different item per value: a number stated outright is the entry's own item
     * already, and a range or a colour index is picked as the table is rolled.
     */
    private static JsonObject dataValue(JsonObject bedrock, String item) {
        String name = bare(Json.text(bedrock, "function", ""));
        boolean fromColour = name.equals("set_data_from_color_index");
        Span span = fromColour ? new Span(0, DataValues.COLOURS - 1)
            : Span.tryFrom(statedData(bedrock));
        if (span == null) return null;

        String family = DataValues.vanilla(item);
        if ("suspicious_stew".equals(family)) return stew(span);
        if ("goat_horn".equals(family)) return instrument(span);

        if (!fromColour && span.lowest() == span.highest()) {
            // A zero is what a Bedrock id means with no data value beside it at all, so an id Java
            // never split keeps the item the entry named and says nothing about the zero.
            if (span.lowest() != 0 && DataValues.item(item, span.lowest()) == null) {
                AddonEntities.unsupported("loot function " + name + " " + item + " " + span);
            }
            return null;
        }

        JsonArray choices = choices(item, span);
        if (choices.isEmpty()) {
            AddonEntities.unsupported("loot function " + name + " " + item + " " + span);
            return null;
        }

        JsonObject picked = new JsonObject();
        picked.addProperty("function", name);
        picked.add("items", choices);
        return BedrockFunction.json(picked);
    }

    /**
     * What each value in the range names, in the order the values run, for the loot roll to pick from.
     *
     * The list travels with the function because a Java id no longer says which Bedrock family the
     * item came out of, and the position has to keep as a colour index counts along it, so a value
     * nothing answers is a hole rather than a gap closed up. A range answering nowhere is empty.
     */
    private static JsonArray choices(String item, Span span) {
        JsonArray choices = new JsonArray();
        boolean answered = false;
        for (int data = span.lowest(); data <= span.highest(); data++) {
            String flattened = DataValues.item(item, data) == null ? null : shipped(item, data);
            answered |= flattened != null;
            choices.add(flattened);
        }
        return answered ? choices : new JsonArray();
    }

    /**
     * The goat horn a data value names, as the tag Java's own function picks one out of.
     *
     * Java keeps all eight horns under one id and names the instrument inside the stack, and a tag
     * of instruments is the only thing that picks one, so the numbers Bedrock gives them are
     * answerable where they line up with a tag: the four regular horns, the four screaming ones, or
     * all eight together.
     */
    private static final Map<Span, String> GOAT_HORNS = Map.of(
        new Span(0, 3), "#minecraft:regular_goat_horns",
        new Span(4, 7), "#minecraft:screaming_goat_horns",
        new Span(0, 7), "#minecraft:goat_horns");

    private static JsonObject instrument(Span span) {
        String tag = GOAT_HORNS.get(span);
        if (tag == null) {
            AddonEntities.unsupported("loot function set_data goat_horn " + span);
            return null;
        }

        JsonObject function = JavaLoot.function("minecraft:set_instrument");
        function.addProperty("options", tag);
        return function;
    }

    /**
     * The effects a stew data value could carry, as the Java function that picks one of them.
     *
     * Java lists the effects a stew may come out with and takes one at random, so a range over
     * Bedrock's numbering is that list, and a number stated outright is the same list with one
     * effect on it.
     */
    private static JsonObject stew(Span span) {
        JsonArray effects = new JsonArray();
        for (int id = span.lowest(); id <= span.highest(); id++) {
            JsonObject effect = stewEffect(id, null);
            if (effect != null) effects.add(effect);
        }
        if (effects.isEmpty()) return null;

        JsonObject function = JavaLoot.function("minecraft:set_stew_effect");
        function.add("effects", effects);
        return function;
    }

    private static JsonArray conditions(JsonObject bedrock) {
        JsonArray conditions = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "conditions")) {
            if (!element.isJsonObject()) continue;
            JsonObject condition = condition(element.getAsJsonObject());
            if (condition != null) conditions.add(condition);
        }
        return conditions;
    }

    /** Only the conditions a Java predicate can ask the same question of are carried across. */
    private static JsonObject condition(JsonObject bedrock) {
        String name = bare(Json.text(bedrock, "condition", ""));
        JsonObject condition;

        switch (name) {
            case "random_chance" -> {
                condition = JavaLoot.condition("minecraft:random_chance");
                condition.add("chance", bedrock.has("chance")
                    ? bedrock.get("chance") : new JsonPrimitive(1));
            }
            case "killed_by_player" -> condition = JavaLoot.condition("minecraft:killed_by_player");
            case "random_chance_with_looting" ->
                condition = Platform.lootingChance(Json.number(bedrock, "chance", 0),
                    Json.number(bedrock, "looting_multiplier", 0));
            case "is_baby" -> {
                JsonObject flags = new JsonObject();
                flags.addProperty("is_baby", true);
                JsonObject predicate = new JsonObject();
                predicate.add(Platform.entityPredicateKey("flags"), flags);
                condition = thisEntity(predicate);
            }
            case "passenger_of_entity" -> {
                JsonObject predicate = new JsonObject();
                predicate.add(Platform.entityPredicateKey("vehicle"),
                    entityType(Json.text(bedrock, "entity_type", null)));
                condition = thisEntity(predicate);
            }
            case "killed_by_entity", "damaged_by_entity" -> {
                JsonObject predicate = new JsonObject();
                predicate.add("source_entity", entityType(Json.text(bedrock, "entity_type", null)));
                condition = JavaLoot.condition("minecraft:damage_source_properties");
                condition.add("predicate", predicate);
            }
            // Each of these reads something a Java predicate has no field for: an entity's own
            // properties, its variant, a property a pack declared, the world difficulty, who tamed
            // the killer, a Bedrock biome tag. This mod answers them itself as the loot is rolled,
            // so the condition keeps the object the pack wrote and names a condition type of its own.
            // `entity_killed` is the same again, asking what the entity the table belongs to just
            // killed. Bedrock names such a table on `minecraft:behavior.eat_mob` and rolls it as the
            // meal goes down, so the answer is the mob that goal just swallowed.
            case "entity_properties", "has_variant", "has_mark_variant", "bool_property",
                "random_difficulty_chance", "random_regional_difficulty_chance",
                "killed_by_player_or_pets", "biome_has_tag", "entity_killed" -> {
                return BedrockCondition.json(bedrock);
            }
            default -> {
                AddonEntities.unsupported("loot condition " + name);
                return null;
            }
        }
        return condition;
    }

    private static JsonObject thisEntity(JsonObject predicate) {
        JsonObject condition = JavaLoot.condition("minecraft:entity_properties");
        condition.addProperty("entity", "this");
        condition.add("predicate", predicate);
        return condition;
    }

    private static JsonObject entityType(String type) {
        JsonObject predicate = new JsonObject();
        String java = type == null ? null : BedrockEntityNames.java(type);
        predicate.addProperty(Platform.entityPredicateKey("entity_type"),
            type == null ? "minecraft:pig" : java == null ? type : java);
        return predicate;
    }

    /**
     * The eight layers of the banner an illager captain carries.
     *
     * Java builds it in its own code out of patterns the game already ships, so a loot table can
     * state the same eight and reach the same banner.
     */
    private static final List<Map.Entry<String, String>> OMINOUS_BANNER = List.of(
        Map.entry("rhombus", "cyan"), Map.entry("stripe_bottom", "light_gray"),
        Map.entry("stripe_center", "gray"), Map.entry("border", "light_gray"),
        Map.entry("stripe_middle", "black"), Map.entry("half_horizontal", "light_gray"),
        Map.entry("circle", "light_gray"), Map.entry("border", "black"));

    private static JsonArray ominousBanner() {
        JsonArray patterns = new JsonArray();
        for (Map.Entry<String, String> layer : OMINOUS_BANNER) {
            JsonObject pattern = new JsonObject();
            pattern.addProperty("pattern", "minecraft:" + layer.getKey());
            pattern.addProperty("color", layer.getValue());
            patterns.add(pattern);
        }
        return patterns;
    }

    /**
     * The effect each suspicious stew id stands for, and how long it lasts by default, in seconds.
     *
     * Bedrock numbers the stew effects rather than naming them, and the numbering is the one its own
     * `suspicious_stew_from_*` recipes write as the item's data value. A pack stating no duration
     * gets the one the stew made from that flower carries, floored at a second because the Java
     * function counts in whole seconds.
     */
    private static final List<Map.Entry<String, Integer>> STEW_EFFECTS = List.of(
        Map.entry("night_vision", 5), Map.entry("jump_boost", 5), Map.entry("weakness", 7),
        Map.entry("blindness", 11), Map.entry("poison", 11), Map.entry("saturation", 1),
        Map.entry("saturation", 1), Map.entry("fire_resistance", 3), Map.entry("regeneration", 7),
        Map.entry("wither", 7), Map.entry("night_vision", 5), Map.entry("blindness", 11),
        Map.entry("nausea", 7));

    private static JsonArray stewEffects(JsonObject bedrock) {
        JsonArray effects = new JsonArray();
        for (JsonElement element : Json.list(bedrock, "effects")) {
            if (!element.isJsonObject()) continue;

            JsonObject stated = element.getAsJsonObject();
            JsonObject effect = stewEffect((int) Json.number(stated, "id", -1),
                stated.get("duration"));
            if (effect != null) effects.add(effect);
        }
        return effects;
    }

    /** One stew effect, lasting as long as the pack said or as long as that stew lasts by default. */
    private static JsonObject stewEffect(int id, JsonElement duration) {
        if (id < 0 || id >= STEW_EFFECTS.size()) {
            AddonEntities.unsupported("loot function set_stew_effect id " + id);
            return null;
        }

        Map.Entry<String, Integer> known = STEW_EFFECTS.get(id);
        JsonObject effect = new JsonObject();
        effect.addProperty("type", "minecraft:" + known.getKey());
        effect.add("duration", duration == null
            ? new JsonPrimitive(known.getValue()) : range(duration));
        return effect;
    }

    /** `specific_enchants` names each enchantment and the level, or the level range, it is given. */
    private static JsonObject enchantments(JsonObject bedrock) {
        JsonObject enchantments = new JsonObject();
        for (JsonElement element : Json.list(bedrock, "enchants")) {
            if (!element.isJsonObject()) continue;
            JsonObject enchant = element.getAsJsonObject();
            String id = Json.text(enchant, "id", null);
            if (id != null) enchantments.add(namespaced(id), range(enchant.get("level")));
        }
        return enchantments;
    }

    private static String namespaced(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** A name a pack may write with or without the vanilla namespace, which means the same thing. */
    private static String bare(String name) {
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /**
     * A count is a number, a `{min, max}` pair or a `[min, max]` pair.
     *
     * Java writes the two pair forms as a uniform distribution between the ends.
     */
    private static JsonElement range(JsonElement bedrock) {
        if (bedrock == null) return new JsonPrimitive(1);
        if (bedrock.isJsonPrimitive()) return bedrock;

        JsonElement low = new JsonPrimitive(1);
        JsonElement high = new JsonPrimitive(1);
        if (bedrock.isJsonArray() && bedrock.getAsJsonArray().size() == 2) {
            low = bedrock.getAsJsonArray().get(0);
            high = bedrock.getAsJsonArray().get(1);
        } else if (bedrock.isJsonObject()) {
            JsonObject body = bedrock.getAsJsonObject();
            if (body.has("min")) low = body.get("min");
            if (body.has("max")) high = body.get("max");
        } else {
            return new JsonPrimitive(1);
        }

        JsonObject uniform = new JsonObject();
        uniform.addProperty("type", "minecraft:uniform");
        uniform.add("min", low);
        uniform.add("max", high);
        return uniform;
    }
}
