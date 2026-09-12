package at.ioexception.mcaddon.client.screen;

import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The screen a Bedrock form becomes.
 *
 * Bedrock's forms are described by the server and drawn by the client, which Java has nothing like,
 * so this draws the description the server sent and sends back what the player chose. The answer is
 * in the order the inputs were declared, because that is the order a pack reads `formValues` in.
 *
 * Everything is a button, a text box or a slider, and a label is a button that cannot be pressed.
 * Drawing text directly would mean a different call on every supported version for no gain.
 */
public final class AddonFormScreen extends Screen {
    private static final int WIDGET_WIDTH = 240;
    private static final int ROW_HEIGHT = 24;

    private final int formId;
    private final JsonArray elements;
    private final List<Supplier<JsonElement>> answers = new ArrayList<>();

    private Integer chosenButton;
    private boolean answered;

    public AddonFormScreen(int formId, String description) {
        super(title(description));
        this.formId = formId;

        JsonElement parsed = JsonParser.parseString(description);
        JsonObject body = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        this.elements = body.has("elements") && body.get("elements").isJsonArray()
            ? body.getAsJsonArray("elements") : new JsonArray();
    }

    private static Component title(String description) {
        JsonElement parsed = JsonParser.parseString(description);
        String heading = parsed.isJsonObject() ? Json.text(parsed.getAsJsonObject(), "title", "") : "";
        return Component.literal(heading.isBlank() ? "Add-on" : heading);
    }

    @Override
    protected void init() {
        answers.clear();

        int left = width / 2 - WIDGET_WIDTH / 2;
        int row = Math.max(36, height / 2 - elements.size() * ROW_HEIGHT / 2);
        int buttonIndex = 0;

        for (JsonElement element : elements) {
            if (!element.isJsonObject()) continue;
            JsonObject widget = element.getAsJsonObject();
            String type = Json.text(widget, "type", "label");
            String text = Json.text(widget, "text", "");

            switch (type) {
                case "button", "submit" -> {
                    int index = buttonIndex++;
                    addRenderableWidget(Button.builder(Component.literal(text), button -> {
                        chosenButton = index;
                        answer(false);
                    }).bounds(left, row, WIDGET_WIDTH, 20).build());
                }
                case "toggle" -> {
                    boolean[] state = {widget.has("value") && widget.get("value").getAsBoolean()};
                    Button[] toggle = new Button[1];
                    toggle[0] = Button.builder(Component.literal(text + ": " + onOff(state[0])), button -> {
                        state[0] = !state[0];
                        toggle[0].setMessage(Component.literal(text + ": " + onOff(state[0])));
                    }).bounds(left, row, WIDGET_WIDTH, 20).build();
                    addRenderableWidget(toggle[0]);
                    answers.add(() -> new JsonPrimitive(state[0]));
                }
                case "dropdown" -> {
                    List<String> options = new ArrayList<>();
                    for (JsonElement option : Json.list(widget, "options")) options.add(option.getAsString());
                    if (options.isEmpty()) options.add("");

                    int[] chosen = {widget.has("value")
                        ? Mth.clamp(widget.get("value").getAsInt(), 0, options.size() - 1) : 0};
                    Button[] dropdown = new Button[1];
                    dropdown[0] = Button.builder(Component.literal(text + ": " + options.get(chosen[0])),
                        button -> {
                            chosen[0] = (chosen[0] + 1) % options.size();
                            dropdown[0].setMessage(Component.literal(text + ": " + options.get(chosen[0])));
                        }).bounds(left, row, WIDGET_WIDTH, 20).build();
                    addRenderableWidget(dropdown[0]);
                    answers.add(() -> new JsonPrimitive(chosen[0]));
                }
                case "slider" -> {
                    double minimum = widget.has("min") ? widget.get("min").getAsDouble() : 0;
                    double maximum = widget.has("max") ? widget.get("max").getAsDouble() : 1;
                    double initial = widget.has("value") ? widget.get("value").getAsDouble() : minimum;
                    ValueSlider slider = addRenderableWidget(
                        new ValueSlider(left, row, text, minimum, maximum, initial));
                    answers.add(() -> new JsonPrimitive(slider.value()));
                }
                case "textField" -> {
                    EditBox field = new EditBox(font, left, row, WIDGET_WIDTH, 20, Component.literal(text));
                    field.setValue(Json.text(widget, "value", ""));
                    field.setHint(Component.literal(Json.text(widget, "placeholder", "")));
                    addRenderableWidget(field);
                    answers.add(() -> new JsonPrimitive(field.getValue()));
                }
                case "divider", "spacer" -> {
                    row += ROW_HEIGHT / 2;
                    continue;
                }
                default -> {
                    Button label = addRenderableWidget(Button.builder(Component.literal(text), button -> { })
                        .bounds(left, row, WIDGET_WIDTH, 20).build());
                    label.active = false;
                }
            }
            row += ROW_HEIGHT;
        }

        // A form with no button of its own still needs a way out that counts as an answer.
        if (buttonIndex == 0) {
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> {
                chosenButton = 0;
                answer(false);
            }).bounds(left, row, WIDGET_WIDTH, 20).build());
        }
    }

    @Override
    public void onClose() {
        answer(true);
        super.onClose();
    }

    /** Sent once: a closed form and a submitted one are the same packet with a different flag. */
    private void answer(boolean cancelled) {
        if (answered) return;
        answered = true;

        JsonObject response = new JsonObject();
        response.addProperty("canceled", cancelled);
        if (cancelled) {
            response.addProperty("cancelationReason", "UserClosed");
        } else {
            if (chosenButton != null) response.addProperty("selection", chosenButton);
            JsonArray values = new JsonArray();
            answers.forEach(answer -> values.add(answer.get()));
            response.add("formValues", values);
        }

        Channels.sendFormResponse(formId, response.toString());
        if (!cancelled) at.ioexception.mcaddon.platform.Platform.setScreen(null);
    }

    private static String onOff(boolean state) {
        return state ? "on" : "off";
    }

    /** A slider that reports the value rather than a fraction, which is what a pack asked for. */
    private final class ValueSlider extends AbstractSliderButton {
        private final String label;
        private final double minimum;
        private final double maximum;

        ValueSlider(int x, int y, String label, double minimum, double maximum, double initial) {
            super(x, y, WIDGET_WIDTH, 20, Component.literal(label),
                maximum <= minimum ? 0 : (initial - minimum) / (maximum - minimum));
            this.label = label;
            this.minimum = minimum;
            this.maximum = maximum;
            updateMessage();
        }

        double value() {
            return minimum + this.value * (maximum - minimum);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + Math.round(value())));
        }

        @Override
        protected void applyValue() {
        }
    }
}
