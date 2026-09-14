package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.bedrock.behaviour.DialogueScene;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackManifest;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An NPC's conversation, and the form description the screen draws it from.
 *
 * Bedrock writes a scene's name and its text either as a plain string or as the rawtext object a
 * `titleraw` takes, and a screen that drew the object would show a player its punctuation. What the
 * description has to come out as is what `@minecraft/server-ui` already sends: a title, the text as
 * one label, and a button per button in the order the pack listed them, because the answer names
 * the button by its position.
 */
class DialogueSceneTest {
    @TempDir
    Path directory;

    @Test
    void aSceneIsReadInBothSpellings() throws IOException {
        List<DialogueScene> scenes = read("""
            {
              "format_version": "1.17.0",
              "minecraft:npc_dialogue": {
                "scenes": [
                  {
                    "scene_tag": "tp:plain",
                    "npc_name": "The Keeper",
                    "text": "Well?",
                    "on_open_commands": ["/say opened"],
                    "on_close_commands": ["/say closed"],
                    "buttons": [{"name": "Leave", "commands": ["/say left", "/say again"]}]
                  },
                  {
                    "scene_tag": "tp:rich",
                    "npc_name": {"rawtext": [{"text": "The "}, {"text": "Keeper"}]},
                    "text": {"rawtext": [{"text": "Go warm."}]}
                  }
                ]
              }
            }
            """);

        assertEquals(2, scenes.size());

        DialogueScene plain = scenes.get(0);
        assertEquals("tp:plain", plain.tag());
        assertEquals("The Keeper", plain.name());
        assertEquals(List.of("/say opened"), plain.onOpen());
        assertEquals(List.of("/say closed"), plain.onClose());
        assertEquals(1, plain.buttons().size());
        assertEquals("Leave", plain.buttons().get(0).name());
        assertEquals(List.of("/say left", "/say again"), plain.buttons().get(0).commands());

        DialogueScene rich = scenes.get(1);
        assertEquals("The Keeper", rich.name());
        assertEquals("Go warm.", rich.text());
        assertEquals(List.of(), rich.buttons());
    }

    @Test
    void aSceneBecomesTheFormDescriptionTheScreenDraws() {
        DialogueScene scene = new DialogueScene("tp:plain", "The Keeper", "Well?",
            List.of(), List.of(),
            List.of(new DialogueScene.Button("Feed it", List.of()),
                new DialogueScene.Button("Walk away", List.of())));

        JsonObject drawn = Dialogues.description(scene, "Ember");
        assertEquals("The Keeper", drawn.get("title").getAsString());

        JsonArray elements = drawn.getAsJsonArray("elements");
        assertEquals(3, elements.size());
        assertEquals("label", elements.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("Well?", elements.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("button", elements.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("Feed it", elements.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("Walk away", elements.get(2).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void aSceneWithNoNameOfItsOwnIsTitledAfterTheNpc() {
        DialogueScene scene = new DialogueScene("tp:plain", "", "", List.of(), List.of(), List.of());

        JsonObject drawn = Dialogues.description(scene, "Ember");
        assertEquals("Ember", drawn.get("title").getAsString());
        assertEquals(0, drawn.getAsJsonArray("elements").size());
    }

    private List<DialogueScene> read(String body) throws IOException {
        Path file = directory.resolve("dialogue/keeper.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);

        PackManifest manifest = PackManifest.tryFrom(Json.parse("""
            {
              "header": {"uuid": "00000000-0000-0000-0000-0000000000ff", "version": [1, 0, 0]},
              "modules": [{"type": "data", "uuid": "00000000-0000-0000-0000-0000000000fe"}]
            }
            """).getAsJsonObject());
        return DialogueScene.readAll(new BedrockPack(directory, manifest, "test"));
    }
}
