package at.ioexception.mcaddon.bedrock.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;

/**
 * One control of a pack's JSON UI with its parents merged in and its variables settled.
 *
 * `properties` holds what the control states, `$` variables taken out and every value written as a
 * bare `$name` replaced by that variable. An expression keeps its variables, because it is evaluated
 * against the data a screen holds when it is drawn, and `variables` is what it reads them from.
 *
 * `type` is the control's own, or one of the two stand-ins for Bedrock's own controls: `VANILLA_FORM`
 * where Bedrock draws its own action form, which this mod draws as its own form screen, and
 * `VANILLA_BUTTON` where a pack builds on one of Bedrock's buttons, which is drawn as a Java button.
 */
public record UiControl(String name, String type, JsonObject properties, Map<String, JsonElement> variables,
    List<UiControl> children, UiDefinitions definitions, String namespace) {

    public static final String VANILLA_FORM = "vanilla_form";
    public static final String VANILLA_BUTTON = "vanilla_button";

    public JsonElement property(String key) {
        return properties.get(key);
    }

    public String text(String key, String fallback) {
        JsonElement value = properties.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    /** The bindings in the order the control lists them, which is the order Bedrock applies them in. */
    public List<JsonObject> bindings() {
        JsonElement bindings = properties.get("bindings");
        if (bindings == null || !bindings.isJsonArray()) return List.of();
        return bindings.getAsJsonArray().asList().stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .toList();
    }

    /** A variable as an expression reads it: a string, a number, a boolean, or nothing. */
    public Object variable(String name) {
        return plain(variables.get(name));
    }

    public static Object plain(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return null;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsDouble();
        return primitive.getAsString();
    }

    /**
     * A control another names by reference from this one, a grid's item or a factory's product,
     * resolved in this control's namespace and with its variables.
     */
    public UiControl instantiate(String childName, String reference, JsonObject overrides) {
        return definitions.instantiate(childName, reference, overrides == null ? new JsonObject() : overrides,
            namespace, variables);
    }

    public JsonArray array(String key) {
        JsonElement value = properties.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }
}
