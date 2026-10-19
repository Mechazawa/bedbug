package at.ioexception.mcaddon.bedrock.ui;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Every control the resource packs' `ui/` files define, merged the way Bedrock merges them.
 *
 * A file names its `namespace` and defines each control under a key, `name` or `name@parent`. A
 * definition takes everything its parent states and overrides it key by key; `controls`, `bindings`
 * and every other array are replaced whole, and a `modifications` entry edits those arrays in place
 * instead. A pack defining a name a lower pack, or Bedrock itself, also defines lays its keys over
 * that definition the same way, which is how `hud_title_text` with only a `bindings` change still
 * draws the title.
 *
 * Bedrock's own UI files are Mojang's and are not shipped here, so a definition a pack lays over one
 * of Bedrock's controls has no base to lay it over. Three of them are answered by what this mod draws
 * instead: `server_form.long_form_panel`, the content of an action form, is this mod's form screen;
 * `common_dialogs.main_panel_no_buttons` is a panel showing its `$child_control`; and every button in
 * `common` and `common_buttons` is a Java button labelled with its `$button_text`. A control built on
 * any other of Bedrock's is drawn from what the pack wrote and named as missing its base.
 *
 * Files are read in pack order, lowest first, so a higher pack's keys win.
 */
public final class UiDefinitions {
    public static final UiDefinitions EMPTY = new UiDefinitions();

    private static final int MAX_DEPTH = 64;

    /** A name a definition mentions, after an `@` or opening a string, with its namespace if it has one. */
    private static final java.util.regex.Pattern MENTION =
        java.util.regex.Pattern.compile("[@\"]([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_/]+)?)");

    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final Map<String, JsonElement> globals = new LinkedHashMap<>();
    private final Set<String> notes = new LinkedHashSet<>();

    private static final class Definition {
        String file;
        String parent;
        final JsonObject body = new JsonObject();
        final List<JsonObject> modifications = new ArrayList<>();
    }

    private UiDefinitions() {
    }

    public static UiDefinitions read(List<BedrockPack> packs) {
        UiDefinitions read = new UiDefinitions();
        packs.forEach(read::readPack);
        return read;
    }

    private void readPack(BedrockPack pack) {
        for (Path file : pack.list("ui", ".json")) {
            String relative = pack.relative(file).toString().replace('\\', '/');
            try {
                JsonObject json = Json.parseObject(file);
                switch (relative) {
                    case "ui/_ui_defs.json" -> {
                    }
                    case "ui/_global_variables.json" -> json.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith("$"))
                        .forEach(entry -> globals.put(entry.getKey(), entry.getValue()));
                    default -> readFile(relative, json);
                }
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {} in {}.", relative, pack.displayName(), error);
                notes.add(relative + " could not be read: " + error.getMessage());
            }
        }
    }

    private void readFile(String file, JsonObject json) {
        String namespace = Json.text(json, "namespace", "");
        if (namespace.isEmpty()) {
            notes.add(file + " names no namespace, so none of its controls can be referred to");
            return;
        }

        json.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("namespace"))
            .filter(entry -> entry.getValue().isJsonObject())
            .forEach(entry -> {
                String key = entry.getKey();
                int at = key.indexOf('@');
                String name = at < 0 ? key : key.substring(0, at);
                Definition definition = definitions.computeIfAbsent(namespace + "." + name,
                    ignored -> new Definition());
                if (definition.file == null) definition.file = file;
                if (at >= 0) definition.parent = qualify(key.substring(at + 1), namespace);

                JsonObject body = entry.getValue().getAsJsonObject().deepCopy();
                JsonElement modifications = body.remove("modifications");
                if (modifications != null && modifications.isJsonArray()) {
                    modifications.getAsJsonArray().asList().stream().filter(JsonElement::isJsonObject)
                        .forEach(modification -> definition.modifications.add(modification.getAsJsonObject()));
                }
                body.entrySet().forEach(property -> definition.body.add(property.getKey(), property.getValue()));
            });
    }

    private static String qualify(String reference, String namespace) {
        String bare = reference.startsWith("@") ? reference.substring(1) : reference;
        return bare.contains(".") ? bare : namespace + "." + bare;
    }

    public boolean defines(String qualified) {
        return definitions.containsKey(qualified);
    }

    /** Whether a pack states `key` itself under one name, rather than only editing it through `modifications`. */
    public boolean states(String qualified, String key) {
        Definition definition = definitions.get(qualified);
        return definition != null && definition.body.has(key);
    }

    /** What the packs wrote under one name, with no parent merged in, or null where none wrote it. */
    public JsonObject written(String qualified) {
        Definition definition = definitions.get(qualified);
        return definition == null ? null : applied(definition.body.deepCopy(), definition.modifications);
    }

    /** The controls every pack's `modifications` put into a control the packs do not define in full. */
    public List<JsonElement> inserted(String qualified, String arrayName) {
        Definition definition = definitions.get(qualified);
        if (definition == null) return List.of();
        JsonObject edited = applied(new JsonObject(), definition.modifications);
        return edited.has(arrayName) ? edited.getAsJsonArray(arrayName).asList() : List.of();
    }

    /** Every name a pack defines under a namespace, which is how the stand-ins' child paths are found. */
    public List<String> names(String namespace) {
        return definitions.keySet().stream().filter(name -> name.startsWith(namespace + ".")).toList();
    }

    /**
     * Hands each thing the packs asked of the UI and this mod does not do to the consumer, once.
     *
     * Only an action form, a message form and the heads-up display's title, subtitle and action bar
     * are drawn from a pack's JSON UI. Every other screen of Bedrock's is one Java draws with classes
     * of its own, and a pack's change to one is a change to Bedrock's definition of that screen, which
     * is Mojang's and is not shipped, so each file changing one is named with its namespace. Inside
     * what is drawn, a control type, an animation and a font size this layout does not draw are named.
     */
    public void report(Consumer<String> consumer) {
        Set<String> reached = reached();
        Map<String, Set<String>> unreached = new java.util.TreeMap<>();
        definitions.forEach((name, definition) -> {
            String namespace = name.substring(0, name.indexOf('.'));
            if (!reached.contains(name)) {
                unreached.computeIfAbsent(definition.file, ignored -> new java.util.TreeSet<>()).add(namespace);
                return;
            }
            String type = Json.text(definition.body, "type", "");
            if (!type.isEmpty() && !DRAWN.contains(type)) {
                notes.add(name + " is a " + type + ", which the form screen and the heads-up display do not draw");
            }
            if (definition.body.has("anims")) notes.add(name + " anims, which are not run");
            if (definition.body.has("font_size") || definition.body.has("font_scale_factor")) {
                notes.add(name + " font size, drawn at the one size Java's font has");
            }
        });
        if (definitions.containsKey("server_form.custom_form")) {
            notes.add("server_form.custom_form, a modal form's layout, whose inputs are Bedrock's own toggle, "
                + "slider, dropdown and text controls; the modal form is drawn as this mod's own form");
        }
        unreached.forEach((file, namespaces) -> notes.add(file + " changes the Bedrock client's own "
            + String.join(", ", namespaces) + " controls, a screen Java draws with classes of its own; "
            + "the change applies to Bedrock's definition of that screen, which is Mojang's and not shipped"));
        notes.forEach(note -> consumer.accept("ui " + note));
    }

    private static final Set<String> DRAWN = Set.of("panel", "stack_panel", "grid", "image", "label", "button",
        "input_panel", "collection_panel", "factory");

    /**
     * Every definition the form screen or the heads-up display can reach: the `server_form` and `hud`
     * namespaces and whatever their definitions name, followed through every name they mention.
     */
    private Set<String> reached() {
        java.util.Deque<String> pending = new java.util.ArrayDeque<>(definitions.keySet().stream()
            .filter(name -> name.startsWith("server_form.") || name.startsWith("hud.")).toList());
        Set<String> reached = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.pop();
            if (!reached.add(name)) continue;
            Definition definition = definitions.get(name);
            String namespace = name.substring(0, name.indexOf('.'));
            if (definition.parent != null && definitions.containsKey(definition.parent)) {
                pending.push(definition.parent);
            }
            java.util.regex.Matcher mentioned = MENTION.matcher(definition.body + " " + definition.modifications);
            while (mentioned.find()) {
                String reference = mentioned.group(1);
                String qualified = reference.contains(".") ? reference : namespace + "." + reference;
                if (definitions.containsKey(qualified)) pending.push(qualified);
            }
        }
        return reached;
    }

    public void note(String note) {
        notes.add(note);
    }

    /**
     * A control placed as `childName`, built from `reference` with `overrides` laid over it, in the
     * namespace of the control naming it and with the variables that control passes down.
     */
    public UiControl instantiate(String childName, String reference, JsonObject overrides, String namespace,
        Map<String, JsonElement> inherited) {
        Map<String, JsonElement> scope = new HashMap<>(globals);
        scope.putAll(inherited);
        return build(childName, reference == null ? null : qualify(substituted(reference, scope), namespace),
            overrides, namespace, scope, 0);
    }

    private UiControl build(String name, String qualified, JsonObject overrides, String namespace,
        Map<String, JsonElement> inherited, int depth) {
        if (depth > MAX_DEPTH) {
            notes.add(name + " nests deeper than " + MAX_DEPTH + " controls");
            return null;
        }

        List<JsonObject> layers = new ArrayList<>();
        String base = chain(qualified, layers);
        JsonObject merged = new JsonObject();
        layers.forEach(layer -> layer.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue())));
        overrides.entrySet().stream()
            .filter(entry -> !entry.getKey().equals("modifications"))
            .forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
        if (overrides.has("modifications") && overrides.get("modifications").isJsonArray()) {
            applied(merged, overrides.getAsJsonArray("modifications").asList().stream()
                .filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).toList());
        }

        Map<String, JsonElement> scope = scope(merged, inherited);
        JsonObject properties = new JsonObject();
        merged.entrySet().stream()
            .filter(entry -> !entry.getKey().startsWith("$") && !entry.getKey().equals("variables"))
            .forEach(entry -> properties.add(entry.getKey(), substituted(entry.getValue(), scope)));

        if (properties.has("ignored") && UiExpression.truthy(value(properties.get("ignored"), scope))) return null;

        String ownNamespace = qualified == null ? namespace : qualified.substring(0, qualified.indexOf('.'));
        String type = Json.text(properties, "type", "");
        if (type.isEmpty() && base != null) type = standIn(name, base, scope, properties, ownNamespace, depth);
        if (type == null) return null;

        List<UiControl> children = new ArrayList<>();
        JsonElement controls = properties.get("controls");
        if (controls != null && controls.isJsonArray()) {
            for (JsonElement entry : controls.getAsJsonArray()) {
                UiControl child = child(entry, ownNamespace, scope, depth);
                if (child != null) children.add(child);
            }
        }
        if (type.equals("panel") && base != null && base.equals("common_dialogs.main_panel_no_buttons")) {
            JsonElement content = scope.get("$child_control");
            if (content != null && content.isJsonPrimitive()) {
                UiControl child = build("child_control", qualify(content.getAsString(), ownNamespace),
                    new JsonObject(), ownNamespace, scope, depth + 1);
                if (child != null) children.add(child);
            }
        }
        return new UiControl(name, type, properties, Map.copyOf(scope), List.copyOf(children), this, ownNamespace);
    }

    /**
     * What a control resting on one of Bedrock's own controls stands for here, or null where the
     * control draws nothing, and a note naming it when it is none of the ones this mod draws.
     */
    private String standIn(String name, String base, Map<String, JsonElement> scope, JsonObject properties,
        String namespace, int depth) {
        if (base.equals("server_form.long_form_panel")) return UiControl.VANILLA_FORM;
        if (base.equals("common_dialogs.main_panel_no_buttons")) {
            if (!properties.has("size")) properties.add("size", Json.parse("[225, 200]"));
            return "panel";
        }
        if (base.startsWith("common.") && base.endsWith("button") || base.startsWith("common_buttons.")) {
            return UiControl.VANILLA_BUTTON;
        }
        notes.add(name + " builds on " + base + ", one of the Bedrock client's own controls, whose "
            + "definition is Mojang's and is not shipped, so only what the pack wrote is drawn");
        return "panel";
    }

    /**
     * The definitions from the root of `qualified`'s parents down, and the name of the Bedrock control
     * the chain rests on: one no pack defines, or one a pack only lays keys over, with no type and no
     * parent of its own.
     */
    private String chain(String qualified, List<JsonObject> layers) {
        String current = qualified;
        Set<String> seen = new LinkedHashSet<>();
        while (current != null && seen.add(current)) {
            Definition definition = definitions.get(current);
            if (definition == null) return current;
            layers.add(0, applied(definition.body.deepCopy(), definition.modifications));
            if (definition.parent == null && !definition.body.has("type")) return current;
            current = definition.parent;
        }
        return null;
    }

    private UiControl child(JsonElement entry, String namespace, Map<String, JsonElement> scope, int depth) {
        if (!entry.isJsonObject() || entry.getAsJsonObject().size() != 1) return null;
        Map.Entry<String, JsonElement> only = entry.getAsJsonObject().entrySet().iterator().next();
        String key = only.getKey();
        int at = key.indexOf('@');
        String name = at < 0 ? key : key.substring(0, at);
        String reference = at < 0 ? null : qualify(substituted(key.substring(at + 1), scope), namespace);
        JsonObject body = only.getValue().isJsonObject() ? only.getValue().getAsJsonObject() : new JsonObject();
        return build(name, reference, body, namespace, scope, depth + 1);
    }

    /**
     * The variables a control sees: what its parents pass down, its own `$name` over those, its own
     * `$name|default` only where nothing else set the name, and then each `variables` entry whose
     * `requires` holds.
     */
    private static Map<String, JsonElement> scope(JsonObject merged, Map<String, JsonElement> inherited) {
        Map<String, JsonElement> scope = new HashMap<>(inherited);
        merged.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("$") && entry.getKey().endsWith("|default"))
            .forEach(entry -> scope.putIfAbsent(entry.getKey().substring(0, entry.getKey().length() - 8),
                substituted(entry.getValue(), scope)));
        merged.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("$") && !entry.getKey().contains("|"))
            .forEach(entry -> scope.put(entry.getKey(), substituted(entry.getValue(), inherited)));

        JsonElement variables = merged.get("variables");
        List<JsonElement> entries = variables == null ? List.of()
            : variables.isJsonArray() ? variables.getAsJsonArray().asList() : List.of(variables);
        entries.stream().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
            .filter(entry -> !entry.has("requires")
                || UiExpression.truthy(value(entry.get("requires"), scope)))
            .forEach(entry -> entry.entrySet().stream()
                .filter(variable -> variable.getKey().startsWith("$"))
                .forEach(variable -> scope.put(variable.getKey(), substituted(variable.getValue(), scope))));
        return scope;
    }

    /** A property's value read against a scope of variables: a variable, an expression or a literal. */
    static Object value(JsonElement element, Map<String, JsonElement> scope) {
        Object plain = UiControl.plain(element);
        if (!(plain instanceof String text)) return plain;
        if (text.startsWith("(")) {
            try {
                return UiExpression.evaluate(text, name -> UiControl.plain(scope.get(name)));
            } catch (IllegalArgumentException unreadable) {
                return "";
            }
        }
        return text.startsWith("$") ? UiControl.plain(scope.get(text)) : text;
    }

    private static String substituted(String text, Map<String, JsonElement> scope) {
        JsonElement value = text.startsWith("$") ? scope.get(text) : null;
        return value != null && value.isJsonPrimitive() ? value.getAsString() : text;
    }

    private static JsonElement substituted(JsonElement element, Map<String, JsonElement> scope) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String text = element.getAsString();
            JsonElement value = text.startsWith("$") ? scope.get(text) : null;
            return value == null ? element : value;
        }
        if (element.isJsonArray()) {
            JsonArray copy = new JsonArray();
            element.getAsJsonArray().forEach(item -> copy.add(substituted(item, scope)));
            return copy;
        }
        return element;
    }

    /**
     * `modifications` applied to the arrays of `body`, in order: `insert_back`, `insert_front`,
     * `insert_after`, `insert_before`, `move_front`, `move_back`, `move_after`, `move_before`, `swap`,
     * `replace` and `remove`. A control is found by `control_name` and a binding by `where`.
     */
    static JsonObject applied(JsonObject body, List<JsonObject> modifications) {
        for (JsonObject modification : modifications) {
            String arrayName = Json.text(modification, "array_name", "controls");
            JsonArray array = body.has(arrayName) && body.get(arrayName).isJsonArray()
                ? body.getAsJsonArray(arrayName) : new JsonArray();
            List<JsonElement> items = new ArrayList<>(array.asList());
            JsonElement value = modification.get("value");
            List<JsonElement> values = value == null ? List.of()
                : value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
            int found = indexOf(items, modification);

            switch (Json.text(modification, "operation", "")) {
                case "insert_back" -> items.addAll(values);
                case "insert_front" -> items.addAll(0, values);
                case "insert_after" -> {
                    if (found >= 0) items.addAll(found + 1, values);
                }
                case "insert_before" -> {
                    if (found >= 0) items.addAll(found, values);
                }
                case "remove" -> {
                    if (found >= 0) items.remove(found);
                }
                case "replace" -> {
                    if (found >= 0) {
                        items.remove(found);
                        items.addAll(found, values);
                    }
                }
                case "move_front", "move_back" -> {
                    if (found >= 0) {
                        JsonElement moved = items.remove(found);
                        items.add(modification.get("operation").getAsString().equals("move_front")
                            ? 0 : items.size(), moved);
                    }
                }
                case "move_after", "move_before", "swap" -> moved(items, modification, found);
                default -> {
                }
            }
            JsonArray result = new JsonArray();
            items.forEach(result::add);
            body.add(arrayName, result);
        }
        return body;
    }

    /** `move_after`, `move_before` and `swap`, which name a second control by `target_control`. */
    private static void moved(List<JsonElement> items, JsonObject modification, int found) {
        JsonObject target = new JsonObject();
        target.addProperty("control_name", Json.text(modification, "target_control", ""));
        int other = indexOf(items, target);
        if (found < 0 || other < 0 || found == other) return;
        String operation = modification.get("operation").getAsString();
        if (operation.equals("swap")) {
            JsonElement first = items.get(found);
            items.set(found, items.get(other));
            items.set(other, first);
            return;
        }
        JsonElement moved = items.remove(found);
        int anchor = indexOf(items, target);
        items.add(operation.equals("move_after") ? anchor + 1 : anchor, moved);
    }

    private static int indexOf(List<JsonElement> items, JsonObject modification) {
        String controlName = Json.text(modification, "control_name", "");
        JsonElement where = modification.get("where");
        for (int index = 0; index < items.size(); index++) {
            JsonElement item = items.get(index);
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            if (!controlName.isEmpty() && object.size() == 1) {
                String key = object.keySet().iterator().next();
                if (key.equals(controlName) || key.startsWith(controlName + "@")) return index;
            }
            if (where != null && where.isJsonObject() && where.getAsJsonObject().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(object.get(entry.getKey())))) {
                return index;
            }
        }
        return -1;
    }
}
