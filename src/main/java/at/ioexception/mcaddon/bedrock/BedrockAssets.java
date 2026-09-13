package at.ioexception.mcaddon.bedrock;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.animation.AnimationController;
import at.ioexception.mcaddon.bedrock.animation.AnimationControllerParser;
import at.ioexception.mcaddon.bedrock.animation.AnimationParser;
import at.ioexception.mcaddon.bedrock.animation.BedrockAnimation;
import at.ioexception.mcaddon.bedrock.entity.ClientEntityDefinition;
import at.ioexception.mcaddon.bedrock.entity.ClientEntityParser;
import at.ioexception.mcaddon.bedrock.model.BedrockGeometry;
import at.ioexception.mcaddon.bedrock.model.GeometryParser;
import at.ioexception.mcaddon.bedrock.model.MaterialDefinition;
import at.ioexception.mcaddon.bedrock.model.RenderController;
import at.ioexception.mcaddon.bedrock.model.RenderControllerParser;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.SemVer;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Everything one resource pack declares about how its entities look.
 *
 * Held per pack rather than in one global table: two add-ons can name a geometry the same thing, and
 * the one that loaded second must not silently replace the first.
 */
public final class BedrockAssets {
    private final Map<String, BedrockGeometry> geometries = new LinkedHashMap<>();
    private final Map<String, BedrockAnimation> animations = new LinkedHashMap<>();
    private final Map<String, AnimationController> animationControllers = new LinkedHashMap<>();
    private final Map<String, RenderController> renderControllers = new LinkedHashMap<>();
    private final Map<String, ClientEntityDefinition> entities = new LinkedHashMap<>();
    private final Map<String, ClientEntityDefinition> attachables = new LinkedHashMap<>();
    private final Map<String, MaterialDefinition> materials = new LinkedHashMap<>();

    /** The engine the pack says it was built for, which picks between definitions of one entity. */
    private final SemVer engineVersion;

    private BedrockAssets(SemVer engineVersion) {
        this.engineVersion = engineVersion == null ? SemVer.ZERO : engineVersion;
    }

    public static BedrockAssets read(BedrockPack pack) {
        BedrockAssets assets = new BedrockAssets(pack.manifest().minEngineVersion());

        assets.materials.putAll(MaterialDefinition.readAll(pack));
        Map<String, JsonObject> legacy = new LinkedHashMap<>();
        assets.each(pack, "models", root -> assets.readGeometries(root, legacy));
        assets.readLegacyGeometries(legacy);
        assets.each(pack, "animations", root ->
            assets.readSection(root, "animations", AnimationParser::readAnimation, assets.animations));
        assets.each(pack, "animation_controllers", root ->
            assets.readSection(root, "animation_controllers",
                AnimationControllerParser::readController, assets.animationControllers));
        assets.each(pack, "render_controllers", root ->
            assets.readSection(root, "render_controllers",
                RenderControllerParser::read, assets.renderControllers));
        assets.each(pack, "entity", root ->
            assets.readDescribed(root, "minecraft:client_entity", assets.entities));
        assets.each(pack, "attachables", root ->
            assets.readDescribed(root, "minecraft:attachable", assets.attachables));

        McAddon.LOGGER.info("{}: {} geometries, {} animations, {} animation controllers, "
                + "{} render controllers, {} entities, {} attachables.",
            pack.displayName(), assets.geometries.size(), assets.animations.size(),
            assets.animationControllers.size(), assets.renderControllers.size(),
            assets.entities.size(), assets.attachables.size());
        return assets;
    }

    public BedrockGeometry geometry(String identifier) {
        return geometries.get(identifier);
    }

    public BedrockAnimation animation(String name) {
        return animations.get(name);
    }

    public AnimationController animationController(String name) {
        return animationControllers.get(name);
    }

    /** Null when a pack names a material the game already has, which carries its own behaviour. */
    public MaterialDefinition material(String name) {
        return materials.get(name);
    }

    public RenderController renderController(String name) {
        return renderControllers.get(name);
    }

    public ClientEntityDefinition entity(String identifier) {
        return entities.get(identifier);
    }

    public Map<String, ClientEntityDefinition> entities() {
        return entities;
    }

    public Map<String, ClientEntityDefinition> attachables() {
        return attachables;
    }

    private void each(BedrockPack pack, String directory, Consumer<JsonObject> reader) {
        for (Path file : pack.list(directory, ".json")) {
            try {
                reader.accept(Json.parseObject(file));
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }
    }

    /**
     * Geometry comes in two shapes.
     *
     * From format 1.12 a file holds a `minecraft:geometry` array of objects that name themselves in
     * their description. Before that each geometry was a top level key, `geometry.name`, with the
     * bones directly under it. Packs still ship both, sometimes in the same add-on.
     *
     * A 1.8 geometry may extend one held in another file, so those are gathered here and folded
     * together once every file has been read.
     */
    private void readGeometries(JsonObject root, Map<String, JsonObject> legacy) {
        if (root.has("minecraft:geometry")) {
            for (JsonElement element : root.getAsJsonArray("minecraft:geometry")) {
                JsonObject geometry = element.getAsJsonObject();
                JsonObject description = geometry.getAsJsonObject("description");
                String identifier = description == null ? null : Json.text(description, "identifier", null);
                if (identifier != null) geometries.put(identifier, GeometryParser.readGeometry(identifier, geometry));
            }
            return;
        }

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (!entry.getKey().startsWith("geometry.") || !entry.getValue().isJsonObject()) continue;
            legacy.put(entry.getKey(), entry.getValue().getAsJsonObject());
        }
    }

    private void readLegacyGeometries(Map<String, JsonObject> legacy) {
        for (Map.Entry<String, JsonObject> entry : legacy.entrySet()) {
            // `geometry.zombie:geometry.humanoid` names the geometry and the one it extends at once.
            int colon = entry.getKey().indexOf(':');
            String identifier = colon < 0 ? entry.getKey() : entry.getKey().substring(0, colon);
            JsonObject body = colon < 0 ? entry.getValue()
                : inherit(entry.getValue(), entry.getKey().substring(colon + 1), legacy);
            geometries.put(identifier, GeometryParser.readGeometry(identifier, legacyGeometry(body)));
        }
    }

    /**
     * A 1.8 geometry written as an extension of another, folded into one body.
     *
     * The child's own fields win, and its bones are added to the parent's. A bone the child names
     * again replaces the parent's bone of that name, which is how an armour layer thickens one limb
     * and keeps the rest. The parent may itself extend something, so the chain is followed up.
     */
    private static JsonObject inherit(JsonObject child, String parentName, Map<String, JsonObject> bodies) {
        JsonObject parent = null;
        for (Map.Entry<String, JsonObject> candidate : bodies.entrySet()) {
            String name = candidate.getKey();
            int colon = name.indexOf(':');
            if (parentName.equals(colon < 0 ? name : name.substring(0, colon))) {
                parent = colon < 0 ? candidate.getValue()
                    : inherit(candidate.getValue(), name.substring(colon + 1), bodies);
                break;
            }
        }
        if (parent == null) return child;

        JsonObject merged = parent.deepCopy();
        for (Map.Entry<String, JsonElement> field : child.entrySet()) {
            if (!"bones".equals(field.getKey())) merged.add(field.getKey(), field.getValue());
        }
        merged.add("bones", mergeBones(parent.getAsJsonArray("bones"), child.getAsJsonArray("bones")));
        return merged;
    }

    /**
     * The parent's bones with the child's laid over them.
     *
     * A bone the child names again adds its own fields to the one it inherited, which is how
     * `geometry.humanoid.armor1` inflates every limb of the body it extends and keeps their cubes.
     * A bone carrying `reset` drops what it inherited instead, which is how the helmet geometry
     * empties the arms and legs it took from the whole armour set and keeps only the head.
     */
    private static JsonArray mergeBones(JsonArray parent, JsonArray child) {
        Map<String, JsonElement> bones = new LinkedHashMap<>();
        for (JsonElement bone : parent == null ? new JsonArray() : parent) {
            bones.put(nameOf(bone), bone);
        }
        for (JsonElement element : child == null ? new JsonArray() : child) {
            JsonObject bone = element.getAsJsonObject();
            JsonElement inherited = bones.get(nameOf(element));
            bones.put(nameOf(element),
                inherited == null || bone.has("reset") ? bone : overlay(inherited.getAsJsonObject(), bone));
        }

        JsonArray merged = new JsonArray();
        bones.values().forEach(merged::add);
        return merged;
    }

    /** The inherited bone with the child's own fields written over it. */
    private static JsonObject overlay(JsonObject inherited, JsonObject bone) {
        JsonObject merged = inherited.deepCopy();
        for (Map.Entry<String, JsonElement> field : bone.entrySet()) {
            merged.add(field.getKey(), field.getValue());
        }
        return merged;
    }

    private static String nameOf(JsonElement bone) {
        return Json.text(bone.getAsJsonObject(), "name", "");
    }

    /**
     * The 1.8 form keeps the description fields beside the bones; the parser expects a description.
     *
     * It also spells the texture size without its underscore, which is the only place in a pack
     * either name is written that way.
     */
    private static JsonObject legacyGeometry(JsonObject body) {
        JsonObject description = new JsonObject();
        copyField(body, description, "texturewidth", "texture_width");
        copyField(body, description, "textureheight", "texture_height");
        copyField(body, description, "visible_bounds_width", "visible_bounds_width");
        copyField(body, description, "visible_bounds_height", "visible_bounds_height");
        copyField(body, description, "visible_bounds_offset", "visible_bounds_offset");
        JsonObject wrapped = new JsonObject();
        wrapped.add("description", description);
        if (body.has("bones")) wrapped.add("bones", body.get("bones"));
        return wrapped;
    }

    private static void copyField(JsonObject body, JsonObject description, String from, String to) {
        JsonElement value = body.has(from) ? body.get(from) : body.get(to);
        if (value != null && !value.isJsonNull()) description.add(to, value);
    }

    private <T> void readSection(JsonObject root, String section,
        BiFunction<String, JsonObject, T> parser, Map<String, T> into) {
        JsonObject body = root.has(section) && root.get(section).isJsonObject()
            ? root.getAsJsonObject(section) : null;
        if (body == null) return;

        for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            into.put(entry.getKey(), parser.apply(entry.getKey(), entry.getValue().getAsJsonObject()));
        }
    }

    private void readDescribed(JsonObject root, String section, Map<String, ClientEntityDefinition> into) {
        JsonObject body = root.has(section) && root.get(section).isJsonObject()
            ? root.getAsJsonObject(section) : null;
        JsonObject description = body == null ? null : body.getAsJsonObject("description");
        if (description == null) return;

        String identifier = Json.text(description, "identifier", null);
        if (identifier == null) return;

        ClientEntityDefinition definition = ClientEntityParser.read(identifier, description);
        if (supersedes(definition, into.get(identifier))) into.put(identifier, definition);
    }

    /**
     * Whether this definition is the one to draw the entity with.
     *
     * A pack may describe one identifier several times over, each written for a different engine,
     * so that an older form of an entity ships beside the current one. The one to keep is the
     * newest whose `min_engine_version` the pack itself reaches. A pack whose own manifest names an
     * engine older than every definition it carries still gets the first one, because an entity
     * nothing draws is worse than one drawn from a definition written later.
     */
    private boolean supersedes(ClientEntityDefinition offered, ClientEntityDefinition held) {
        if (held == null) return true;
        if (offered.minEngineVersion().compareTo(engineVersion) > 0) return false;

        return held.minEngineVersion().compareTo(engineVersion) > 0
            || offered.minEngineVersion().compareTo(held.minEngineVersion()) >= 0;
    }
}
