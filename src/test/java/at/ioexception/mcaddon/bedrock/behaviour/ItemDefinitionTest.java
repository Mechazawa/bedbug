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
