package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One screen an NPC opens: a name, a body of text and a row of buttons that each run commands.
 *
 * A pack writes several of these per file and reaches one by its `scene_tag`. Bedrock's own text
 * fields take either a string or the rawtext object a `titleraw` takes, so both are read.
 */
public record DialogueScene(String tag, String name, String text, List<String> onOpen,
    List<String> onClose, List<Button> buttons) {

    public record Button(String name, List<String> commands) {
    }

    public static List<DialogueScene> readAll(BedrockPack pack) {
        List<DialogueScene> scenes = new ArrayList<>();
        for (Path file : pack.list("dialogue", ".json")) {
            try {
                scenes.addAll(read(Json.parseObject(file)));
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return scenes;
    }

    private static List<DialogueScene> read(JsonObject root) {
        List<DialogueScene> scenes = new ArrayList<>();
        if (!root.has("minecraft:npc_dialogue")
            || !root.get("minecraft:npc_dialogue").isJsonObject()) {
            return scenes;
        }

        for (JsonElement element : Json.list(root.getAsJsonObject("minecraft:npc_dialogue"), "scenes")) {
            if (!element.isJsonObject()) continue;
            JsonObject body = element.getAsJsonObject();

            String tag = Json.text(body, "scene_tag", null);
            if (tag == null) continue;

            scenes.add(new DialogueScene(tag, written(body.get("npc_name")),
                written(body.get("text")), commands(body, "on_open_commands"),
                commands(body, "on_close_commands"), buttons(body)));
        }
        return scenes;
    }

    private static List<Button> buttons(JsonObject scene) {
        List<Button> buttons = new ArrayList<>();
        for (JsonElement element : Json.list(scene, "buttons")) {
            if (!element.isJsonObject()) continue;
            JsonObject body = element.getAsJsonObject();
            buttons.add(new Button(written(body.get("name")), commands(body, "commands")));
        }
        return buttons;
    }

    private static List<String> commands(JsonObject body, String key) {
        List<String> commands = new ArrayList<>();
        for (JsonElement element : Json.list(body, key)) {
            if (element.isJsonPrimitive()) commands.add(element.getAsString());
        }
        return commands;
    }

    /**
     * A field a pack writes as a string or as Bedrock's rawtext object.
     *
     * The object holds a `rawtext` list of parts, each a literal under `text` or a translation key
     * under `translate`, and a screen draws them one after another.
     */
    private static String written(JsonElement value) {
        if (value == null) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (!value.isJsonObject()) return "";

        StringBuilder joined = new StringBuilder();
        for (JsonElement part : Json.list(value.getAsJsonObject(), "rawtext")) {
            if (!part.isJsonObject()) continue;
            JsonObject piece = part.getAsJsonObject();
            joined.append(Json.text(piece, "text", Json.text(piece, "translate", "")));
        }
        return joined.toString();
    }
}
