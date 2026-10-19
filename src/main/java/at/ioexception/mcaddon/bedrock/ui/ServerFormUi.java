package at.ioexception.mcaddon.bedrock.ui;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * A form the server sent, as the controls of a pack's `server_form.json` see it.
 *
 * Bedrock draws every form inside `main_screen_content`, whose `server_form_factory` makes the
 * `long_form` for an action or message form and the `custom_form` for a modal one. A pack either
 * lays a `long_form` of its own over Bedrock's or inserts a second factory beside the first, and in
 * both cases hides whichever does not apply by a `#visible` binding over the form's `#title_text`.
 * Where no pack replaced a form's `controls`, the form is Bedrock's own, which this mod draws as its
 * own screen: `UiControl.VANILLA_FORM` stands where it goes.
 *
 * The form's text is `#form_text`, and its buttons, labels, headers and dividers are the items of
 * the `form_buttons` collection, each carrying `#form_button_text` and `#form_button_texture`.
 */
public final class ServerFormUi implements UiData {
    public static final String BUTTONS = "form_buttons";
    private static final String FACTORY = "server_form_factory";

    private final UiDefinitions definitions;
    private final String kind;
    private final String title;
    private final String text;
    private final List<JsonObject> items;
    private final ToIntFunction<String> width;
    private final int lineHeight;

    public ServerFormUi(UiDefinitions definitions, JsonObject description, ToIntFunction<String> width,
        int lineHeight) {
        this.definitions = definitions;
        this.kind = Json.text(description, "kind", "");
        this.title = Json.text(description, "title", "");
        List<JsonObject> elements = Json.list(description, "elements").stream()
            .filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).toList();
        this.text = String.join("\n", elements.stream().filter(element -> type(element).equals("body"))
            .map(element -> Json.text(element, "text", "")).toList());
        this.items = elements.stream()
            .filter(element -> List.of("button", "label", "header", "divider").contains(type(element)))
            .toList();
        this.width = width;
        this.lineHeight = lineHeight;
    }

    private static String type(JsonObject element) {
        return Json.text(element, "type", "label");
    }

    /**
     * The form laid out on a screen of this size, or null where nothing but Bedrock's own form would be
     * drawn, which is every form of a kind no pack routes and every form while no pack has a
     * `server_form.json`.
     */
    public UiPlaced layout(int screenWidth, int screenHeight) {
        if (kind.isEmpty() || !definitions.names("server_form").iterator().hasNext()) return null;
        UiPlaced placed = new UiLayout(this).place(screen(), 0, 0, screenWidth, screenHeight);
        return placed == null || placed.drawn().noneMatch(UiPlaced::draws) ? null : placed;
    }

    /** Bedrock's `main_screen_content` with what the packs wrote over it or inserted into it. */
    private UiControl screen() {
        String name = "server_form.main_screen_content";
        if (definitions.states(name, "type")) return definitions.instantiate("main_screen_content", name,
            new JsonObject(), "server_form", Map.of());

        JsonObject content = new JsonObject();
        content.addProperty("type", "panel");
        content.add("size", Json.parse("[0, 0]"));
        JsonArray controls = new JsonArray();
        controls.add(Json.parse("{\"" + FACTORY + "\": {\"type\": \"panel\", \"factory\": {\"name\": \"" + FACTORY
            + "\", \"control_ids\": {\"long_form\": \"@server_form.long_form\","
            + " \"custom_form\": \"@server_form.custom_form\"}}}}"));
        definitions.inserted(name, "controls").forEach(controls::add);
        content.add("controls", controls);
        return definitions.instantiate("main_screen_content", null, content, "server_form", Map.of());
    }

    @Override
    public UiControl made(String factory, UiControl owner) {
        if (!factory.equals(FACTORY)) return null;
        JsonElement factoryProperty = owner.property("factory");
        JsonObject ids = factoryProperty.getAsJsonObject().has("control_ids")
            ? factoryProperty.getAsJsonObject().getAsJsonObject("control_ids") : new JsonObject();
        JsonElement reference = ids.get(kind);
        if (reference == null || !reference.isJsonPrimitive()) return null;

        String written = reference.getAsString();
        String bare = written.substring(written.indexOf('@') + 1);
        String qualified = bare.contains(".") ? bare : owner.namespace() + "." + bare;
        if (!qualified.equals("server_form." + kind) || definitions.states(qualified, "controls")
            || definitions.states(qualified, "type")) {
            int at = written.indexOf('@');
            String name = at > 0 ? written.substring(0, at) : qualified.substring(qualified.indexOf('.') + 1);
            return owner.instantiate(name, qualified, null);
        }

        JsonObject form = definitions.written(qualified);
        form = form == null ? new JsonObject() : form;
        form.remove("controls");
        if (!form.has("type")) form.addProperty("type", "panel");
        if (!form.has("size")) form.add("size", Json.parse("[225, 200]"));
        JsonArray controls = new JsonArray();
        controls.add(Json.parse("{\"form@server_form.long_form_panel\": {}}"));
        definitions.inserted(qualified, "controls").forEach(controls::add);
        form.add("controls", controls);
        return definitions.instantiate(kind, null, form, "server_form", owner.variables());
    }

    @Override
    public Object global(String binding) {
        return switch (binding) {
            case "#title_text" -> title;
            case "#form_text" -> text;
            default -> null;
        };
    }

    @Override
    public Object item(String collection, int index, String binding) {
        if (!collection.equals(BUTTONS) || index < 0 || index >= items.size()) return null;
        JsonObject item = items.get(index);
        return switch (binding) {
            case "#form_button_text" -> Json.text(item, "text", "");
            case "#form_button_texture" -> Json.text(item, "icon", "");
            case "#form_button_texture_file_system" -> "InternalPath";
            default -> null;
        };
    }

    @Override
    public int length(String collection) {
        return collection.equals(BUTTONS) ? items.size() : 0;
    }

    @Override
    public String kind(String collection, int index) {
        return collection.equals(BUTTONS) && index >= 0 && index < items.size() ? type(items.get(index)) : "";
    }

    /** The element of the description an item of `form_buttons` stands for, or null. */
    public JsonObject element(int index) {
        return index >= 0 && index < items.size() ? items.get(index) : null;
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
