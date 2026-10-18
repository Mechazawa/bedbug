package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * An item a behaviour pack declares.
 *
 * `icon` names a key in the resource pack's `item_texture.json` rather than a path, so the two halves
 * of an add-on have to be read together before an item can be drawn.
 */
public record ItemDefinition(String identifier, String iconKey, String displayNameKey,
    int maxStackSize, Map<String, JsonObject> components) {

    public static List<ItemDefinition> readAll(BedrockPack pack) {
        List<ItemDefinition> items = new ArrayList<>();
        for (Path file : pack.list("items", ".json")) {
            try {
                ItemDefinition item = read(Json.parseObject(file));
                if (item != null) items.add(item);
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
        return items;
    }

    private static ItemDefinition read(JsonObject root) {
        JsonObject body = root.has("minecraft:item") && root.get("minecraft:item").isJsonObject()
            ? root.getAsJsonObject("minecraft:item") : null;
        if (body == null) return null;

        JsonObject description = body.getAsJsonObject("description");
        String identifier = description == null ? null : Json.text(description, "identifier", null);
        if (identifier == null) return null;

        Map<String, JsonObject> components = new LinkedHashMap<>();
        JsonObject declared = body.getAsJsonObject("components");
        if (declared != null) {
            for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                components.put(entry.getKey(), component(entry.getValue()));
            }
        }

        return new ItemDefinition(identifier, icon(components.get("minecraft:icon")),
            displayName(components.get("minecraft:display_name")),
            stackSize(declared), components);
    }

    /**
     * A component written as a bare value or a list keeps it under `value`, where the object form
     * puts it.
     */
    private static JsonObject component(JsonElement declared) {
        if (declared.isJsonObject()) return declared.getAsJsonObject();

        JsonObject wrapped = new JsonObject();
        if (declared.isJsonPrimitive() || declared.isJsonArray()) wrapped.add("value", declared);
        return wrapped;
    }

    /** The number `component` holds under `field`, falling back to the bare value it was written as. */
    public float number(String component, String field, float fallback) {
        JsonObject declared = components.get(component);
        if (declared == null) return fallback;

        JsonElement value = declared.get(field);
        if (value == null) value = declared.get("value");
        return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
    }

    /** The text `component` holds under `field`, falling back to the bare value it was written as. */
    public String text(String component, String field) {
        JsonObject declared = components.get(component);
        if (declared == null) return null;

        String value = Json.text(declared, field, null);
        return value != null ? value : Json.text(declared, "value", null);
    }

    /** Whether a component is declared without the `false` Bedrock switches one off with. */
    public boolean declaredTrue(String component) {
        JsonObject declared = components.get(component);
        if (declared == null) return false;

        JsonElement value = declared.get("value");
        return value == null || !value.isJsonPrimitive() || value.getAsBoolean();
    }

    /** The damage this takes before it breaks, under either name a pack's format version has for it. */
    public int durability() {
        int declared = (int) number("minecraft:durability", "max_durability", 0f);
        return declared > 0 ? declared : (int) number("minecraft:max_damage", "value", 0f);
    }

    /**
     * What the player sees while holding right click.
     *
     * A food component with no animation beside it means eating, which is what food was added for.
     */
    public String useAnimation() {
        String named = text("minecraft:use_animation", "value");
        if (named != null) return named;

        JsonObject food = components.get("minecraft:food");
        if (food == null) return "none";

        return Json.text(food, "use_animation", "eat");
    }

    /**
     * Whether the pack means the player swallows this.
     *
     * `minecraft:food` beside a `use_animation` of `none` is how a pack makes an item hold-to-use
     * without being eaten: in Bedrock the animation drives the interaction and `none` draws nothing.
     * Java has one component for both, so a hat declared that way would feed the player.
     */
    public boolean edible() {
        if (!components.containsKey("minecraft:food")) return false;

        String animation = useAnimation();
        return animation.equals("eat") || animation.equals("drink");
    }

    /**
     * How much saturation eating this gives.
     *
     * The field takes a number or one of Bedrock's named tiers. 0.6 is what the engine uses for a
     * food component that leaves it out.
     */
    public float saturation() {
        JsonObject food = components.get("minecraft:food");
        JsonElement declared = food == null ? null : food.get("saturation_modifier");
        if (declared == null || !declared.isJsonPrimitive()) return 0.6f;

        JsonPrimitive value = declared.getAsJsonPrimitive();
        if (value.isNumber()) return value.getAsFloat();
        return switch (value.getAsString()) {
            case "poor" -> 0.1f;
            case "low" -> 0.3f;
            case "good" -> 0.8f;
            case "max" -> 1.0f;
            case "supernatural" -> 1.2f;
            default -> 0.6f;
        };
    }

    /**
     * How long one use lasts, in seconds.
     *
     * `use_modifiers` counts it in seconds and `minecraft:use_duration` counts it in ticks. 1.6
     * seconds is Bedrock's default for a food item and Java's as well.
     */
    public float useSeconds() {
        float seconds = number("minecraft:use_modifiers", "use_duration", 0f);
        if (seconds > 0f) return seconds;

        float ticks = number("minecraft:use_duration", "value", 0f);
        return ticks > 0f ? ticks / 20f : 1.6f;
    }

    /** Armour points, under whichever component the pack's format version carries them. */
    public float protection() {
        float worn = number("minecraft:wearable", "protection", 0f);
        return worn > 0f ? worn : number("minecraft:armor", "protection", 0f);
    }

    /**
     * The colour a dyeable item starts out, or -1 where the pack declared no dyeing.
     *
     * `default_color` is written the way CSS writes one, and a pack that leaves it out means the
     * leather brown Java falls back to as well.
     */
    public int dyeColour() {
        JsonObject dyeable = components.get("minecraft:dyeable");
        if (dyeable == null) return -1;

        String colour = Json.text(dyeable, "default_color", null);
        if (colour == null) return LEATHER_BROWN;

        try {
            return Integer.parseInt(colour.startsWith("#") ? colour.substring(1) : colour, 16)
                & 0xFFFFFF;
        } catch (NumberFormatException malformed) {
            return LEATHER_BROWN;
        }
    }

    /** What an undyed leather item is worth in both editions. */
    private static final int LEATHER_BROWN = 0xA06540;

    /** One block `minecraft:digger` names, and how fast this breaks it. */
    public record DestroySpeed(String block, float speed) {
    }

    /**
     * The blocks `minecraft:digger` lists a speed for.
     *
     * An entry whose `block` is an object holds a Molang tag query, which picks blocks by a
     * condition the game evaluates per block, so there is no fixed set of blocks to answer with.
     */
    public List<DestroySpeed> destroySpeeds() {
        JsonObject digger = components.get("minecraft:digger");
        if (digger == null) return List.of();

        return Json.list(digger, "destroy_speeds").stream()
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .filter(entry -> entry.has("block") && entry.get("block").isJsonPrimitive())
            .map(entry -> new DestroySpeed(entry.get("block").getAsString(),
                entry.has("speed") ? entry.get("speed").getAsFloat() : 1f))
            .toList();
    }

    /**
     * What `minecraft:shooter` accepts as ammunition, in the order the pack prefers it.
     *
     * Each entry names an item and says where it may be taken from. Where it comes from is Java's
     * own rule, so only the name is read.
     */
    public List<String> ammunition() {
        JsonObject shooter = components.get("minecraft:shooter");
        if (shooter == null) return List.of();

        return Json.list(shooter, "ammunition").stream()
            .filter(JsonElement::isJsonObject)
            .map(entry -> Json.text(entry.getAsJsonObject(), "item", null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    /**
     * The components a pack's own script registered that this item asks for.
     *
     * Each component here is held the way `component` holds it, so the older list comes back out
     * from under `value` before the two spellings are read together.
     */
    public CustomComponents scriptedComponents() {
        JsonObject declared = new JsonObject();
        components.forEach((name, body) -> declared.add(name,
            name.equals(CustomComponents.LISTED) && body.has("value") ? body.get("value") : body));
        return CustomComponents.of(declared);
    }

    /** How far along the view vector a weapon component hits, and how wide its raycast is. */
    public record Reach(float min, float max, float creativeMin, float creativeMax, float margin) {
    }

    /**
     * The reach `component` declares, or null where the item carries no such component.
     *
     * `creative_reach` falls back to `reach`, and three blocks is what Bedrock reaches when the
     * component names neither.
     */
    public Reach reach(String component) {
        JsonObject declared = components.get(component);
        if (declared == null) return null;

        float min = end(declared, "reach", "min", 0f);
        float max = end(declared, "reach", "max", 3f);
        return new Reach(min, max, end(declared, "creative_reach", "min", min),
            end(declared, "creative_reach", "max", max), number(component, "hitbox_margin", 0f));
    }

    /** One end of a range a component writes as `{"min": a, "max": b}`. */
    private static float end(JsonObject declared, String field, String side, float fallback) {
        JsonElement range = declared.get(field);
        if (range == null || !range.isJsonObject()) return fallback;

        JsonElement value = range.getAsJsonObject().get(side);
        return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
    }

    /** What has to hold before one effect of a kinetic weapon applies. */
    public record KineticCondition(int durationTicks, float speed, float relativeSpeed) {
    }

    /**
     * The named conditions of `minecraft:kinetic_weapon`, or null where the pack declares none.
     *
     * An effect whose conditions are absent never applies, which is why the absence is answered
     * rather than turned into a condition that always holds.
     */
    public KineticCondition kineticCondition(String field) {
        JsonObject kinetic = components.get("minecraft:kinetic_weapon");
        JsonElement declared = kinetic == null ? null : kinetic.get(field);
        if (declared == null || !declared.isJsonObject()) return null;

        JsonObject condition = declared.getAsJsonObject();
        return new KineticCondition((int) field(condition, "max_duration", -1f),
            field(condition, "min_speed", 0f), field(condition, "min_relative_speed", 0f));
    }

    private static float field(JsonObject json, String name, float fallback) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
    }

    /** The causes `minecraft:damage_absorption` takes for whoever wears this. */
    public Set<DamageCause> absorbedCauses() {
        JsonObject absorption = components.get("minecraft:damage_absorption");
        if (absorption == null) return Set.of();

        return DamageCause.allNamed(Json.list(absorption, "absorbable_causes"));
    }

    /** The items `minecraft:repairable` accepts for repairing this one. */
    public List<String> repairItems() {
        JsonObject repairable = components.get("minecraft:repairable");
        if (repairable == null) return List.of();

        return Json.list(repairable, "repair_items").stream()
            .flatMap(entry -> entry.isJsonObject()
                ? Json.list(entry.getAsJsonObject(), "items").stream()
                : Stream.of(entry))
            .filter(JsonElement::isJsonPrimitive)
            .map(JsonElement::getAsString)
            .toList();
    }

    /**
     * The texture key `minecraft:icon` names, in any of the three shapes Bedrock has used for it.
     *
     * The oldest is a bare string, then came an object with `texture`, and from format 1.21.20 a map
     * of states to textures under `textures`. A pack written against any of them is still shipped.
     */
    private static String icon(JsonObject component) {
        if (component == null) return null;

        String texture = Json.text(component, "texture", null);
        if (texture == null) texture = Json.text(component, "value", null);
        if (texture != null) return texture;

        JsonObject textures = component.getAsJsonObject("textures");
        if (textures == null) return null;

        String byState = Json.text(textures, "default", null);
        if (byState != null) return byState;
        return textures.entrySet().stream()
            .filter(entry -> entry.getValue().isJsonPrimitive())
            .map(entry -> entry.getValue().getAsString())
            .findFirst().orElse(null);
    }

    private static String displayName(JsonObject component) {
        return component == null ? null : Json.text(component, "value", null);
    }

    /** A Bedrock component is written either as the bare value or as an object wrapping it. */
    private static int stackSize(JsonObject components) {
        JsonElement value = components == null ? null : components.get("minecraft:max_stack_size");
        if (value == null) return 64;
        if (value.isJsonPrimitive()) return value.getAsInt();

        JsonElement inner = value.isJsonObject() ? value.getAsJsonObject().get("value") : null;
        return inner != null && inner.isJsonPrimitive() ? inner.getAsInt() : 64;
    }
}
