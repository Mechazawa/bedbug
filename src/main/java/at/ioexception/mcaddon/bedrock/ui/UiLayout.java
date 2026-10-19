package at.ioexception.mcaddon.bedrock.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lays a control tree out the way Bedrock's JSON UI does, as far as a pack's forms and heads-up
 * display need it.
 *
 * A control's `size` and `offset` take pixels, a percentage of the parent (`%`), of the children's
 * extent (`%c`), of the largest child (`%cm`), of the control's own other side (`%x`, `%y`), `fill`
 * for what a stack panel has left and `default`, which is the text's own size for a label and the
 * parent's for anything else, with terms joined by `+` and `-`. `anchor_from` is the point of the
 * parent and `anchor_to` the point of the control placed on it, both `center` unless stated. A
 * `stack_panel` puts its visible children one after another along its `orientation`, a `grid` builds
 * its `grid_item_template` once per cell, and a `factory` over a `collection_name` builds one of its
 * `control_ids` per item of that collection. A `button` draws its `default_control`; the controls it
 * names for the pointer over it, for a press and for being locked are not drawn.
 *
 * Bindings are applied in order: a binding names a value the screen holds, or of one item of a
 * collection, and a `view` binding evaluates an expression over the properties another control's
 * bindings set, found among the control's ancestors and the siblings placed before it. A control is
 * left out when `visible`, or a `#visible` its bindings set, is false.
 */
public final class UiLayout {
    private static final Pattern TERM = Pattern.compile("([+-]?)\\s*([0-9]*\\.?[0-9]+)\\s*(px|%cm|%sm|%c|%x|%y|%)?");

    private final UiData data;

    private record Frame(UiPlaced placed, Frame parent, List<UiPlaced> siblings) {
    }

    public UiLayout(UiData data) {
        this.data = data;
    }

    /** Lays `control` out inside a rectangle of the screen, or returns null where it is not visible. */
    public UiPlaced place(UiControl control, int x, int y, int width, int height) {
        return place(control, new int[] {x, y, width, height}, null, new ArrayList<>(), 0, -1, null);
    }

    private UiPlaced place(UiControl control, int[] parent, Frame above, List<UiPlaced> siblings, int parentLayer,
        int inheritedIndex, int[] slot) {
        int index = control.properties().has("collection_index")
            ? (int) number(control.property("collection_index")) : inheritedIndex;
        Map<String, Object> bound = bind(control, index, above, siblings);
        if (!visible(control, bound)) return null;

        int[] area = slot == null ? parent : slot;
        String label = control.type().equals("label")
            ? new UiPlaced(control, 0, 0, 0, 0, 0, index, bound, List.of()).text() : null;
        int width = axis(control.property("size"), 0, area[2], label, true, 0);
        int height = axis(control.property("size"), 1, area[3], label, false, width);
        boolean fitsChildren = dependsOnChildren(control.property("size"));

        int layer = parentLayer + (int) number(control.property("layer"));
        List<UiPlaced> children = new ArrayList<>();
        int[] own = position(control, area, width, height);
        UiPlaced provisional = new UiPlaced(control, own[0], own[1], width, height, layer, index, bound, children);
        Frame frame = new Frame(provisional, above, siblings);
        layChildren(control, frame, own, width, height, layer, index, children);

        if (fitsChildren) {
            int[] extent = extent(children, own, control);
            width = axisWithChildren(control.property("size"), 0, area[2], extent[0], extent[2], width);
            height = axisWithChildren(control.property("size"), 1, area[3], extent[1], extent[3], height);
            int[] moved = position(control, area, width, height);
            int shiftX = moved[0] - own[0];
            int shiftY = moved[1] - own[1];
            own = moved;
            children.replaceAll(child -> shifted(child, shiftX, shiftY));
        }
        return new UiPlaced(control, own[0], own[1], width, height, layer, index, bound, List.copyOf(children));
    }

    private void layChildren(UiControl control, Frame frame, int[] own, int width, int height, int layer, int index,
        List<UiPlaced> children) {
        List<String> otherStates = List.of(control.text("hover_control", ""), control.text("pressed_control", ""),
            control.text("locked_control", ""));
        List<Built> built = new ArrayList<>();
        control.children().stream()
            .filter(child -> !control.type().equals("button") || !otherStates.contains(child.name()))
            .forEach(child -> built.add(new Built(child, index)));
        built.addAll(made(control, index));

        int[] box = {own[0], own[1], width, height};
        switch (control.type()) {
            case "stack_panel" -> stack(control, frame, box, layer, built, children);
            case "grid" -> grid(control, frame, box, layer, children);
            default -> built.forEach(item -> add(children,
                place(item.control(), box, frame, children, layer, item.index(), null)));
        }
    }

    private record Built(UiControl control, int index) {
    }

    /** The controls a `factory` makes: one per item of its collection, or what the screen made for it. */
    private List<Built> made(UiControl control, int index) {
        JsonElement factory = control.property("factory");
        if (factory == null || !factory.isJsonObject()) return List.of();
        JsonObject described = factory.getAsJsonObject();
        JsonObject ids = described.has("control_ids") && described.get("control_ids").isJsonObject()
            ? described.getAsJsonObject("control_ids") : new JsonObject();
        String collection = control.text("collection_name", "");

        if (collection.isEmpty()) {
            UiControl product = data.made(described.has("name") ? described.get("name").getAsString() : "", control);
            return product == null ? List.of() : List.of(new Built(product, index));
        }

        List<Built> items = new ArrayList<>();
        for (int item = 0; item < data.length(collection); item++) {
            String kind = data.kind(collection, item);
            JsonElement id = ids.get(kind);
            if (id == null || !id.isJsonPrimitive()) continue;
            String written = id.getAsString();
            int at = written.indexOf('@');
            String reference = at >= 0 ? written.substring(at + 1) : written;
            UiControl product = control.instantiate(at > 0 ? written.substring(0, at)
                : reference.substring(reference.indexOf('.') + 1), reference, null);
            if (product != null) items.add(new Built(product, item));
        }
        return items;
    }

    private void stack(UiControl control, Frame frame, int[] box, int layer, List<Built> built,
        List<UiPlaced> children) {
        boolean horizontal = control.text("orientation", "vertical").equals("horizontal");
        int cursor = 0;
        for (Built item : built) {
            int[] slot = horizontal
                ? new int[] {box[0] + cursor, box[1], Math.max(0, box[2] - cursor), box[3]}
                : new int[] {box[0], box[1] + cursor, box[2], Math.max(0, box[3] - cursor)};
            UiPlaced placed = place(item.control(), box, frame, children, layer, item.index(), slot);
            if (placed == null) continue;
            placed = horizontal ? shifted(placed, slot[0] - placed.x(), 0) : shifted(placed, 0, slot[1] - placed.y());
            children.add(placed);
            cursor += horizontal ? placed.width() : placed.height();
        }
    }

    private void grid(UiControl control, Frame frame, int[] box, int layer, List<UiPlaced> children) {
        JsonArray dimensions = control.array("grid_dimensions");
        int columns = dimensions == null || dimensions.size() < 2 ? 1 : Math.max(1, dimensions.get(0).getAsInt());
        int rows = dimensions == null || dimensions.size() < 2 ? 1 : Math.max(0, dimensions.get(1).getAsInt());
        String collection = control.text("collection_name", "");
        String rescaling = control.text("grid_rescaling_type", "none");
        if (!collection.isEmpty() && rescaling.equals("vertical") || dimensions == null) {
            rows = Math.max(rows, (data.length(collection) + columns - 1) / columns);
        }
        int count = columns * rows;
        if (control.properties().has("maximum_grid_items")) {
            count = Math.min(count, (int) number(control.property("maximum_grid_items")));
        }

        String template = control.text("grid_item_template", "");
        for (int item = 0; item < count && !template.isEmpty(); item++) {
            UiControl cell = control.instantiate("grid_item", template, null);
            if (cell == null) continue;
            int cellWidth = axis(cell.property("size"), 0, box[2], null, true, 0);
            int cellHeight = axis(cell.property("size"), 1, box[3], null, false, cellWidth);
            int[] slot = {box[0] + item % columns * cellWidth, box[1] + item / columns * cellHeight,
                cellWidth, cellHeight};
            UiControl anchored = withTopLeft(cell);
            add(children, place(anchored, slot, frame, children, layer, item, slot));
        }
    }

    /** A grid cell is placed by its corner, whatever anchors the template states. */
    private static UiControl withTopLeft(UiControl cell) {
        JsonObject properties = cell.properties().deepCopy();
        properties.addProperty("anchor_from", "top_left");
        properties.addProperty("anchor_to", "top_left");
        properties.remove("offset");
        return new UiControl(cell.name(), cell.type(), properties, cell.variables(), cell.children(),
            cell.definitions(), cell.namespace());
    }

    private static void add(List<UiPlaced> children, UiPlaced placed) {
        if (placed != null) children.add(placed);
    }

    private Map<String, Object> bind(UiControl control, int index, Frame above, List<UiPlaced> siblings) {
        Map<String, Object> bound = new HashMap<>();
        JsonElement bag = control.property("property_bag");
        if (bag != null && bag.isJsonObject()) {
            bag.getAsJsonObject().entrySet().forEach(entry -> bound.put(entry.getKey(),
                UiControl.plain(entry.getValue())));
        }

        for (JsonObject binding : control.bindings()) {
            if (UiExpression.truthy(UiControl.plain(binding.get("ignored")))) continue;
            String type = text(binding, "binding_type", "global");
            if (type.equals("view")) {
                String source = text(binding, "source_control_name", "");
                Map<String, Object> from = source.isEmpty() || source.equals(control.name())
                    ? bound : source(source, above, siblings, bound);
                String target = text(binding, "target_property_name", "");
                Object value = evaluate(text(binding, "source_property_name", ""), from, control);
                if (!target.isEmpty()) bound.put(target, value);
                continue;
            }
            if (type.equals("collection_details") || type.equals("none")) continue;

            String name = text(binding, "binding_name", "");
            if (name.isEmpty()) continue;
            Object value;
            if (name.startsWith("(")) {
                value = evaluate(name, bound, control);
            } else if (type.equals("collection")) {
                value = data.item(text(binding, "binding_collection_name", ""), index, name);
            } else {
                value = data.global(name);
            }
            bound.put(text(binding, "binding_name_override", name), value == null ? "" : value);
        }
        return bound;
    }

    private Object evaluate(String expression, Map<String, Object> from, UiControl control) {
        if (expression.isEmpty()) return "";
        try {
            return UiExpression.evaluate(expression, name -> {
                if (name.startsWith("$")) return control.variable(name);
                if (from.containsKey(name)) return from.get(name);
                return data.global(name);
            });
        } catch (IllegalArgumentException unreadable) {
            control.definitions().note(control.name() + " binds an expression that does not parse: " + expression);
            return "";
        }
    }

    /** The properties a named control's bindings set, searched among the ancestors and earlier siblings. */
    private static Map<String, Object> source(String name, Frame above, List<UiPlaced> siblings,
        Map<String, Object> fallback) {
        for (UiPlaced sibling : siblings) {
            if (sibling.control().name().equals(name)) return sibling.bound();
        }
        for (Frame frame = above; frame != null; frame = frame.parent()) {
            if (frame.placed().control().name().equals(name)) return frame.placed().bound();
            for (UiPlaced sibling : frame.siblings()) {
                if (sibling.control().name().equals(name)) return sibling.bound();
            }
        }
        return fallback;
    }

    private static boolean visible(UiControl control, Map<String, Object> bound) {
        JsonElement stated = control.property("visible");
        if (stated != null && !UiExpression.truthy(UiDefinitions.value(stated, control.variables()))) return false;
        return !bound.containsKey("#visible") || UiExpression.truthy(bound.get("#visible"));
    }

    /** One side of a control's size; `label` is a label's text, which its default size fits, or null. */
    private int axis(JsonElement size, int which, int parent, String label, boolean horizontal, int otherSide) {
        JsonElement value = size != null && size.isJsonArray() && size.getAsJsonArray().size() > which
            ? size.getAsJsonArray().get(which) : null;
        String written = value == null ? "default" : value.isJsonPrimitive() ? value.getAsString() : "default";
        if (written.equals("default")) {
            if (label == null) return parent;
            String[] lines = label.split("\n", -1);
            return horizontal ? java.util.Arrays.stream(lines).mapToInt(data::textWidth).max().orElse(0)
                : lines.length * data.lineHeight();
        }
        if (written.equals("fill")) return parent;
        return terms(written, parent, 0, 0, otherSide);
    }

    private static int axisWithChildren(JsonElement size, int which, int parent, int extent, int largest,
        int current) {
        JsonElement value = size != null && size.isJsonArray() && size.getAsJsonArray().size() > which
            ? size.getAsJsonArray().get(which) : null;
        if (value == null || !value.isJsonPrimitive() || !value.getAsString().contains("%c")) return current;
        return terms(value.getAsString(), parent, extent, largest, 0);
    }

    private static boolean dependsOnChildren(JsonElement size) {
        return size != null && size.toString().contains("%c");
    }

    private static int terms(String written, int parent, int children, int largest, int otherSide) {
        Matcher matcher = TERM.matcher(written.toLowerCase(Locale.ROOT));
        double total = 0;
        while (matcher.find()) {
            double amount = Double.parseDouble(matcher.group(2)) * (matcher.group(1).equals("-") ? -1 : 1);
            String unit = matcher.group(3) == null ? "px" : matcher.group(3);
            total += switch (unit) {
                case "%" -> amount / 100 * parent;
                case "%c" -> amount / 100 * children;
                case "%cm" -> amount / 100 * largest;
                case "%x", "%y" -> amount / 100 * otherSide;
                default -> amount;
            };
        }
        return (int) Math.round(total);
    }

    /** The children's extent along each axis from the control's corner, then the largest child's size. */
    private static int[] extent(List<UiPlaced> children, int[] own, UiControl control) {
        boolean stacked = control.type().equals("stack_panel");
        boolean horizontal = control.text("orientation", "vertical").equals("horizontal");
        int width = 0;
        int height = 0;
        int widest = 0;
        int tallest = 0;
        for (UiPlaced child : children) {
            widest = Math.max(widest, child.width());
            tallest = Math.max(tallest, child.height());
            width = stacked && horizontal ? width + child.width() : Math.max(width, child.x() + child.width() - own[0]);
            height = stacked && !horizontal ? height + child.height()
                : Math.max(height, child.y() + child.height() - own[1]);
        }
        return new int[] {width, height, widest, tallest};
    }

    private int[] position(UiControl control, int[] area, int width, int height) {
        double[] from = anchor(control.text("anchor_from", "center"));
        double[] to = anchor(control.text("anchor_to", "center"));
        JsonElement offset = control.property("offset");
        int offsetX = offset(offset, 0, area[2], width, height);
        int offsetY = offset(offset, 1, area[3], width, height);
        return new int[] {
            (int) Math.round(area[0] + from[0] * area[2] - to[0] * width) + offsetX,
            (int) Math.round(area[1] + from[1] * area[3] - to[1] * height) + offsetY};
    }

    private static int offset(JsonElement offset, int which, int parent, int width, int height) {
        if (offset == null || !offset.isJsonArray() || offset.getAsJsonArray().size() <= which) return 0;
        JsonElement value = offset.getAsJsonArray().get(which);
        if (!value.isJsonPrimitive()) return 0;
        String written = value.getAsString().toLowerCase(Locale.ROOT);
        int own = written.contains("%y") ? height : width;
        return terms(written, parent, 0, 0, own);
    }

    private static double[] anchor(String name) {
        return switch (name) {
            case "top_left" -> new double[] {0, 0};
            case "top_middle" -> new double[] {0.5, 0};
            case "top_right" -> new double[] {1, 0};
            case "left_middle" -> new double[] {0, 0.5};
            case "right_middle" -> new double[] {1, 0.5};
            case "bottom_left" -> new double[] {0, 1};
            case "bottom_middle" -> new double[] {0.5, 1};
            case "bottom_right" -> new double[] {1, 1};
            default -> new double[] {0.5, 0.5};
        };
    }

    private static UiPlaced shifted(UiPlaced placed, int dx, int dy) {
        if (dx == 0 && dy == 0) return placed;
        return new UiPlaced(placed.control(), placed.x() + dx, placed.y() + dy, placed.width(), placed.height(),
            placed.layer(), placed.index(), placed.bound(),
            placed.children().stream().map(child -> shifted(child, dx, dy)).toList());
    }

    private static double number(JsonElement value) {
        Object plain = UiControl.plain(value);
        if (plain instanceof Double number) return number;
        try {
            return plain == null ? 0 : Double.parseDouble(plain.toString());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static String text(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
