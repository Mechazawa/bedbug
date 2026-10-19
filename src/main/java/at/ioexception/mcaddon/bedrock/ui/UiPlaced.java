package at.ioexception.mcaddon.bedrock.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A control where the layout put it, in the screen's units, with the values its bindings took.
 *
 * `layer` adds up the layers of the control and every control above it, which is the order Bedrock
 * draws in. `index` is the item of a collection the control stands for, or -1 outside one.
 */
public record UiPlaced(UiControl control, int x, int y, int width, int height, int layer, int index,
    Map<String, Object> bound, List<UiPlaced> children) {

    /** This control and every one under it, in the order they are drawn. */
    public Stream<UiPlaced> drawn() {
        return Stream.concat(Stream.of(this), children.stream().flatMap(UiPlaced::drawn))
            .sorted(java.util.Comparator.comparingInt(UiPlaced::layer));
    }

    /** Whether the control draws something of its own, where a panel only holds others. */
    public boolean draws() {
        return switch (type()) {
            case "label", "image", "button", UiControl.VANILLA_BUTTON -> true;
            default -> false;
        };
    }

    public String type() {
        return control.type();
    }

    /** A label's text: a binding it names, an expression, a variable already settled, or the text itself. */
    public String text() {
        return UiExpression.text(read("text"));
    }

    /** An image's texture path, as the pack writes it, or an empty string. */
    public String texture() {
        return UiExpression.text(read("texture"));
    }

    private Object read(String property) {
        if (bound.containsKey("#" + property)) return bound.get("#" + property);
        JsonElement value = control.property(property);
        Object plain = UiControl.plain(value);
        if (plain instanceof String text && text.startsWith("#")) return bound.getOrDefault(text, "");
        if (plain instanceof String text && text.startsWith("(")) {
            return UiDefinitions.value(value, control.variables());
        }
        return plain;
    }

    /** A label's colour as 0xRRGGBB, from the `[r, g, b]` it states between nought and one, or white. */
    public int colour() {
        JsonArray colour = control.array("color");
        if (colour == null || colour.size() < 3) return 0xFFFFFF;
        int red = channel(colour.get(0));
        int green = channel(colour.get(1));
        int blue = channel(colour.get(2));
        return red << 16 | green << 8 | blue;
    }

    private static int channel(JsonElement value) {
        double fraction = value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsDouble() : 1;
        return (int) Math.round(Math.max(0, Math.min(1, fraction)) * 255);
    }

    /** Where a label's line of this width starts, by the label's `text_alignment` inside its own width. */
    public int textLeft(int textWidth) {
        return switch (control.text("text_alignment", "left")) {
            case "center" -> x + (width - textWidth) / 2;
            case "right" -> x + width - textWidth;
            default -> x;
        };
    }

    public boolean shadow() {
        return UiExpression.truthy(UiControl.plain(control.property("shadow")));
    }
}
