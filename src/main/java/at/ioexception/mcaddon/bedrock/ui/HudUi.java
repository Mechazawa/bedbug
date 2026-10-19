package at.ioexception.mcaddon.bedrock.ui;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The title, subtitle and action bar as a pack's `hud_screen.json` sees them.
 *
 * The title and subtitle reach the heads-up display as the global bindings
 * `#hud_title_text_string` and `#hud_subtitle_text_string`, which keep the last text sent; the action
 * bar reaches it only as `$actionbar_text`, the variable `hud_actionbar_text_factory` hands the
 * control it makes each time one is sent, and `hud_title_text_factory` makes its control the same way
 * for a title. A pack adds controls of its own by inserting them into `root_panel`, and hides
 * Bedrock's own title or action bar by a `visible` or a `#visible` binding laid over
 * `hud_title_text`, `hud_actionbar_text` or one of the controls inside them, named by its path.
 */
public final class HudUi implements UiData {
    private static final List<String> TITLE = List.of("hud.hud_title_text", "hud.hud_title_text/title_frame",
        "hud.hud_title_text/title_frame/title");
    private static final List<String> SUBTITLE = List.of("hud.hud_title_text",
        "hud.hud_title_text/subtitle_frame", "hud.hud_title_text/subtitle_frame/subtitle");
    private static final List<String> ACTIONBAR = List.of("hud.hud_actionbar_text",
        "hud.hud_actionbar_text/actionbar_message");

    private final UiDefinitions definitions;
    private final String title;
    private final String subtitle;
    private final String actionbar;
    private final ToIntFunction<String> width;
    private final int lineHeight;

    /** A null `actionbar` or `title` is one not sent since the display was last cleared. */
    public HudUi(UiDefinitions definitions, String title, String subtitle, String actionbar,
        ToIntFunction<String> width, int lineHeight) {
        this.definitions = definitions;
        this.title = title;
        this.subtitle = subtitle;
        this.actionbar = actionbar;
        this.width = width;
        this.lineHeight = lineHeight;
    }

    public boolean hidesTitle() {
        return hides(TITLE, Map.of());
    }

    public boolean hidesSubtitle() {
        return hides(SUBTITLE, Map.of());
    }

    public boolean hidesActionbar() {
        return actionbar != null && hides(ACTIONBAR, Map.of("$actionbar_text", new JsonPrimitive(actionbar)));
    }

    /** Whether any of the controls on the way to a piece of Bedrock's display is hidden by a pack. */
    private boolean hides(List<String> path, Map<String, com.google.gson.JsonElement> variables) {
        return path.stream().filter(definitions::defines).anyMatch(name -> {
            JsonObject written = definitions.written(name);
            written.remove("controls");
            written.addProperty("type", "panel");
            written.add("size", Json.parse("[0, 0]"));
            UiControl control = definitions.instantiate(name, null, written, "hud", variables);
            return control != null && new UiLayout(this).place(control, 0, 0, 0, 0) == null;
        });
    }

    /** What the packs inserted into `root_panel`, laid out over the whole display, or null. */
    public UiPlaced layout(int displayWidth, int displayHeight) {
        List<com.google.gson.JsonElement> inserted = definitions.inserted("hud.root_panel", "controls");
        if (inserted.isEmpty()) return null;

        JsonObject root = new JsonObject();
        root.addProperty("type", "panel");
        root.add("size", Json.parse("[\"100%\", \"100%\"]"));
        JsonArray controls = new JsonArray();
        inserted.forEach(controls::add);
        root.add("controls", controls);
        UiControl control = definitions.instantiate("root_panel", null, root, "hud", Map.of());
        return control == null ? null : new UiLayout(this).place(control, 0, 0, displayWidth, displayHeight);
    }

    @Override
    public UiControl made(String factory, UiControl owner) {
        String id = switch (factory) {
            case "hud_actionbar_text_factory" -> actionbar == null ? null : "hud_actionbar_text";
            case "hud_title_text_factory" -> title == null ? null : "hud_title_text";
            default -> null;
        };
        JsonObject ids = owner.property("factory").getAsJsonObject().has("control_ids")
            ? owner.property("factory").getAsJsonObject().getAsJsonObject("control_ids") : new JsonObject();
        if (id == null || !ids.has(id) || !ids.get(id).isJsonPrimitive()) return null;

        String written = ids.get(id).getAsString();
        int at = written.indexOf('@');
        Map<String, com.google.gson.JsonElement> variables = new java.util.HashMap<>(owner.variables());
        if (actionbar != null) variables.put("$actionbar_text", new JsonPrimitive(actionbar));
        String reference = written.substring(at + 1);
        return definitions.instantiate(at > 0 ? written.substring(0, at)
            : reference.substring(reference.indexOf('.') + 1), reference, new JsonObject(), owner.namespace(),
            variables);
    }

    @Override
    public Object global(String binding) {
        return switch (binding) {
            case "#hud_title_text_string" -> title == null ? "" : title;
            case "#hud_subtitle_text_string" -> subtitle == null ? "" : subtitle;
            default -> null;
        };
    }

    @Override
    public Object item(String collection, int index, String binding) {
        return null;
    }

    @Override
    public int length(String collection) {
        return 0;
    }

    @Override
    public String kind(String collection, int index) {
        return "";
    }

    @Override
    public int textWidth(String line) {
        return width.applyAsInt(line);
    }

    @Override
    public int lineHeight() {
        return lineHeight;
    }
}
