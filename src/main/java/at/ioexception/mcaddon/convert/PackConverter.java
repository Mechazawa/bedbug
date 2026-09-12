package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.behaviour.ItemDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.RecipeDefinition;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns one Bedrock resource pack into a Java Edition resource pack on disk.
 *
 * This runs once per pack per version of that pack: the output directory is named after the pack's
 * uuid, its version and the pack format, and a finished conversion leaves a marker so a restart skips
 * straight past it. Converting 400 textures and 2000 sounds on every launch would be minutes of
 * startup for nothing.
 */
public final class PackConverter {
    /** Written alongside the output so an interrupted conversion is redone rather than trusted. */
    private static final String MARKER = ".converted";

    private PackConverter() {
    }

    public static ConvertedPack convert(BedrockPack pack, List<ItemDefinition> items,
        List<RecipeDefinition> recipes, Path cache) {
        String namespace = Names.namespaceOf(pack);
        Path root = cache.resolve("converted").resolve("%s-%s-%d".formatted(
            pack.manifest().uuid(), pack.manifest().version(), Platform.resourcePackFormat()));

        Textures textures = new Textures(namespace);
        Map<String, String> icons = new TreeMap<>();
        List<String> sounds = new ArrayList<>();

        try {
            boolean fresh = !Files.isRegularFile(root.resolve(MARKER));
            if (fresh) {
                deleteRecursively(root);
                Files.createDirectories(root);
                writeMetadata(pack, root);
            }

            textures.convert(pack, root, fresh);
            sounds.addAll(new Sounds(namespace).convert(pack, root, fresh));
            new Texts(namespace).convert(pack, root, fresh);
            icons.putAll(new Icons(namespace).convert(pack, textures.paths()));
            if (fresh) {
                writeItemModels(root, icons, items);
                writeRecipes(root, recipes);
            }

            if (fresh) {
                Files.writeString(root.resolve(MARKER), pack.displayName());
                McAddon.LOGGER.info("Converted {}: {} textures, {} sound events.",
                    pack.displayName(), textures.written(), sounds.size());
            }
        } catch (IOException error) {
            McAddon.LOGGER.error("Could not convert {}.", pack.displayName(), error);
        }

        return new ConvertedPack(pack, root, namespace, textures.paths(), icons, List.copyOf(sounds));
    }

    private static void writeMetadata(BedrockPack pack, Path root) throws IOException {
        JsonObject meta = new JsonObject();
        JsonObject section = new JsonObject();
        section.addProperty("pack_format", Platform.resourcePackFormat());
        section.addProperty("description", pack.displayName());
        meta.add("pack", section);
        Files.writeString(root.resolve("pack.mcmeta"), meta.toString(), StandardCharsets.UTF_8);

        Path icon = pack.file("pack_icon.png");
        if (Files.isRegularFile(icon)) {
            Files.copy(icon, root.resolve("pack.png"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Textures, with Targa decoded and the physically based sidecars left behind.
     *
     * A pack declaring the `pbr` capability ships a normal map, a heightmap and a combined
     * metalness map beside every colour texture. Java has no use for any of them, and the
     * `*.texture_set.json` that names them has to be read to know which files they are: dropping only
     * the sidecar leaves several hundred unused images in the output.
     */
    private static final class Textures {
        private final String namespace;
        private final Map<String, String> paths = new TreeMap<>();
        private int written;

        Textures(String namespace) {
            this.namespace = namespace;
        }

        Map<String, String> paths() {
            return paths;
        }

        int written() {
            return written;
        }

        void convert(BedrockPack pack, Path root, boolean write) throws IOException {
            Set<String> auxiliary = auxiliaryImages(pack);
            Path target = root.resolve("assets").resolve(namespace).resolve("textures");

            for (Path source : pack.list("textures", "")) {
                String relative = pack.root().relativize(source).toString().replace('\\', '/');
                String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean targa = name.endsWith(".tga");
                if (!targa && !name.endsWith(".png")) continue;
                if (auxiliary.contains(withoutExtension(relative))) continue;

                String inside = Names.sanitise(withoutExtension(relative).substring("textures/".length()));
                paths.put(withoutExtension(relative), namespace + ":" + inside);
                if (!write) continue;

                Path destination = target.resolve(inside + ".png");
                Files.createDirectories(destination.getParent());
                if (targa) {
                    ImageIO.write(TgaReader.read(Files.readAllBytes(source)), "PNG", destination.toFile());
                } else {
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                written++;
            }
        }

        /** Every image a texture set names as something other than its colour layer. */
        private Set<String> auxiliaryImages(BedrockPack pack) {
            Set<String> auxiliary = new HashSet<>();
            for (Path set : pack.list("textures", ".texture_set.json")) {
                auxiliary.add(withoutExtension(withoutExtension(
                    pack.root().relativize(set).toString().replace('\\', '/'))));
                try {
                    JsonObject body = Json.parseObject(set).getAsJsonObject("minecraft:texture_set");
                    if (body == null) continue;
                    for (Map.Entry<String, JsonElement> channel : body.entrySet()) {
                        if (channel.getKey().equals("color") || !channel.getValue().isJsonPrimitive()) continue;
                        auxiliary.add(channel.getValue().getAsString());
                    }
                } catch (IOException | RuntimeException error) {
                    McAddon.LOGGER.warn("Could not read {}.", set);
                }
            }
            return auxiliary;
        }
    }

    /**
     * `sound_definitions.json` becomes Java's `sounds.json`, one registered event per definition.
     *
     * Bedrock paths carry no extension and are pack relative; Java's are relative to the namespace's
     * `sounds/` directory. Volume, pitch and weight carry over, which is what makes a pack's random
     * variants pick the way its author intended.
     */
    private static final class Sounds {
        private final String namespace;

        Sounds(String namespace) {
            this.namespace = namespace;
        }

        List<String> convert(BedrockPack pack, Path root, boolean write) throws IOException {
            Path definitions = pack.file("sounds/sound_definitions.json");
            if (!Files.isRegularFile(definitions)) return List.of();

            JsonObject body = Json.parseObject(definitions);
            JsonObject entries = body.has("sound_definitions")
                ? body.getAsJsonObject("sound_definitions") : body;

            Map<String, JsonObject> byNamespace = new LinkedHashMap<>();
            List<String> events = new ArrayList<>();
            int repaired = 0;
            int missing = 0;

            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;

                int colon = entry.getKey().indexOf(':');
                String eventNamespace = colon > 0 ? Names.sanitise(entry.getKey().substring(0, colon)) : namespace;
                String eventPath = Names.sanitise(colon > 0 ? entry.getKey().substring(colon + 1) : entry.getKey());

                JsonObject converted = new JsonObject();
                JsonObject definition = entry.getValue().getAsJsonObject();
                converted.addProperty("category", category(Json.text(definition, "category", "neutral")));

                JsonArray files = new JsonArray();
                for (JsonElement sound : Json.list(definition, "sounds")) {
                    JsonObject reference = new JsonObject();
                    String bedrockPath;
                    if (sound.isJsonPrimitive()) {
                        bedrockPath = sound.getAsString();
                    } else if (sound.isJsonObject()) {
                        bedrockPath = Json.text(sound.getAsJsonObject(), "name", null);
                        copyNumber(sound.getAsJsonObject(), reference, "volume");
                        copyNumber(sound.getAsJsonObject(), reference, "pitch");
                        copyNumber(sound.getAsJsonObject(), reference, "weight");
                    } else {
                        continue;
                    }
                    if (bedrockPath == null) continue;

                    Path source = pack.file(bedrockPath + ".ogg");
                    if (!Files.isRegularFile(source)) {
                        missing++;
                        continue;
                    }
                    String inside = Names.sanitise(bedrockPath.startsWith("sounds/")
                        ? bedrockPath.substring("sounds/".length()) : bedrockPath);
                    reference.addProperty("name", eventNamespace + ":" + inside);
                    files.add(reference);

                    if (write && copy(source, root.resolve("assets").resolve(eventNamespace)
                        .resolve("sounds").resolve(inside + ".ogg"))) {
                        repaired++;
                    }
                }

                if (files.isEmpty()) continue;
                converted.add("sounds", files);
                byNamespace.computeIfAbsent(eventNamespace, key -> new JsonObject()).add(eventPath, converted);
                events.add(eventNamespace + ":" + eventPath);
            }

            if (write) {
                for (Map.Entry<String, JsonObject> file : byNamespace.entrySet()) {
                    Path target = root.resolve("assets").resolve(file.getKey()).resolve("sounds.json");
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, file.getValue().toString(), StandardCharsets.UTF_8);
                }
                if (repaired > 0) McAddon.LOGGER.info("Rewrote the page headers of {} Ogg files.", repaired);
                if (missing > 0) {
                    McAddon.LOGGER.warn("{} names {} sound files it does not ship; those play silent.",
                        pack.displayName(), missing);
                }
            }
            return events;
        }

        /** True when the file needed its final page rewritten on the way across. */
        private boolean copy(Path source, Path destination) throws IOException {
            Files.createDirectories(destination.getParent());
            byte[] bytes = Files.readAllBytes(source);
            byte[] fixed = OggRepair.repair(bytes);
            Files.write(destination, fixed == null ? bytes : fixed);
            return fixed != null;
        }

        private static void copyNumber(JsonObject from, JsonObject to, String key) {
            if (from.has(key) && from.get(key).isJsonPrimitive()) to.add(key, from.get(key));
        }

        /** Bedrock's categories are close enough to Java's sound sources to map by name. */
        private static String category(String bedrock) {
            return switch (bedrock) {
                case "music" -> "music";
                case "record" -> "record";
                case "weather" -> "weather";
                case "block" -> "block";
                case "hostile" -> "hostile";
                case "player" -> "player";
                case "ambient" -> "ambient";
                case "ui" -> "master";
                default -> "neutral";
            };
        }
    }

    /** Bedrock `.lang` files become Java lang JSON, one per locale the pack ships. */
    private static final class Texts {
        private final String namespace;

        Texts(String namespace) {
            this.namespace = namespace;
        }

        void convert(BedrockPack pack, Path root, boolean write) throws IOException {
            if (!write) return;

            for (Path source : pack.list("texts", ".lang")) {
                String locale = withoutExtension(source.getFileName().toString()).toLowerCase(Locale.ROOT);
                Map<String, String> entries = LangFile.read(source);
                if (entries.isEmpty()) continue;

                JsonObject json = new JsonObject();
                entries.forEach(json::addProperty);
                Path target = root.resolve("assets").resolve(namespace).resolve("lang").resolve(locale + ".json");
                Files.createDirectories(target.getParent());
                Files.writeString(target, json.toString(), StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * `textures/item_texture.json` maps the short key an item's icon names onto a texture path.
     *
     * The keys are what a behaviour pack writes, so resolving them is what lets the two halves of an
     * add-on meet.
     */
    private record Icons(String namespace) {
        Map<String, String> convert(BedrockPack pack, Map<String, String> texturePaths) {
            Map<String, String> icons = new TreeMap<>();
            Path file = pack.file("textures/item_texture.json");
            if (!Files.isRegularFile(file)) return icons;

            try {
                JsonObject data = Json.parseObject(file).getAsJsonObject("texture_data");
                if (data == null) return icons;

                for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonElement textures = entry.getValue().getAsJsonObject().get("textures");
                    String path = textures == null ? null
                        : textures.isJsonArray() ? textures.getAsJsonArray().get(0).getAsString()
                        : textures.getAsString();
                    String resolved = path == null ? null : texturePaths.get(path);
                    if (resolved != null) icons.put(entry.getKey(), resolved);
                }
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
            return icons;
        }
    }

    /**
     * An item model per behaviour pack item, in both shapes the supported versions need.
     *
     * Up to 1.21.3 an item finds its model by name under `models/item`; from 1.21.4 it needs an entry
     * under `items` naming that model. Writing both costs a few hundred bytes and covers all three.
     */
    private static void writeItemModels(Path root, Map<String, String> icons, List<ItemDefinition> items)
        throws IOException {
        for (ItemDefinition item : items) {
            String icon = item.iconKey() == null ? null : icons.get(item.iconKey());
            if (icon == null) continue;

            int colon = item.identifier().indexOf(':');
            String namespace = Names.sanitise(colon > 0 ? item.identifier().substring(0, colon) : "minecraft");
            String name = Names.sanitise(colon > 0 ? item.identifier().substring(colon + 1) : item.identifier());

            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", icon);
            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:item/generated");
            model.add("textures", textures);
            write(root.resolve("assets").resolve(namespace).resolve("models/item").resolve(name + ".json"), model);

            JsonObject reference = new JsonObject();
            reference.addProperty("type", "minecraft:model");
            reference.addProperty("model", namespace + ":item/" + name);
            JsonObject definition = new JsonObject();
            definition.add("model", reference);
            write(root.resolve("assets").resolve(namespace).resolve("items").resolve(name + ".json"), definition);
        }
    }

    /**
     * Crafting recipes, as a data pack the game reads out of the same directory.
     *
     * Bedrock's shaped recipes carry a pattern and a key map exactly as Java's do, so the conversion
     * is a rename. Furnace recipes and anything with a Bedrock-only tag are left out and reported.
     */
    private static void writeRecipes(Path root, List<RecipeDefinition> recipes) throws IOException {
        boolean modern = Platform.modernRecipes();
        String directory = modern ? "recipe" : "recipes";

        for (RecipeDefinition recipe : recipes) {
            JsonObject json = new JsonObject();
            switch (recipe.kind()) {
                case SHAPED -> {
                    json.addProperty("type", "minecraft:crafting_shaped");
                    JsonArray pattern = new JsonArray();
                    recipe.pattern().forEach(pattern::add);
                    json.add("pattern", pattern);

                    JsonObject key = new JsonObject();
                    recipe.key().forEach((symbol, item) -> key.add(symbol, ingredient(item, modern)));
                    json.add("key", key);
                }
                case SHAPELESS -> {
                    json.addProperty("type", "minecraft:crafting_shapeless");
                    JsonArray ingredients = new JsonArray();
                    for (RecipeDefinition.Ingredient ingredient : recipe.ingredients()) {
                        for (int i = 0; i < Math.max(1, ingredient.count()); i++) {
                            ingredients.add(ingredient(ingredient.item(), modern));
                        }
                    }
                    json.add("ingredients", ingredients);
                }
                case FURNACE -> {
                    json.addProperty("type", "minecraft:smelting");
                    json.add("ingredient", ingredient(recipe.ingredients().isEmpty()
                        ? "minecraft:air" : recipe.ingredients().get(0).item(), modern));
                    json.addProperty("cookingtime", 200);
                    json.addProperty("experience", 0.1);
                }
                default -> { }
            }

            JsonObject result = new JsonObject();
            result.addProperty(modern ? "id" : "item", recipe.result());
            result.addProperty("count", Math.max(1, recipe.resultCount()));
            json.add("result", result);

            int colon = recipe.identifier().indexOf(':');
            String namespace = Names.sanitise(colon > 0
                ? recipe.identifier().substring(0, colon) : "minecraft");
            String name = Names.sanitise(colon > 0
                ? recipe.identifier().substring(colon + 1) : recipe.identifier());
            write(root.resolve("data").resolve(namespace).resolve(directory).resolve(name + ".json"), json);
        }
    }

    private static JsonElement ingredient(String item, boolean modern) {
        if (modern) return new com.google.gson.JsonPrimitive(item);
        JsonObject wrapped = new JsonObject();
        wrapped.addProperty("item", item);
        return wrapped;
    }

    private static void write(Path file, JsonObject content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
    }

    private static String withoutExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
