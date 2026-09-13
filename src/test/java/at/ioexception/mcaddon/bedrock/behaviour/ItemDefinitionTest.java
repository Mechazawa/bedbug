package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.pack.BedrockPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is read out of an item a behaviour pack declares.
 *
 * Bedrock has respelled its component format several times without retiring the older spellings, so
 * one pack folder holds items written against three of them. `minecraft:icon` is the worst of it:
 * a bare string, an object with `texture`, and from format 1.21.20 a map of states under `textures`
 * all mean the same thing, and an icon that goes unread is an item with no model at all.
 */
class ItemDefinitionTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "{\"minecraft:icon\": \"vn_thing\"} -> vn_thing",
        "{\"minecraft:icon\": {\"texture\": \"vn_thing\"}} -> vn_thing",
        "{\"minecraft:icon\": {\"textures\": {\"default\": \"vn_thing\"}}} -> vn_thing",
        "{\"minecraft:icon\": {\"textures\": {\"broken\": \"vn_thing\"}}} -> vn_thing",
    })
    void readsEverySpellingOfTheIconComponent(String components, String expected) throws IOException {
        assertEquals(expected, item(components).iconKey());
    }

    @Test
    void hasNoIconWhenTheItemDeclaresNone() throws IOException {
        assertNull(item("{}").iconKey());
        assertNull(item("{\"minecraft:icon\": {}}").iconKey());
    }

    /** A component written as a bare value is kept where the object form puts it. */
    @Test
    void keepsABareComponentValueUnderValue() throws IOException {
        ItemDefinition item = item("""
            {"minecraft:max_stack_size": 16, "minecraft:hand_equipped": true, "minecraft:icon": "vn_thing"}
            """);

        assertEquals(16, item.components().get("minecraft:max_stack_size").get("value").getAsInt());
        assertTrue(item.components().get("minecraft:hand_equipped").get("value").getAsBoolean());
        assertEquals("vn_thing", item.components().get("minecraft:icon").get("value").getAsString());
    }

    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "{\"minecraft:max_stack_size\": 16} -> 16",
        "{\"minecraft:max_stack_size\": {\"value\": 16}} -> 16",
        "{} -> 64",
        "{\"minecraft:max_stack_size\": {}} -> 64",
    })
    void readsEitherSpellingOfTheStackSize(String components, int expected) throws IOException {
        assertEquals(expected, item(components).maxStackSize());
    }

    @Test
    void readsTheDisplayNameKey() throws IOException {
        assertEquals("item.vn:thing.name",
            item("{\"minecraft:display_name\": {\"value\": \"item.vn:thing.name\"}}").displayNameKey());
        assertNull(item("{}").displayNameKey());
    }

    @Test
    void keepsEveryComponentTheItemDeclares() throws IOException {
        ItemDefinition item = item("{\"minecraft:food\": {\"nutrition\": 4}, \"minecraft:wearable\": {}}");

        assertEquals(List.of("minecraft:food", "minecraft:wearable"), List.copyOf(item.components().keySet()));
        assertEquals(4, item.components().get("minecraft:food").get("nutrition").getAsInt());
    }

    /**
     * A pack declares food beside an animation of `none` to make an item hold-to-use without being
     * eaten, because in Bedrock the animation is what drives the interaction.
     */
    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "{\"minecraft:food\": {\"nutrition\": 4}} -> true",
        "{\"minecraft:food\": {\"nutrition\": 4}, \"minecraft:use_animation\": \"eat\"} -> true",
        "{\"minecraft:food\": {\"nutrition\": 4}, \"minecraft:use_animation\": \"drink\"} -> true",
        "{\"minecraft:food\": {\"nutrition\": 0}, \"minecraft:use_animation\": \"none\"} -> false",
        "{\"minecraft:food\": {\"use_animation\": \"none\"}} -> false",
        "{\"minecraft:use_animation\": \"eat\"} -> false",
    })
    void eatsOnlyWhatTheAnimationSaysIsEaten(String components, boolean expected) throws IOException {
        assertEquals(expected, item(components).edible());
    }

    /** Bedrock takes either a number or one of its named tiers where Java takes only a number. */
    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "{\"minecraft:food\": {\"saturation_modifier\": 0.4}} -> 0.4",
        "{\"minecraft:food\": {\"saturation_modifier\": \"good\"}} -> 0.8",
        "{\"minecraft:food\": {\"saturation_modifier\": \"supernatural\"}} -> 1.2",
        "{\"minecraft:food\": {}} -> 0.6",
        "{} -> 0.6",
    })
    void readsSaturationAsANumberOrATier(String components, float expected) throws IOException {
        assertEquals(expected, item(components).saturation(), 0.0001f);
    }

    /** `use_modifiers` counts a use in seconds and `minecraft:use_duration` counts it in ticks. */
    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "{\"minecraft:use_modifiers\": {\"use_duration\": 3.2}} -> 3.2",
        "{\"minecraft:use_duration\": 32} -> 1.6",
        "{} -> 1.6",
    })
    void countsAUseInSeconds(String components, float expected) throws IOException {
        assertEquals(expected, item(components).useSeconds(), 0.0001f);
    }

    @ParameterizedTest
    @CsvSource(delimiterString = "->", value = {
        "{\"minecraft:durability\": {\"max_durability\": 250}} -> 250",
        "{\"minecraft:max_damage\": 250} -> 250",
        "{\"minecraft:max_damage\": 0} -> 0",
        "{} -> 0",
    })
    void readsEitherSpellingOfTheDurability(String components, int expected) throws IOException {
        assertEquals(expected, item(components).durability());
    }

    /** A speed entry whose block is a Molang tag query names no fixed block to answer with. */
    @Test
    void keepsOnlyTheDiggerEntriesThatNameABlock() throws IOException {
        ItemDefinition item = item("""
            {"minecraft:digger": {"destroy_speeds": [
                {"block": "minecraft:stone", "speed": 6},
                {"block": {"tags": "q.any_tag('stone')"}, "speed": 4},
                {"block": "minecraft:dirt"}]}}
            """);

        assertEquals(List.of(new ItemDefinition.DestroySpeed("minecraft:stone", 6f),
            new ItemDefinition.DestroySpeed("minecraft:dirt", 1f)), item.destroySpeeds());
    }

    /** Each repair entry holds its own list of items, and a bare name stands for a list of one. */
    @Test
    void flattensTheRepairItems() throws IOException {
        ItemDefinition item = item("""
            {"minecraft:repairable": {"repair_items": [
                {"items": ["minecraft:stick", "minecraft:oak_planks"], "repair_amount": 10},
                "minecraft:diamond"]}}
            """);

        assertEquals(List.of("minecraft:stick", "minecraft:oak_planks", "minecraft:diamond"),
            item.repairItems());
    }

    /** Bedrock turns a component off by writing false into it, keeping the component declared. */
    @Test
    void readsAComponentSwitchedOff() throws IOException {
        assertTrue(item("{\"minecraft:glint\": true}").declaredTrue("minecraft:glint"));
        assertTrue(item("{\"minecraft:glint\": {}}").declaredTrue("minecraft:glint"));
        assertFalse(item("{\"minecraft:glint\": false}").declaredTrue("minecraft:glint"));
        assertFalse(item("{}").declaredTrue("minecraft:glint"));
    }

    /** A weapon that names one reach reaches that far in creative too, which is Bedrock's rule. */
    @Test
    void fallsBackToTheOrdinaryReachInCreative() throws IOException {
        ItemDefinition.Reach reach = item("""
            {"minecraft:piercing_weapon": {"reach": {"min": 1, "max": 4}, "hitbox_margin": 0.25}}
            """).reach("minecraft:piercing_weapon");

        assertEquals(1f, reach.min());
        assertEquals(4f, reach.max());
        assertEquals(1f, reach.creativeMin());
        assertEquals(4f, reach.creativeMax());
        assertEquals(0.25f, reach.margin());
    }

    @Test
    void readsTheThreeBlocksAWeaponReachesWhenItNamesNoRange() throws IOException {
        ItemDefinition.Reach reach =
            item("{\"minecraft:kinetic_weapon\": {}}").reach("minecraft:kinetic_weapon");

        assertEquals(0f, reach.min());
        assertEquals(3f, reach.max());
        assertNull(item("{}").reach("minecraft:kinetic_weapon"));
    }

    /** An effect whose conditions the pack left out never applies, so absence is not a condition. */
    @Test
    void readsOnlyTheKineticConditionsThePackWrote() throws IOException {
        ItemDefinition item = item("""
            {"minecraft:kinetic_weapon": {"damage_conditions": {"min_relative_speed": 0.4}}}
            """);

        ItemDefinition.KineticCondition damage = item.kineticCondition("damage_conditions");
        assertEquals(-1, damage.durationTicks());
        assertEquals(0f, damage.speed());
        assertEquals(0.4f, damage.relativeSpeed());
        assertNull(item.kineticCondition("knockback_conditions"));
    }

    @Test
    void skipsAFileThatDeclaresNoUsableItem() throws IOException {
        write("items/block.json", "{\"minecraft:block\": {\"description\": {\"identifier\": \"vn:thing\"}}}");
        write("items/nameless.json", "{\"minecraft:item\": {\"description\": {}}}");
        write("items/empty.json", "{}");

        assertEquals(List.of(), ItemDefinition.readAll(pack()));
    }

    private ItemDefinition item(String components) throws IOException {
        write("items/thing.json", """
            {"minecraft:item": {"description": {"identifier": "vn:thing"}, "components": %s}}
            """.formatted(components));

        List<ItemDefinition> items = ItemDefinition.readAll(pack());
        assertEquals(1, items.size(), components);
        assertEquals("vn:thing", items.get(0).identifier());
        return items.get(0);
    }

    private BedrockPack pack() {
        return new BedrockPack(directory, null, "test.mcaddon");
    }

    private void write(String path, String body) throws IOException {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
    }
}
