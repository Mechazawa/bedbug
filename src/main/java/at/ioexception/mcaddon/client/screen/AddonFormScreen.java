package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The screen a Bedrock form becomes.
 *
 * Bedrock's forms are described by the server and drawn by the client, which Java has nothing like,
 * so this draws the description the server sent and sends back what the player chose. The answer is
 * in the order the inputs were declared, because that is the order a pack reads `formValues` in.
 *
 * A form stays on the screen while the server describes it again: a press on a button marked `stay`
 * and a change to an input marked `bound` are sent without closing it, and the description that
 * comes back is drawn in place. Every input keeps its value in its own element of the description,
 * so a redraw shows what the player last set and an answer is read straight out of the description.
 *
 * Everything is a button, a text box or a slider, and a label is a button that cannot be pressed.
 * Drawing text directly would mean a different call on every supported version for no gain.
 */
public final class AddonFormScreen extends Screen {
    private static final int WIDGET_WIDTH = 240;
    private static final int ROW_HEIGHT = 24;

    /** A line of body text takes the height of the text, not the height of a button. */
    private static final int LINE_HEIGHT = 12;

    /** Room for the button's own border on each side of the text it holds. */
    private static final int TEXT_PADDING = 8;

    /** Between the heading and the first widget, so the two do not read as one block of text. */
    private static final int HEADING_GAP = 6;

    private static final String VALUE = "value";

    private final int formId;
    private JsonObject description;
    private final Map<Integer, AbstractWidget> widgets = new HashMap<>();

    private boolean answered;

    private AddonFormScreen(int formId, JsonObject description) {
        super(heading(description));
        this.formId = formId;
        this.description = description;
    }

    /**
     * Opens a form, closes the one showing on an empty description, or redraws the one showing when
     * the server describes it again. A description of a form that is no longer showing is dropped
     * rather than opened again, because the player closed it while it was on its way.
     */
    public static void receive(int formId, String description) {
        if (description.isEmpty()) {
            Platform.setScreen(null);
            return;
        }

        JsonElement parsed = JsonParser.parseString(description);
        JsonObject body = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        if (body.remove("update") == null) {
            Platform.setScreen(new AddonFormScreen(formId, body));
        } else if (Platform.currentScreen() instanceof AddonFormScreen open && open.formId == formId) {
            open.update(body);
        }
    }

    public int formId() {
        return formId;
    }

    private static Component heading(JsonObject description) {
        String heading = Json.text(description, "title", "");
        return Component.literal(heading.isBlank() ? "Add-on" : heading);
    }

    private List<JsonObject> elements() {
        List<JsonObject> elements = new ArrayList<>();
        for (JsonElement element : Json.list(description, "elements")) {
            if (element.isJsonObject()) elements.add(element.getAsJsonObject());
        }
        return elements;
    }

    private static boolean input(JsonObject element) {
        return switch (Json.text(element, "type", "label")) {
            case "toggle", "dropdown", "slider", "textField" -> true;
            default -> false;
        };
    }

    /**
     * Draws a description the server sent for the form already showing, keeping the screen and what
     * has the focus. A text field the player is typing in keeps what they typed, because an update
     * started before the last keystroke reached the server would otherwise take it away again.
     */
    private void update(JsonObject next) {
        if (next.equals(description)) return;

        Integer focused = widgets.entrySet().stream()
            .filter(entry -> entry.getValue() == getFocused())
            .map(Map.Entry::getKey)
            .findFirst().orElse(null);
        if (focused != null && widgets.get(focused) instanceof EditBox typing) {
            Json.list(next, "elements").stream()
                .filter(JsonElement::isJsonObject)
                .map(JsonElement::getAsJsonObject)
                .filter(element -> (int) Json.number(element, "id", -1) == focused)
                .forEach(element -> element.addProperty(VALUE, typing.getValue()));
        }

        description = next;
        rebuildWidgets();
        if (focused != null && widgets.containsKey(focused)) setFocused(widgets.get(focused));
    }

    /**
     * One line of body text per line it needs, because a form describes its text and not its layout.
     *
     * A pack writes a paragraph with newlines in it and expects the game to lay it out. Java draws a
     * button's message on one line and shows a newline as a missing glyph, so the breaks a pack asked
     * for are taken here and anything still too wide for the width is broken at a space.
     */
    private List<String> wrapped(String text) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\r?\\n", -1)) {
            String rest = paragraph;
            while (font.width(rest) > WIDGET_WIDTH - TEXT_PADDING) {
                int cut = rest.length();
                while (cut > 1 && font.width(rest.substring(0, cut)) > WIDGET_WIDTH - TEXT_PADDING) {
                    cut = rest.lastIndexOf(' ', cut - 1) > 0 ? rest.lastIndexOf(' ', cut - 1) : cut - 1;
                }
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut).stripLeading();
            }
            lines.add(rest);
        }
        return lines;
    }

    @Override
    protected void init() {
        widgets.clear();
        List<JsonObject> elements = elements();

        int left = width / 2 - WIDGET_WIDTH / 2;
        int row = Math.max(36, height / 2 - elements.size() * ROW_HEIGHT / 2);
        int buttonIndex = 0;

        // The heading is what a form is called and what an NPC scene names the speaker with, and a
        // screen draws nothing of its own title.
        addRenderableWidget(new StringWidget(left, row - LINE_HEIGHT - HEADING_GAP, WIDGET_WIDTH,
            LINE_HEIGHT, heading(description), font));

        for (JsonObject widget : elements) {
            String type = Json.text(widget, "type", "label");
            String text = Json.text(widget, "text", "");
            AbstractWidget drawn;

            switch (type) {
                case "button", "submit" -> {
                    int index = buttonIndex++;
                    drawn = Button.builder(Component.literal(text), button -> press(widget, index))
                        .bounds(left, row, WIDGET_WIDTH, 20).build();
                }
                case "toggle" -> drawn = Button.builder(Component.literal(toggleLabel(widget)), button -> {
                    widget.addProperty(VALUE, !Json.bool(widget, VALUE, false));
                    button.setMessage(Component.literal(toggleLabel(widget)));
                    changed(widget);
                }).bounds(left, row, WIDGET_WIDTH, 20).build();
                case "dropdown" -> {
                    List<String> options = new ArrayList<>();
                    for (JsonElement option : Json.list(widget, "options")) options.add(option.getAsString());
                    if (options.isEmpty()) options.add("");
                    widget.addProperty(VALUE, Mth.clamp((int) Json.number(widget, VALUE, 0), 0, options.size() - 1));

                    drawn = Button.builder(Component.literal(text + ": " + options.get(widget.get(VALUE).getAsInt())),
                        button -> {
                            int chosen = (widget.get(VALUE).getAsInt() + 1) % options.size();
                            widget.addProperty(VALUE, chosen);
                            button.setMessage(Component.literal(text + ": " + options.get(chosen)));
                            changed(widget);
                        }).bounds(left, row, WIDGET_WIDTH, 20).build();
                }
                case "slider" -> {
                    double minimum = Json.number(widget, "min", 0);
                    double maximum = Json.number(widget, "max", 1);
                    drawn = new ValueSlider(left, row, widget, minimum, maximum,
                        Json.number(widget, VALUE, minimum));
                }
                case "textField" -> {
                    EditBox field = new EditBox(font, left, row, WIDGET_WIDTH, 20, Component.literal(text));
                    field.setValue(Json.text(widget, VALUE, ""));
                    field.setHint(Component.literal(Json.text(widget, "placeholder", "")));
                    field.setResponder(typed -> {
                        if (typed.equals(Json.text(widget, VALUE, ""))) return;
                        widget.addProperty(VALUE, typed);
                        changed(widget);
                    });
                    drawn = field;
                }
                case "divider", "spacer" -> {
                    row += ROW_HEIGHT / 2;
                    continue;
                }
                default -> {
                    for (String line : wrapped(text)) {
                        addRenderableWidget(new StringWidget(left, row, WIDGET_WIDTH, LINE_HEIGHT,
                            Component.literal(line), font));
                        row += LINE_HEIGHT;
                    }
                    continue;
                }
            }

            drawn.active = !Json.bool(widget, "disabled", false);
            addRenderableWidget(drawn);
            widgets.put((int) Json.number(widget, "id", -1), drawn);
            row += ROW_HEIGHT;
        }

        // A form with no button of its own still needs a way out that counts as an answer.
        if (buttonIndex == 0) {
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> answer(false, 0, null))
                .bounds(left, row, WIDGET_WIDTH, 20).build());
        }
    }

    private void press(JsonObject button, int index) {
        if (Json.bool(button, "stay", false)) {
            JsonObject response = open();
            response.add("button", button.get("id"));
            Channels.sendFormResponse(formId, response.toString());
        } else {
            answer(false, index, button.get("id"));
        }
    }

    /** An input bound to an observable is written back as it changes, so the form can react to it. */
    private void changed(JsonObject input) {
        if (Json.bool(input, "bound", false)) Channels.sendFormResponse(formId, open().toString());
    }

    /** What a press or a change sends while the form stays on the screen. */
    private JsonObject open() {
        JsonObject response = new JsonObject();
        response.addProperty("open", true);
        response.add("values", values());
        return response;
    }

    /** Every input's value under the id the server gave it. */
    private JsonObject values() {
        JsonObject values = new JsonObject();
        elements().stream()
            .filter(AddonFormScreen::input)
            .filter(element -> element.has(VALUE))
            .forEach(element -> values.add(String.valueOf((int) Json.number(element, "id", -1)), element.get(VALUE)));
        return values;
    }

    @Override
    public void onClose() {
        answer(true, null, null);
        super.onClose();
    }

    /** Sent once: a closed form and a submitted one are the same packet with a different flag. */
    private void answer(boolean cancelled, Integer selection, JsonElement button) {
        if (answered) return;
        answered = true;

        JsonObject response = new JsonObject();
        response.addProperty("canceled", cancelled);
        response.add("values", values());
        if (cancelled) {
            response.addProperty("cancelationReason", "UserClosed");
        } else {
            if (selection != null) response.addProperty("selection", selection);
            if (button != null) response.add("button", button);
            JsonArray formValues = new JsonArray();
            elements().stream()
                .filter(AddonFormScreen::input)
                .map(element -> element.has(VALUE) ? element.get(VALUE) : null)
                .forEach(formValues::add);
            response.add("formValues", formValues);
        }

        Channels.sendFormResponse(formId, response.toString());
        if (!cancelled) Platform.setScreen(null);
    }

    private static String toggleLabel(JsonObject toggle) {
        return Json.text(toggle, "text", "") + ": " + (Json.bool(toggle, VALUE, false) ? "on" : "off");
    }

    /**
     * A slider that reports the value rather than a fraction, which is what a pack asked for. It
     * writes into its element as it moves and sends nothing of its own, because a description coming
     * back mid-drag would redraw the slider out from under the mouse; the value travels with the next
     * press or change.
     */
    private final class ValueSlider extends AbstractSliderButton {
        private final JsonObject element;
        private final double minimum;
        private final double maximum;

        ValueSlider(int x, int y, JsonObject element, double minimum, double maximum, double initial) {
            super(x, y, WIDGET_WIDTH, 20, Component.literal(Json.text(element, "text", "")),
                maximum <= minimum ? 0 : (initial - minimum) / (maximum - minimum));
            this.element = element;
            this.minimum = minimum;
            this.maximum = maximum;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(Json.text(element, "text", "") + ": " + Math.round(current())));
        }

        @Override
        protected void applyValue() {
            element.addProperty(VALUE, current());
        }

        private double current() {
            return minimum + this.value * (maximum - minimum);
        }
    }
}
