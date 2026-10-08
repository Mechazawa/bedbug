package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.bedrock.BedrockAssets;
import at.ioexception.mcaddon.bedrock.ClientBlockDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.BlockDefinition;
import at.ioexception.mcaddon.bedrock.model.BedrockGeometry;
import at.ioexception.mcaddon.bedrock.behaviour.ItemDefinition;
import at.ioexception.mcaddon.bedrock.molang.MolangEnvironment;
import at.ioexception.mcaddon.bedrock.molang.MolangParser;
import at.ioexception.mcaddon.bedrock.molang.MolangValue;
import at.ioexception.mcaddon.bedrock.particle.ParticleDefinition;
import at.ioexception.mcaddon.block.AddonBlock;
import at.ioexception.mcaddon.block.BlockComponents;
import at.ioexception.mcaddon.bedrock.behaviour.LootTableDefinition;
import at.ioexception.mcaddon.bedrock.behaviour.RecipeDefinition;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /** One add-on: the resource pack, and the behaviour packs that came out of the same file. */
    public record Bundle(BedrockPack resources, List<BedrockPack> behaviours) {
    }

    /**
     * When the newest of this mod's classes was compiled, in a development run, and nothing outside one.
     *
     * A released jar's version changes with its code. A development run keeps one version across
     * every edit to the converter, so without this a run after such an edit reads the output the
     * code before it wrote.
     */
    private static final String CODE_BUILT = net.fabricmc.loader.api.FabricLoader.getInstance()
        .isDevelopmentEnvironment() ? newestClass() : "";

    private static String newestClass() {
        try {
            Path classes = Path.of(PackConverter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(classes)) return "";
            try (java.util.stream.Stream<Path> walk = Files.walk(classes)) {
                return String.valueOf(walk.filter(path -> path.toString().endsWith(".class"))
                    .mapToLong(path -> path.toFile().lastModified()).max().orElse(0));
            }
        } catch (IOException | java.net.URISyntaxException | SecurityException unreadable) {
            return "";
        }
    }

    /**
     * What the converted output was made from, as one line.
     *
     * Everything the conversion reads goes into it: every half of the add-on, and this mod's own
     * version, because the output is a function of the code that wrote it as well. A pack edited in
     * place gives a different answer and is converted again.
     */
    private static String stampOf(Bundle bundle, FunctionNames functions) {
        StringBuilder stamp = new StringBuilder(at.ioexception.mcaddon.McAddon.VERSION);
        stamp.append(' ').append(CODE_BUILT);
        stamp.append(' ').append(functions.stamp());
        stamp.append(' ').append(bundle.resources().contentStamp());
        for (BedrockPack behaviour : bundle.behaviours()) {
            stamp.append(' ').append(behaviour.contentStamp());
        }
        return stamp.toString();
    }

    /** What the last conversion into this directory was made from, or null where there was none. */
    private static String readMarker(Path root) {
        try {
            return Files.readString(root.resolve(MARKER));
        } catch (IOException nothingThere) {
            return null;
        }
    }

    /** Converts one add-on under the namespace chosen for it, calling functions out of `functions`. */
    public static ConvertedPack convert(Bundle bundle, String namespace, FunctionNames functions, Path cache) {
        BedrockPack pack = bundle.resources();
        List<ItemDefinition> items = new ArrayList<>();
        List<RecipeDefinition> recipes = new ArrayList<>();
        List<LootTableDefinition> loot = new ArrayList<>();
        List<BlockDefinition> blocks = new ArrayList<>();
        List<at.ioexception.mcaddon.bedrock.behaviour.BiomeDefinition> biomes = new ArrayList<>();
        List<at.ioexception.mcaddon.bedrock.behaviour.FeatureDefinition> features = new ArrayList<>();
        List<at.ioexception.mcaddon.bedrock.behaviour.FeatureRuleDefinition> featureRules =
            new ArrayList<>();
        List<at.ioexception.mcaddon.bedrock.behaviour.DimensionDefinition> dimensions =
            new ArrayList<>();
        for (BedrockPack behaviour : bundle.behaviours()) {
            items.addAll(ItemDefinition.readAll(behaviour));
            recipes.addAll(RecipeDefinition.readAll(behaviour));
            loot.addAll(LootTableDefinition.readAll(behaviour));
            blocks.addAll(BlockDefinition.readAll(behaviour));
            biomes.addAll(at.ioexception.mcaddon.bedrock.behaviour.BiomeDefinition.readAll(behaviour));
            features.addAll(
                at.ioexception.mcaddon.bedrock.behaviour.FeatureDefinition.readAll(behaviour));
            featureRules.addAll(
                at.ioexception.mcaddon.bedrock.behaviour.FeatureRuleDefinition.readAll(behaviour));
            dimensions.addAll(
                at.ioexception.mcaddon.bedrock.behaviour.DimensionDefinition.readAll(behaviour));
        }
        Path root = cache.resolve("converted").resolve("%s-%s-%d".formatted(
            pack.manifest().uuid(), pack.manifest().version(), Platform.resourcePackFormat()));

        Textures textures = new Textures(namespace);
        Map<String, String> icons = new TreeMap<>();
        List<String> sounds = new ArrayList<>();
        BedrockAssets assets = BedrockAssets.read(pack);
        Map<String, at.ioexception.mcaddon.bedrock.LightingDefinition> lightings =
            at.ioexception.mcaddon.bedrock.LightingDefinition.readAll(pack);
        Map<String, at.ioexception.mcaddon.bedrock.AtmosphereDefinition> atmospheres =
            at.ioexception.mcaddon.bedrock.AtmosphereDefinition.readAll(pack);
        at.ioexception.mcaddon.bedrock.RendererSettings.WATER.report(pack);
        at.ioexception.mcaddon.bedrock.RendererSettings.COLOR_GRADING.report(pack);
        Map<String, SpawnEggAppearance> eggs = new LinkedHashMap<>();
        assets.entities().forEach((identifier, definition) -> {
            if (definition.spawnEgg() != null) eggs.put(identifier, definition.spawnEgg());
        });

        try {
            String stamp = stampOf(bundle, functions);
            boolean fresh = !stamp.equals(readMarker(root));
            if (fresh) {
                deleteRecursively(root);
                Files.createDirectories(root);
            }

            textures.convert(pack, root, fresh);
            sounds.addAll(new Sounds(namespace).convert(pack, root, fresh));
            Set<String> locales = new Texts(namespace).convert(bundle, blocks, root, fresh);
            if (fresh) writeMetadata(pack, bundle, locales, root);
            icons.putAll(new Icons(namespace).convert(pack, textures.paths()));
            if (fresh) {
                Set<String> sprites = new LinkedHashSet<>();
                writeItemModels(root, icons, items, sprites);
                writeSpawnEggs(root, icons, eggs, sprites);
                writeBuckets(root, bundle.behaviours());
                writeRecipes(root, recipes);
                writeLootTables(root, loot, namespace);
                writeBlockData(root, blocks, loot);
                writeItemTags(root, items);
                writeJukeboxSongs(root, items, namespace, sounds);
                writeDyeRecipes(root, items);
                writeBlockModels(root, icons, blocks, assets, sprites,
                    ClientBlockDefinition.readAll(pack));
                writeAtlas(root, sprites);
                writeParticles(pack, root, textures.paths());
                writeFunctions(bundle, root, namespace, functions);
                writeFont(pack, root, namespace);
                Worldgen.write(root, namespace, biomes,
                    at.ioexception.mcaddon.bedrock.ClientBiomeDefinition.readAll(pack,
                        name -> BedrockSoundNames.java(name, namespace, sounds)),
                    features, featureRules, dimensions);
                Timelines.write(root, namespace, lightings, atmospheres);
            }

            if (fresh) {
                Files.writeString(root.resolve(MARKER), stamp);
                McAddon.LOGGER.info("Converted {}: {} textures, {} sound events.",
                    pack.displayName(), textures.written(), sounds.size());
            }
        } catch (IOException error) {
            McAddon.LOGGER.error("Could not convert {}.", pack.displayName(), error);
        }

        return new ConvertedPack(pack, bundle.behaviours(), root, namespace, textures.paths(),
            textures.emissive(), icons, List.copyOf(sounds), eggs);
    }

    private static void writeMetadata(BedrockPack pack, Bundle bundle, Set<String> locales, Path root)
        throws IOException {
        JsonObject meta = new JsonObject();
        JsonObject section = new JsonObject();
        section.addProperty("pack_format", Platform.resourcePackFormat());
        // From pack format 82 the game wants the range spelled out as well as the single number, and
        // reads a pack that gives only the number through a fallback it complains about first.
        section.addProperty("min_format", Platform.resourcePackFormat());
        section.addProperty("max_format", Platform.resourcePackFormat());
        section.addProperty("description", pack.displayName());
        meta.add("pack", section);

        JsonObject languages = languages(bundle, locales);
        if (languages.size() > 0) meta.add("language", languages);
        Files.writeString(root.resolve("pack.mcmeta"), meta.toString(), StandardCharsets.UTF_8);

        Path icon = pack.file("pack_icon.png");
        if (Files.isRegularFile(icon)) {
            Files.copy(icon, root.resolve("pack.png"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The languages the add-on declares, narrowed to the ones it has text for.
     *
     * A locale listed with no `.lang` behind it would stand in the language screen with nothing to
     * say, so a pack that lists more than it ships contributes only what it ships.
     */
    private static JsonObject languages(Bundle bundle, Set<String> locales) {
        List<BedrockPack> everywhere = new ArrayList<>(bundle.behaviours());
        everywhere.add(bundle.resources());

        JsonObject section = new JsonObject();
        for (Language language : Language.readAll(everywhere)) {
            if (locales.contains(language.code())) section.add(language.code(), language.declaration());
        }
        return section;
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
        private final Map<String, String> emissive = new TreeMap<>();
        private int written;

        Textures(String namespace) {
            this.namespace = namespace;
        }

        Map<String, String> paths() {
            return paths;
        }

        Map<String, String> emissive() {
            return emissive;
        }

        int written() {
            return written;
        }

        void convert(BedrockPack pack, Path root, boolean write) throws IOException {
            List<TextureSet> sets = TextureSet.readAll(pack);
            Map<String, Flipbook> flipbooks = Flipbook.readAll(pack);
            Set<String> auxiliary = new HashSet<>();
            for (TextureSet set : sets) auxiliary.addAll(set.auxiliary());

            Path target = root.resolve("assets").resolve(namespace).resolve("textures");
            for (Path source : pack.list("textures", "")) {
                String relative = pack.relative(source).toString().replace('\\', '/');
                String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
                boolean targa = name.endsWith(".tga");
                if (!targa && !name.endsWith(".png")) continue;
                if (auxiliary.contains(withoutExtension(relative))) continue;

                String inside = Names.sanitise(withoutExtension(relative).substring("textures/".length()));
                paths.put(withoutExtension(relative), namespace + ":" + inside);
                if (!write) continue;

                // One texture this reader cannot make sense of costs the pack that texture, not the
                // rest of its sounds, models and functions.
                try {
                    Path destination = target.resolve(inside + ".png");
                    Files.createDirectories(destination.getParent());
                    BufferedImage decoded = targa
                        ? TgaReader.read(Files.readAllBytes(source))
                        : ImageIO.read(source.toFile());
                    AlphaMask mask = AlphaMask.tryFrom(decoded);
                    if (mask == null && !targa) {
                        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        ImageIO.write(mask == null ? decoded : mask.opaque(), "PNG",
                            destination.toFile());
                    }
                    Flipbook flipbook = flipbooks.get(withoutExtension(relative));
                    if (flipbook != null) flipbook.write(destination);
                    written++;
                } catch (IOException | RuntimeException error) {
                    paths.remove(withoutExtension(relative));
                    AddonEntities.unsupported("texture " + source.getFileName());
                }
            }

            // A cached conversion has the files already, so what glows is read back rather than redone.
            for (TextureSet set : sets) {
                String inside = Names.sanitise(set.colour().substring("textures/".length()));
                if (write) writeSurface(pack, set, target, inside);
                if (Files.isRegularFile(target.resolve(inside + "_emissive.png"))) {
                    emissive.put(set.colour(), namespace + ":" + inside + "_emissive");
                }
            }
        }

        /**
         * Everything a physically based set can become, which is two different things at once.
         *
         * The emissive copy is what vanilla Java draws as a second lit pass. The `_n` and `_s`
         * sidecars are what a shader pack loaded by Iris or OptiFine reads, and cost nothing when
         * the player has no shader pack: the game never looks at them.
         */
        private void writeSurface(BedrockPack pack, TextureSet set, Path target, String inside)
            throws IOException {
            TextureSet.Layers layers = set.layers(pack);
            BufferedImage glow = TextureSet.emissiveLayer(layers, EMISSIVE_THRESHOLD);
            if (glow != null) {
                Path destination = target.resolve(inside + "_emissive.png");
                Files.createDirectories(destination.getParent());
                ImageIO.write(glow, "PNG", destination.toFile());
            }

            for (TextureSet.Sidecar sidecar : TextureSet.shaderSidecars(layers)) {
                Path destination = target.resolve(inside + sidecar.suffix() + ".png");
                Files.createDirectories(destination.getParent());
                ImageIO.write(sidecar.image(), "PNG", destination.toFile());
                written++;
            }
        }
    }

    /** How bright the combined map's emissive channel has to be before a pixel counts as glowing. */
    private static final int EMISSIVE_THRESHOLD = 8;

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

                Names.Identifier event = Names.soundEvent(entry.getKey(), namespace);
                String eventNamespace = event.namespace();
                String eventPath = event.path();

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

        /**
         * Every `.lang` in the add-on, both halves together.
         *
         * A pack is free to put a name in either half and real ones use both, so the files are merged
         * rather than taken from the resource pack alone. The resource pack is read last, because
         * what a player sees is what it says.
         */
        Set<String> convert(Bundle bundle, List<BlockDefinition> blocks, Path root, boolean write)
            throws IOException {
            if (!write) return Set.of();

            Map<String, Map<String, String>> byLocale = new LinkedHashMap<>();
            List<BedrockPack> everywhere = new ArrayList<>(bundle.behaviours());
            everywhere.add(bundle.resources());

            for (BedrockPack pack : everywhere) {
                for (Path source : pack.list("texts", ".lang")) {
                    String locale = withoutExtension(source.getFileName().toString()).toLowerCase(Locale.ROOT);
                    byLocale.computeIfAbsent(locale, name -> new LinkedHashMap<>())
                        .putAll(LangFile.read(source));
                }
            }

            Set<String> written = new LinkedHashSet<>();
            for (Map.Entry<String, Map<String, String>> locale : byLocale.entrySet()) {
                if (locale.getValue().isEmpty()) continue;

                written.add(locale.getKey());
                JsonObject json = new JsonObject();
                locale.getValue().forEach(json::addProperty);
                locale.getValue().forEach((key, value) -> {
                    String java = javaKey(key);
                    if (java != null && !json.has(java)) json.addProperty(java, value);
                });
                blockNames(blocks, locale.getValue(), json);

                Path target = root.resolve("assets").resolve(namespace).resolve("lang")
                    .resolve(locale.getKey() + ".json");
                Files.createDirectories(target.getParent());
                Files.writeString(target, json.toString(), StandardCharsets.UTF_8);
            }
            return written;
        }

        /**
         * The name a block's `minecraft:display_name` asks for, under the key Java reads it by.
         *
         * Bedrock takes the component as a translation key and falls back to showing the raw string,
         * so a key this locale translates becomes the translation and anything else becomes itself.
         */
        private static void blockNames(List<BlockDefinition> blocks, Map<String, String> translations,
            JsonObject json) {
            for (BlockDefinition block : blocks) {
                String name = BlockComponents.displayName(block);
                if (name == null) continue;

                Names.Identifier id = Names.split(block.identifier(), "minecraft");
                String key = "block." + id.namespace() + "." + id.path();
                if (json.has(key)) continue;
                json.addProperty(key, translations.getOrDefault(name, name));
            }
        }
    }

    /**
     * The same name under the key Java looks it up by, or null when Java has no such key.
     *
     * The two editions name the same thing differently: Bedrock keeps the colon of an identifier and
     * ends every key with `.name`, and it calls a block a tile. Without the second spelling a pack's
     * own translations are all present and none of them is ever found.
     */
    private static String javaKey(String bedrock) {
        if (!bedrock.endsWith(".name")) return null;

        String body = bedrock.substring(0, bedrock.length() - ".name".length());
        if (body.startsWith("item.spawn_egg.entity.")) {
            return identifierKey("item", body.substring("item.spawn_egg.entity.".length()), "_spawn_egg");
        }
        if (body.startsWith("entity.")) return identifierKey("entity", body.substring("entity.".length()), "");
        if (body.startsWith("item.")) return identifierKey("item", body.substring("item.".length()), "");
        if (body.startsWith("tile.")) return identifierKey("block", body.substring("tile.".length()), "");
        return null;
    }

    private static String identifierKey(String kind, String identifier, String suffix) {
        if (identifier.indexOf(':') <= 0) return null;

        Names.Identifier id = Names.split(identifier, "minecraft");
        return kind + "." + id.namespace() + "." + id.path() + suffix;
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
            for (String name : List.of("textures/item_texture.json", "textures/terrain_texture.json")) {
                read(pack.file(name), texturePaths, icons);
            }
            return icons;
        }

        private void read(Path file, Map<String, String> texturePaths, Map<String, String> icons) {
            if (!Files.isRegularFile(file)) return;

            try {
                JsonObject data = Json.parseObject(file).getAsJsonObject("texture_data");
                if (data == null) return;

                for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    JsonElement textures = entry.getValue().getAsJsonObject().get("textures");
                    if (textures == null) continue;
                    if (!textures.isJsonArray()) {
                        put(icons, entry.getKey(), textures, texturePaths);
                        continue;
                    }

                    // A key naming a list is one icon per entry, each picked by its own number,
                    // which is how every spawn egg in the game shares the key `spawn_egg`.
                    JsonArray list = textures.getAsJsonArray();
                    for (int index = 0; index < list.size(); index++) {
                        put(icons, index == 0 ? entry.getKey() : entry.getKey() + "#" + index,
                            list.get(index), texturePaths);
                    }
                }
            } catch (IOException | RuntimeException error) {
                McAddon.LOGGER.warn("Could not read {}: {}", file.getFileName(), error.toString());
            }
        }

        /** One icon, where the pack wrote a plain path and the conversion kept a texture for it. */
        private static void put(Map<String, String> icons, String key, JsonElement texture,
            Map<String, String> texturePaths) {
            if (!texture.isJsonPrimitive()) return;

            String resolved = texturePaths.get(texture.getAsString());
            if (resolved != null) icons.put(key, resolved);
        }
    }

    /**
     * An item model per behaviour pack item, in both shapes the supported versions need.
     *
     * Up to 1.21.3 an item finds its model by name under `models/item`; from 1.21.4 it needs an entry
     * under `items` naming that model. Writing both costs a few hundred bytes and covers all three.
     */
    private static void writeItemModels(Path root, Map<String, String> icons,
        List<ItemDefinition> items, Set<String> sprites) throws IOException {
        for (ItemDefinition item : items) {
            String icon = item.iconKey() == null ? null : icons.get(item.iconKey());
            if (icon == null) continue;

            sprites.add(icon);
            // `hand_equipped` means the item is carried the way a tool is, which Java draws by
            // parenting the model to `handheld` rather than to the flat one.
            boolean held = item.components().containsKey("minecraft:hand_equipped");
            writeFlatItem(root, Names.split(item.identifier(), "minecraft"), "",
                flat(icon, held ? "minecraft:item/handheld" : "minecraft:item/generated"),
                dyeTint(item.dyeColour()));
        }
    }

    /**
     * The dyed colour painted over an item's one layer, for the versions reading tints from an item
     * definition. Older ones are told the same thing through a colour provider instead.
     */
    private static JsonArray dyeTint(int colour) {
        if (colour < 0) return null;

        JsonObject dye = new JsonObject();
        dye.addProperty("type", "minecraft:dye");
        dye.addProperty("default", colour);
        JsonArray tints = new JsonArray();
        tints.add(dye);
        return tints;
    }

    /**
     * One item drawn from one texture, in both the shapes a version may read it.
     *
     * Up to 1.21.3 the model at `models/item/<name>.json` is the whole answer. From 1.21.4 the game
     * reads `items/<name>.json` first and that names the model, so both are written and whichever
     * the running version looks for is there.
     */
    private static void writeFlatItem(Path root, Names.Identifier id, String suffix, JsonObject model,
        JsonArray tints) throws IOException {
        String name = id.path() + suffix;
        Path assets = root.resolve("assets").resolve(id.namespace());
        write(assets.resolve("models/item").resolve(name + ".json"), model);

        JsonObject reference = new JsonObject();
        reference.addProperty("type", "minecraft:model");
        reference.addProperty("model", id.namespace() + ":item/" + name);
        if (tints != null) reference.add("tints", tints);

        JsonObject definition = new JsonObject();
        definition.add("model", reference);
        write(assets.resolve("items").resolve(name + ".json"), definition);
    }

    /** The model for an item drawn from one texture, under whichever parent holds it right. */
    private static JsonObject flat(String icon, String parent) {
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", icon);

        JsonObject model = new JsonObject();
        model.addProperty("parent", parent);
        model.add("textures", textures);
        return model;
    }

    /**
     * Adds every sprite the written models point at to the atlas they are drawn from.
     *
     * Java stitches only what its own atlas definition names, which is the `block` and `item`
     * directories, and a Bedrock pack keeps its textures wherever it likes. A texture that never
     * reaches the atlas draws as the missing-texture chequer however correct the model is. The list
     * comes from the models rather than from the pack's texture index, so a sprite a model reaches
     * by any route is stitched and one nothing draws is not. Atlas definitions from every loaded
     * pack are merged, so naming the sprites here adds to vanilla's list rather than replacing it.
     */
    private static void writeAtlas(Path root, Set<String> icons) throws IOException {
        JsonArray sources = new JsonArray();
        for (String icon : icons) {
            JsonObject single = new JsonObject();
            single.addProperty("type", "minecraft:single");
            single.addProperty("resource", icon);
            sources.add(single);
        }
        if (sources.isEmpty()) return;

        JsonObject atlas = new JsonObject();
        atlas.add("sources", sources);
        write(root.resolve("assets/minecraft/atlases/blocks.json"), atlas);
    }

    /**
     * The model and the name for each entity's spawn egg.
     *
     * A pack that gave its egg an item texture gets a flat item drawn from that texture. One that
     * gave colours gets the vanilla egg painted with them, which on a version reading item
     * definitions is two constant tints and on an older one is the colours the item itself carries.
     */
    private static void writeSpawnEggs(Path root, Map<String, String> icons,
        Map<String, SpawnEggAppearance> eggs, Set<String> sprites) throws IOException {
        for (SpawnEggAppearance egg : eggs.values()) {
            String icon = egg.iconKey() == null ? null : icons.get(egg.iconKey());
            if (icon != null) sprites.add(icon);
            JsonObject vanilla = new JsonObject();
            vanilla.addProperty("parent", "minecraft:item/template_spawn_egg");

            writeFlatItem(root, Names.split(egg.identifier(), "minecraft"), "_spawn_egg",
                icon == null ? vanilla : flat(icon, "minecraft:item/generated"),
                icon == null ? tints(egg) : null);
        }
    }

    /**
     * The bucket each entity `minecraft:bucketable` makes scoopable is carried in, drawn as a bucket
     * of water.
     *
     * Bedrock draws the entity inside the bucket from its own model, and a Java item model has no
     * way to name an entity, so the bucket shows what it holds by its name instead.
     */
    private static void writeBuckets(Path root, List<BedrockPack> behaviours) throws IOException {
        for (BedrockPack behaviour : behaviours) {
            for (at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition entity
                : at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition.readAll(behaviour)) {
                boolean scoopable = entity.components().containsKey("minecraft:bucketable")
                    || entity.componentGroups().values().stream()
                        .anyMatch(group -> group.has("minecraft:bucketable"));
                if (!scoopable) continue;

                JsonObject bucket = new JsonObject();
                bucket.addProperty("parent", "minecraft:item/water_bucket");
                writeFlatItem(root, Names.split(entity.identifier(), "minecraft"), "_bucket", bucket,
                    null);
            }
        }
    }

    /** The two layers of the vanilla egg, each painted a colour the pack chose. */
    private static JsonArray tints(SpawnEggAppearance egg) {
        JsonArray tints = new JsonArray();
        for (int colour : new int[] {egg.baseColour(), egg.overlayColour()}) {
            JsonObject tint = new JsonObject();
            tint.addProperty("type", "minecraft:constant");
            tint.addProperty("value", colour);
            tints.add(tint);
        }
        return tints;
    }

    /** What a Bedrock crafting station tag names, as the Java recipe type made there. */
    private static final Map<String, String> COOKERS = Map.of(
        "furnace", "minecraft:smelting",
        "blast_furnace", "minecraft:blasting",
        "smoker", "minecraft:smoking",
        "campfire", "minecraft:campfire_cooking",
        "soul_campfire", "minecraft:campfire_cooking");

    /** The station tags a converted recipe answers without needing a recipe type of its own. */
    private static final Set<String> PLAIN_STATIONS =
        Set.of("crafting_table", "smithing_table", "brewing_stand");

    /**
     * Crafting recipes, as a data pack the game reads out of the same directory.
     *
     * Bedrock's shaped recipes carry a pattern and a key map exactly as Java's do, so the conversion
     * is a rename. `tags` names the block the recipe is made at, and one Bedrock furnace recipe
     * listing four cookers becomes one Java recipe per cooker, because a Java cooking recipe names
     * the one block it belongs to.
     */
    private static void writeRecipes(Path root, List<RecipeDefinition> recipes) throws IOException {
        for (RecipeDefinition recipe : recipes) {
            switch (recipe.kind()) {
                case SHAPED -> writeRecipe(root, recipe, "", shaped(recipe));
                case SHAPELESS -> writeRecipe(root, recipe, "",
                    recipe.tags().contains("stonecutter") ? stonecutting(recipe) : shapeless(recipe));
                case FURNACE -> {
                    for (Map.Entry<String, String> cooker : cookers(recipe).entrySet()) {
                        writeRecipe(root, recipe, cooker.getKey(), cooking(recipe, cooker.getValue()));
                    }
                }
                case SMITHING_TRANSFORM -> writeRecipe(root, recipe, "", smithingTransform(recipe));
                case SMITHING_TRIM -> {
                    JsonObject json = smithingTrim(recipe);
                    if (json != null) writeRecipe(root, recipe, "", json);
                }
                default -> { }
            }
            reportStations(recipe);
        }
    }

    private static void writeRecipe(Path root, RecipeDefinition recipe, String suffix, JsonObject json)
        throws IOException {
        if (recipe.group() != null) json.addProperty("group", recipe.group());

        Names.Identifier id = Names.split(recipe.identifier(), "minecraft");
        Path data = root.resolve("data").resolve(id.namespace());
        write(data.resolve(dataDirectory("recipe")).resolve(id.path() + suffix + ".json"), json);

        if (recipe.unlock().isEmpty()) return;
        write(data.resolve(dataDirectory("advancement")).resolve("recipes")
            .resolve(id.path() + suffix + ".json"),
            unlockAdvancement(id.namespace() + ":" + id.path() + suffix, recipe.unlock()));
    }

    /**
     * The advancement that hands a player a recipe, which is how Java says one is unlocked.
     *
     * Java keeps a recipe out of the book until an advancement rewards it, so a Bedrock `unlock`
     * list becomes one criterion per item named and holding any of them is enough. The recipe
     * itself is a criterion too, which is what gives the book entry to a player handed the recipe
     * by a command. The advancement carries no display, so nothing is shown when it is met.
     */
    private static JsonObject unlockAdvancement(String recipe, List<String> unlock) {
        JsonObject criteria = new JsonObject();
        JsonArray names = new JsonArray();

        JsonObject recipeConditions = new JsonObject();
        recipeConditions.addProperty("recipe", recipe);
        criteria.add("has_the_recipe", trigger("minecraft:recipe_unlocked", recipeConditions));
        names.add("has_the_recipe");

        for (int i = 0; i < unlock.size(); i++) {
            JsonArray items = new JsonArray();
            items.add(Platform.heldItem(unlock.get(i)));
            JsonObject conditions = new JsonObject();
            conditions.add("items", items);

            criteria.add("has_item_" + i, trigger("minecraft:inventory_changed", conditions));
            names.add("has_item_" + i);
        }

        JsonArray requirements = new JsonArray();
        requirements.add(names);

        JsonArray rewarded = new JsonArray();
        rewarded.add(recipe);
        JsonObject rewards = new JsonObject();
        rewards.add("recipes", rewarded);

        JsonObject json = new JsonObject();
        json.addProperty("parent", "minecraft:recipes/root");
        json.add("criteria", criteria);
        json.add("requirements", requirements);
        json.add("rewards", rewards);
        return json;
    }

    private static JsonObject trigger(String name, JsonObject conditions) {
        JsonObject criterion = new JsonObject();
        criterion.addProperty("trigger", name);
        criterion.add("conditions", conditions);
        return criterion;
    }

    private static JsonObject shaped(RecipeDefinition recipe) {
        boolean bare = Platform.bareRecipeIngredients();
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:crafting_shaped");

        JsonArray pattern = new JsonArray();
        recipe.pattern().forEach(pattern::add);
        json.add("pattern", pattern);

        JsonObject key = new JsonObject();
        recipe.key().forEach((symbol, item) -> key.add(symbol, ingredient(item, bare)));
        json.add("key", key);
        return craftingResult(json, recipe);
    }

    private static JsonObject shapeless(RecipeDefinition recipe) {
        boolean bare = Platform.bareRecipeIngredients();
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:crafting_shapeless");

        JsonArray ingredients = new JsonArray();
        for (RecipeDefinition.Ingredient ingredient : recipe.ingredients()) {
            for (int i = 0; i < Math.max(1, ingredient.count()); i++) {
                ingredients.add(ingredient(ingredient.item(), bare));
            }
        }
        json.add("ingredients", ingredients);
        return craftingResult(json, recipe);
    }

    private static JsonObject cooking(RecipeDefinition recipe, String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.add("ingredient", ingredient(firstIngredient(recipe), Platform.bareRecipeIngredients()));
        json.addProperty("experience", 0.1);
        Platform.simpleResult(json, recipe.result(), 1);
        return json;
    }

    private static JsonObject stonecutting(RecipeDefinition recipe) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:stonecutting");
        json.add("ingredient", ingredient(firstIngredient(recipe), Platform.bareRecipeIngredients()));
        Platform.simpleResult(json, recipe.result(), Math.max(1, recipe.resultCount()));
        return json;
    }

    private static JsonObject smithingTransform(RecipeDefinition recipe) {
        boolean bare = Platform.bareRecipeIngredients();
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:smithing_transform");
        json.add("template", ingredient(recipe.smithing().template(), bare));
        json.add("base", ingredient(recipe.smithing().base(), bare));
        if (recipe.smithing().addition() != null) {
            json.add("addition", ingredient(recipe.smithing().addition(), bare));
        }
        return craftingResult(json, recipe);
    }

    /** Null when the running version wants a trim pattern the template does not name. */
    private static JsonObject smithingTrim(RecipeDefinition recipe) {
        boolean bare = Platform.bareRecipeIngredients();
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:smithing_trim");
        json.add("template", ingredient(recipe.smithing().template(), bare));
        json.add("base", ingredient(recipe.smithing().base(), bare));
        if (recipe.smithing().addition() != null) {
            json.add("addition", ingredient(recipe.smithing().addition(), bare));
        }
        return Platform.smithingTrimPattern(json, recipe.smithing().template()) ? json : null;
    }

    private static JsonObject craftingResult(JsonObject json, RecipeDefinition recipe) {
        JsonObject result = new JsonObject();
        result.addProperty(Platform.recipeResultKey(), recipe.result());
        result.addProperty("count", Math.max(1, recipe.resultCount()));
        json.add("result", result);
        return json;
    }

    private static String firstIngredient(RecipeDefinition recipe) {
        return recipe.ingredients().isEmpty() ? "minecraft:air" : recipe.ingredients().get(0).item();
    }

    /**
     * The Java recipe type and file suffix for each cooker a furnace recipe names.
     *
     * A recipe naming both a campfire and a soul campfire is one Java recipe, because both read the
     * same type. A recipe naming no cooker this knows is written for a furnace, which is where a
     * Bedrock furnace recipe without tags is made.
     */
    private static Map<String, String> cookers(RecipeDefinition recipe) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String tag : recipe.tags()) {
            String type = COOKERS.get(tag);
            if (type != null) found.putIfAbsent(suffixOf(type), type);
        }
        if (found.isEmpty()) found.put("", "minecraft:smelting");
        return found;
    }

    private static String suffixOf(String type) {
        return "minecraft:smelting".equals(type) ? "" : "_" + type.substring("minecraft:".length());
    }

    /** A station whose recipe type Java has none of is named rather than left doing nothing. */
    private static void reportStations(RecipeDefinition recipe) {
        recipe.tags().stream()
            .filter(tag -> !PLAIN_STATIONS.contains(tag) && !COOKERS.containsKey(tag)
                && !"stonecutter".equals(tag))
            .forEach(tag -> AddonEntities.unsupported("recipe station " + tag));
    }

    /**
     * A dyeing recipe per dyeable item, for the versions that decide dyeing from one.
     *
     * Up to 1.21 membership of `#minecraft:dyeable` was the whole answer and there is no recipe to
     * write. The recipe holds no result of its own: the crafting grid keeps the item and changes
     * only the colour it carries.
     */
    private static void writeDyeRecipes(Path root, List<ItemDefinition> items) throws IOException {
        if (!Platform.dyeingIsARecipe()) return;

        for (ItemDefinition item : items) {
            if (item.dyeColour() < 0) continue;

            Names.Identifier id = Names.split(item.identifier(), "minecraft");
            String name = id.namespace() + ":" + id.path();

            JsonObject json = new JsonObject();
            json.addProperty("type", "minecraft:crafting_dye");
            json.addProperty("dye", "#minecraft:dyes");
            json.addProperty("target", name);
            JsonObject result = new JsonObject();
            result.addProperty(Platform.recipeResultKey(), name);
            json.add("result", result);

            write(root.resolve("data").resolve(id.namespace()).resolve(dataDirectory("recipe"))
                .resolve(id.path() + "_dyed.json"), json);
        }
    }

    /**
     * A block state file, a block model and an item model per block a behaviour pack declares.
     *
     * `minecraft:material_instances` names a face, `sides` for the four around it, or `*` for all of
     * them, and the texture it names is a key resolved the same way an item's icon is.
     */
    private static void writeBlockModels(Path root, Map<String, String> textureKeys,
        List<BlockDefinition> blocks, BedrockAssets geometries, Set<String> sprites,
        Map<String, ClientBlockDefinition> appearance) throws IOException {
        for (BlockDefinition block : blocks) {
            Map<String, String> faces = resolveFaces(declaredFaces(block, appearance), textureKeys);
            if (faces.isEmpty()) continue;

            sprites.addAll(faces.values());

            int colon = block.identifier().indexOf(':');
            String namespace = Names.sanitise(colon > 0 ? block.identifier().substring(0, colon) : "minecraft");
            String name = Names.sanitise(colon > 0
                ? block.identifier().substring(colon + 1) : block.identifier());
            Path assets = root.resolve("assets").resolve(namespace);

            writeCubeModel(assets, name, faces, geometryOf(block, geometries));

            JsonObject blockstate = new JsonObject();
            blockstate.add("variants",
                variants(block, namespace, name, textureKeys, assets, geometries, sprites, appearance));
            write(assets.resolve("blockstates").resolve(name + ".json"), blockstate);

            // `minecraft:item_visual` gives the block's item a shape of its own, which Java reaches
            // by pointing the item model at a second block model rather than at the block's.
            BedrockGeometry held = itemGeometryOf(block, geometries);
            String drawnAs = name;
            if (held != null) {
                drawnAs = name + "_in_hand";
                writeCubeModel(assets, drawnAs, faces, held);
            }

            JsonObject item = new JsonObject();
            item.addProperty("parent", namespace + ":block/" + drawnAs);
            write(assets.resolve("models/item").resolve(name + ".json"), item);

            JsonObject reference = new JsonObject();
            reference.addProperty("type", "minecraft:model");
            reference.addProperty("model", namespace + ":block/" + drawnAs);
            JsonObject definition = new JsonObject();
            definition.add("model", reference);
            write(assets.resolve("items").resolve(name + ".json"), definition);

            if (block.components().has("minecraft:flower_pottable")) {
                Map<String, String> embedded = embeddedFaces(block, faces, textureKeys);
                sprites.addAll(embedded.values());
                writePottedModel(assets, namespace, name, embedded,
                    embeddedGeometryOf(block, geometries));
            }
        }
    }

    /**
     * The faces the block wears while it is standing in a pot.
     *
     * `minecraft:embedded_visual` may dress the potted form differently from the block itself, and
     * a pack that names no textures there means the ones the block already wears.
     */
    private static Map<String, String> embeddedFaces(BlockDefinition block,
        Map<String, String> faces, Map<String, String> textureKeys) {
        Map<String, String> declared = resolveFaces(
            materialTextures(embedded(block).get("minecraft:material_instances")), textureKeys);
        return declared.isEmpty() ? faces : declared;
    }

    /** The body of `minecraft:embedded_visual`, empty where the block declares none. */
    private static JsonObject embedded(BlockDefinition block) {
        JsonElement component = block.components().get("minecraft:embedded_visual");
        return component != null && component.isJsonObject()
            ? component.getAsJsonObject() : new JsonObject();
    }

    /** The geometry `minecraft:embedded_visual` draws the potted form from, where it names one. */
    private static BedrockGeometry embeddedGeometryOf(BlockDefinition block, BedrockAssets geometries) {
        String named = BlockDefinition.geometry(embedded(block));
        return named == null ? null : geometries.geometry(named);
    }

    /**
     * The block as it looks standing in a flower pot, which `minecraft:flower_pottable` asks for.
     *
     * `minecraft:embedded_visual` draws that form with a shape of its own. Without one Java's own
     * two crossed quads above the rim wear the block's texture, which is how every potted plant in
     * the game is drawn.
     */
    private static void writePottedModel(Path assets, String namespace, String name,
        Map<String, String> faces, BedrockGeometry geometry) throws IOException {
        if (faces.isEmpty()) return;

        if (geometry != null) {
            writeCubeModel(assets, "potted_" + name, faces, geometry);
        } else {
            JsonObject textures = new JsonObject();
            textures.addProperty("plant", faces.values().iterator().next());

            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:block/flower_pot_cross");
            model.add("textures", textures);
            write(assets.resolve("models/block").resolve("potted_" + name + ".json"), model);
        }

        JsonObject variant = new JsonObject();
        variant.addProperty("model", namespace + ":block/potted_" + name);
        JsonObject variants = new JsonObject();
        variants.add("", variant);
        JsonObject blockstate = new JsonObject();
        blockstate.add("variants", variants);
        write(assets.resolve("blockstates").resolve("potted_" + name + ".json"), blockstate);
    }

    /**
     * The sprite each particle effect is drawn from, named the way the game looks one up.
     *
     * A registered particle type reads `particles/<name>.json` for its sprites, and the sprites
     * themselves come off the particle atlas rather than the block one, so both the definition and
     * an atlas source are written. A pack's own particle texture is what its effect draws.
     */
    private static void writeParticles(BedrockPack pack, Path root, Map<String, String> texturePaths)
        throws IOException {
        Map<String, ParticleDefinition> particles = ParticleDefinition.readAll(pack);
        if (particles.isEmpty()) return;

        JsonArray atlas = new JsonArray();
        for (ParticleDefinition particle : particles.values()) {
            String sprite = particle.texture() == null ? null : texturePaths.get(particle.texture());
            if (sprite == null) continue;

            Names.Identifier id = Names.split(particle.identifier(), "mcaddon");
            JsonArray textures = new JsonArray();
            textures.add(sprite);

            JsonObject definition = new JsonObject();
            definition.add("textures", textures);
            write(root.resolve("assets").resolve(id.namespace()).resolve("particles")
                .resolve(id.path() + ".json"), definition);

            JsonObject source = new JsonObject();
            source.addProperty("type", "minecraft:single");
            source.addProperty("resource", sprite);
            atlas.add(source);
        }
        if (atlas.isEmpty()) return;

        JsonObject sources = new JsonObject();
        sources.add("sources", atlas);
        write(root.resolve("assets/minecraft/atlases/particles.json"), sources);
    }

    /** Functions come from the behaviour packs, which is where a pack keeps its commands. */
    /**
     * A pack's own glyphs, added to the font the game draws ordinary text with.
     *
     * The image goes under the pack's own namespace and the definition under Java's, because a font
     * is named by the game and the providers every pack declares for one name are read together.
     */
    private static void writeFont(BedrockPack pack, Path root, String namespace) throws IOException {
        List<GlyphPage> pages = GlyphPage.readAll(pack);
        JsonArray providers = new JsonArray();

        for (GlyphPage page : pages) {
            JsonObject provider = page.provider(namespace);
            if (provider == null) continue;

            Path target = root.resolve("assets").resolve(namespace).resolve("textures")
                .resolve(page.texturePath());
            Files.createDirectories(target.getParent());
            Files.copy(page.source(), target, StandardCopyOption.REPLACE_EXISTING);
            providers.add(provider);
        }
        if (providers.isEmpty()) return;

        JsonObject font = new JsonObject();
        font.add("providers", providers);
        write(root.resolve("assets/minecraft/font/default.json"), font);
    }

    private static void writeFunctions(Bundle bundle, Path root, String namespace, FunctionNames functions)
        throws IOException {
        Set<String> unsupported = new java.util.LinkedHashSet<>();
        for (BedrockPack behaviour : bundle.behaviours()) {
            Functions.convert(behaviour, root, namespace, functions, unsupported);
        }
        unsupported.forEach(at.ioexception.mcaddon.registry.AddonEntities::unsupported);
    }

    /**
     * One variant per combination of the states a pack declared, with its permutations applied.
     *
     * A permutation's condition is Molang over `q.block_state`, so each combination is evaluated
     * against the values it stands for and the last condition that holds wins, which is the order
     * Bedrock applies them in.
     */
    private static JsonObject variants(BlockDefinition block, String namespace, String name,
        Map<String, String> textureKeys, Path assets, BedrockAssets geometries, Set<String> sprites,
        Map<String, ClientBlockDefinition> appearance) throws IOException {
        JsonObject variants = new JsonObject();
        int[] rotation = BlockComponents.rotation(block);
        if (block.states().isEmpty()) {
            variants.add("", modelReference(namespace, name, rotation));
            return variants;
        }

        List<String> stateNames = new ArrayList<>(block.states().keySet());
        List<List<JsonElement>> values = stateNames.stream().map(block.states()::get).toList();

        int total = values.stream().mapToInt(List::size).reduce(1, (left, right) -> left * right);
        for (int combination = 0; combination < total; combination++) {
            Map<String, JsonElement> chosen = new LinkedHashMap<>();
            List<String> key = new ArrayList<>();

            int remaining = combination;
            for (int index = 0; index < stateNames.size(); index++) {
                List<JsonElement> options = values.get(index);
                int at = remaining % options.size();
                remaining /= options.size();

                String state = stateNames.get(index);
                chosen.put(state, options.get(at));
                key.add(javaStateName(state) + "=" + (AddonBlock.isBoolean(options)
                    ? options.get(at).getAsString() : String.valueOf(at)));
            }

            // A permutation may name a texture the block's own faces never do, and a sprite nothing
            // names is never stitched, so the ones a variant reaches are collected here too.
            Map<String, String> faces = facesFor(block, chosen, textureKeys, appearance);
            sprites.addAll(faces.values());

            String variantName = name + "_" + combination;
            writeCubeModel(assets, variantName, faces, geometryOf(block, geometries));
            variants.add(String.join(",", key), modelReference(namespace, variantName, rotation));
        }
        return variants;
    }

    /**
     * The texture key each face of the block names, from whichever half of the add-on states them.
     *
     * `minecraft:material_instances` on the behaviour side is the newer form and wins where a block
     * carries one. A pack written before that component existed states the same thing in the
     * resource pack's `blocks.json`, and without reading that its blocks have no model at all.
     */
    private static Map<String, String> declaredFaces(BlockDefinition block,
        Map<String, ClientBlockDefinition> appearance) {
        if (!block.faceTextures().isEmpty()) return block.faceTextures();

        ClientBlockDefinition drawn = ClientBlockDefinition.of(appearance, block.identifier());
        return drawn == null ? block.faceTextures() : drawn.faceTextures();
    }

    /** The textures that apply once the permutations whose conditions hold have been applied. */
    private static Map<String, String> facesFor(BlockDefinition block, Map<String, JsonElement> states,
        Map<String, String> textureKeys, Map<String, ClientBlockDefinition> appearance) {
        Map<String, String> faces = resolveFaces(declaredFaces(block, appearance), textureKeys);

        MolangEnvironment environment = new MolangEnvironment();
        environment.setQueries((query, arguments) -> {
            if (!query.equals("block_state") || arguments.isEmpty()) return MolangValue.ZERO;
            JsonElement value = states.get(arguments.get(0).text());
            if (value == null || !value.isJsonPrimitive()) return MolangValue.ZERO;
            if (value.getAsJsonPrimitive().isBoolean()) return MolangValue.of(value.getAsBoolean());
            if (value.getAsJsonPrimitive().isNumber()) return MolangValue.of(value.getAsDouble());
            return MolangValue.of(value.getAsString());
        });

        for (BlockDefinition.Permutation permutation : block.permutations()) {
            if (!MolangParser.parse(permutation.condition()).evaluate(environment).truthy()) continue;

            Map<String, String> overrides =
                materialTextures(permutation.components().get("minecraft:material_instances"));
            faces.putAll(resolveFaces(overrides, textureKeys));
        }
        return faces;
    }

    /** The texture key each face of a `minecraft:material_instances` body names. */
    private static Map<String, String> materialTextures(JsonElement instances) {
        Map<String, String> textures = new LinkedHashMap<>();
        if (instances == null || !instances.isJsonObject()) return textures;

        for (Map.Entry<String, JsonElement> entry : instances.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            String texture = Json.text(entry.getValue().getAsJsonObject(), "texture", null);
            if (texture != null) textures.put(entry.getKey(), texture);
        }
        return textures;
    }

    private static Map<String, String> resolveFaces(Map<String, String> declared,
        Map<String, String> textureKeys) {
        Map<String, String> faces = new TreeMap<>();
        declared.forEach((face, key) -> {
            String texture = textureKeys.getOrDefault(key, vanillaTexture(key));
            if (texture == null) return;
            switch (face) {
                case "*" -> FACES.forEach(name -> faces.put(name, texture));
                case "sides" -> SIDE_FACES.forEach(name -> faces.put(name, texture));
                default -> faces.put(face, texture);
            }
        });
        return faces;
    }

    /**
     * The game's own texture of that name, for a key no loaded pack answers.
     *
     * Bedrock looks a texture key up in the pack's index and then in the game's own, so a behaviour
     * pack naming `stone` without shipping one means the stone everybody already has. Without this
     * such a block has no model at all and the game logs a missing one every time it is drawn.
     */
    private static String vanillaTexture(String key) {
        if (key == null || key.contains(":") || key.contains("/")) return null;

        // A block of that name is the evidence the texture exists: the game draws every one of its
        // own blocks from `block/<name>`, and naming a texture nothing has would only move the
        // complaint from the model to the atlas.
        String sanitised = Names.sanitise(key);
        net.minecraft.resources.Identifier id =
            net.minecraft.resources.Identifier.tryParse("minecraft:" + sanitised);
        return id != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)
            ? "minecraft:block/" + sanitised : null;
    }

    /**
     * The model for one block, from its own geometry where it has one.
     *
     * A pack that names a `minecraft:geometry` drew its block itself, so the cubes come across as
     * model elements; without one the block is a plain cube wearing the pack's textures.
     */
    private static void writeCubeModel(Path assets, String name, Map<String, String> faces,
        BedrockGeometry geometry) throws IOException {
        if (faces.isEmpty()) return;

        JsonObject textures = new JsonObject();
        boolean uniform = faces.values().stream().distinct().count() == 1;
        if (uniform) {
            textures.addProperty("all", faces.values().iterator().next());
        } else {
            faces.forEach(textures::addProperty);
        }

        JsonObject model = new JsonObject();
        if (geometry != null && !uniform) textures.addProperty("all", faces.values().iterator().next());
        JsonArray elements = geometry == null ? null
            : BlockGeometry.elements(geometry, textures.keySet());
        if (elements == null) {
            model.addProperty("parent", uniform ? "minecraft:block/cube_all" : "minecraft:block/cube");
        } else {
            // A model with its own elements needs a particle texture, which `all` is.
            textures.addProperty("particle", "#all");
            model.add("elements", elements);
            JsonObject display = BlockGeometry.display(geometry);
            if (display != null) model.add("display", display);
        }
        model.add("textures", textures);
        write(assets.resolve("models/block").resolve(name + ".json"), model);
    }

    /**
     * The model a variant draws, turned the way a `minecraft:transformation` asks for.
     *
     * A Java variant carries a quarter turn about x and one about y; the rest of what a
     * transformation can say is reported by `AddonBlocks` instead.
     */
    private static JsonObject modelReference(String namespace, String name, int[] rotation) {
        JsonObject variant = new JsonObject();
        variant.addProperty("model", namespace + ":block/" + name);
        if (rotation[0] != 0) variant.addProperty("x", rotation[0]);
        if (rotation[1] != 0) variant.addProperty("y", rotation[1]);
        return variant;
    }

    /** A Bedrock state name is namespaced; a Java property name is not allowed to be. */
    private static String javaStateName(String bedrock) {
        return Names.sanitise(bedrock).replace(':', '_').replace('.', '_').replace('/', '_');
    }

    /** Null when the pack drew its block with a plain cube rather than its own geometry. */
    private static BedrockGeometry geometryOf(BlockDefinition block, BedrockAssets geometries) {
        // `minecraft:unit_cube` asks for the plain block shape whatever geometry sits beside it.
        if (block.components().has("minecraft:unit_cube")) return null;

        return block.geometry() == null ? null : geometries.geometry(block.geometry());
    }

    /** The geometry `minecraft:item_visual` asks the block's item to be drawn from, where it names one. */
    private static BedrockGeometry itemGeometryOf(BlockDefinition block, BedrockAssets geometries) {
        JsonElement component = block.components().get("minecraft:item_visual");
        String named = component == null || !component.isJsonObject() ? null
            : Json.text(component.getAsJsonObject(), "geometry", null);
        return named == null ? null : geometries.geometry(named);
    }

    private static final List<String> FACES = List.of("up", "down", "north", "south", "east", "west");
    private static final List<String> SIDE_FACES = List.of("north", "south", "east", "west");

    /**
     * Loot tables, filed under the path a behaviour pack's `minecraft:loot` names them by.
     *
     * Bedrock's paths are pack relative and keep their `loot_tables/` prefix, so the same string a
     * pack writes resolves as a Java loot table id.
     */
    private static void writeLootTables(Path root, List<LootTableDefinition> tables, String namespace)
        throws IOException {
        String directory = dataDirectory("loot_table");

        for (LootTableDefinition table : tables) {
            write(root.resolve("data").resolve(namespace).resolve(directory)
                .resolve(LootTableDefinition.javaPath(table.path()) + ".json"), table.java());
        }
    }

    /**
     * A block's `minecraft:loot` and `minecraft:tags`, as the data pack files Java reads them from.
     *
     * Java looks a block's drops up under `<namespace>:blocks/<name>` and every supported version
     * makes that key final once the block is registered, so the table the pack named is written a
     * second time under the path the block already reads. The copy declares a block context instead
     * of the entity one every converted table carries, because that is what validation checks a
     * block's own table against.
     */
    private static void writeBlockData(Path root, List<BlockDefinition> blocks,
        List<LootTableDefinition> tables) throws IOException {
        Map<String, List<String>> tagged = new LinkedHashMap<>();

        for (BlockDefinition block : blocks) {
            Names.Identifier id = Names.split(block.identifier(), "minecraft");
            if (id.namespace().isEmpty() || id.path().isEmpty()) continue;

            String named = BlockComponents.loot(block);
            LootTableDefinition table = named == null ? null : lootTable(tables, named);
            if (table != null) {
                JsonObject copy = table.java().deepCopy();
                copy.addProperty("type", "minecraft:block");
                write(root.resolve("data").resolve(id.namespace())
                    .resolve(dataDirectory("loot_table")).resolve("blocks")
                    .resolve(id.path() + ".json"), copy);
            }

            for (String tag : BlockComponents.tags(block)) {
                tagged.computeIfAbsent(tag, name -> new ArrayList<>())
                    .add(id.namespace() + ":" + id.path());
            }
        }

        for (Map.Entry<String, List<String>> tag : tagged.entrySet()) {
            Names.Identifier id = Names.split(tag.getKey(), "minecraft");
            if (id.path().isEmpty()) continue;

            JsonArray values = new JsonArray();
            tag.getValue().forEach(values::add);
            JsonObject json = new JsonObject();
            json.add("values", values);
            write(root.resolve("data").resolve(id.namespace())
                .resolve("tags").resolve(dataDirectory("block"))
                .resolve(id.path() + ".json"), json);
        }
    }

    /**
     * The item tags a pack asked its items to join, as the files Java reads them from.
     *
     * Bedrock states an item's tags on the item; Java keeps the membership in the tag instead, so
     * the same list has to be turned inside out. A tag Java already knows gains the pack's items
     * alongside the vanilla ones, which is how a pack's own item becomes dyeable or a boat fuel.
     */
    private static void writeItemTags(Path root, List<ItemDefinition> items) throws IOException {
        Map<String, List<String>> tagged = new TreeMap<>();
        for (ItemDefinition item : items) {
            Names.Identifier id = Names.split(item.identifier(), "minecraft");
            for (String tag : tagsOf(item)) {
                tagged.computeIfAbsent(tag, name -> new ArrayList<>())
                    .add(id.namespace() + ":" + id.path());
            }
        }

        for (Map.Entry<String, List<String>> tag : tagged.entrySet()) {
            Names.Identifier id = Names.split(tag.getKey(), "minecraft");
            if (id.path().isEmpty()) continue;

            JsonArray values = new JsonArray();
            tag.getValue().forEach(values::add);
            JsonObject json = new JsonObject();
            json.add("values", values);
            write(root.resolve("data").resolve(id.namespace())
                .resolve("tags").resolve(dataDirectory("item"))
                .resolve(id.path() + ".json"), json);
        }
    }

    /**
     * One `jukebox_song` entry per record a pack declares, named after the record itself.
     *
     * The entry holds what Bedrock writes on the item: the sound event to play, how long it runs
     * and what a comparator beside the jukebox reads. The description is the item's own name, which
     * is what a jukebox shows the player while the record turns.
     *
     * Nothing is written on the versions with no such registry. A record is the whole item there,
     * and `AddonItems` builds it as one.
     */
    private static void writeJukeboxSongs(Path root, List<ItemDefinition> items, String namespace,
        List<String> soundEvents) throws IOException {
        if (!Platform.jukeboxSongsAreARegistry()) return;

        for (ItemDefinition item : items) {
            String sound = item.text("minecraft:record", "sound_event");
            if (sound == null) continue;

            Names.Identifier event = Names.soundEvent(sound, namespace);
            String plays = event.namespace() + ":" + event.path();
            // Every field of the entry is required and the game refuses one naming a sound event it
            // has not got, so a record the pack wrote no music for gets no entry at all.
            if (!soundEvents.contains(plays)) continue;

            Names.Identifier id = Names.split(item.identifier(), "minecraft");
            JsonObject description = new JsonObject();
            description.addProperty("translate", "item." + id.namespace() + "." + id.path());

            JsonObject song = new JsonObject();
            song.addProperty("sound_event", plays);
            song.add("description", description);
            song.addProperty("length_in_seconds",
                Math.max(0.1f, item.number("minecraft:record", "duration", 1f)));
            song.addProperty("comparator_output", Math.clamp(
                (int) item.number("minecraft:record", "comparator_signal", 1f), 0, 15));
            write(root.resolve("data").resolve(id.namespace()).resolve("jukebox_song")
                .resolve(id.path() + ".json"), song);
        }
    }

    /**
     * Every tag one item names, from either spelling.
     *
     * A pack writes `minecraft:tags` with a `tags` array, and older packs write the array straight
     * onto the component. Both are the same list of names.
     */
    private static List<String> tagsOf(ItemDefinition item) {
        List<String> tags = new ArrayList<>();
        if (item.dyeColour() >= 0) {
            // A dyeable item joins whichever tag the running version decides dyeing from: up to 1.21
            // one tag answers both the crafting grid and the cauldron, and from 26 the grid reads a
            // recipe instead and the tag is left saying only what a cauldron washes.
            tags.add(Platform.dyeingIsARecipe()
                ? "minecraft:cauldron_can_remove_dye" : "minecraft:dyeable");
        }

        // What a jukebox will take is read off this tag, which is the whole of the check before the
        // song registry existed and is still what says an item belongs in the slot.
        if (item.components().containsKey("minecraft:record")) tags.add("minecraft:music_discs");

        JsonObject component = item.components().get("minecraft:tags");
        if (component == null) return tags;

        JsonElement named = component.has("tags") ? component.get("tags") : component.get("value");
        if (named == null || !named.isJsonArray()) return tags;

        named.getAsJsonArray().forEach(one -> {
            if (one.isJsonPrimitive()) tags.add(one.getAsString());
        });
        return tags;
    }

    /** The converted table the pack-relative path names, or null when the pack ships no such file. */
    private static LootTableDefinition lootTable(List<LootTableDefinition> tables, String named) {
        String wanted = withoutExtension(named.startsWith("./") ? named.substring(2) : named);
        for (LootTableDefinition table : tables) {
            if (withoutExtension(table.path()).equals(wanted)) return table;
        }
        return null;
    }

    /**
     * A data pack directory, spelt the way the running version spells it.
     *
     * 1.21 dropped the plural from every one of them: `loot_tables` became `loot_table` and
     * `tags/blocks` became `tags/block`.
     */
    private static String dataDirectory(String singular) {
        return Platform.singularDataDirectories() ? singular : singular + "s";
    }

    /**
     * One ingredient, as the running version writes one.
     *
     * A name opening with `#` is a tag, which the object form states under its own key and the bare
     * form states with the marker left where it is.
     */
    private static JsonElement ingredient(String item, boolean bare) {
        if (bare) return new com.google.gson.JsonPrimitive(item);
        JsonObject wrapped = new JsonObject();
        wrapped.addProperty(item.startsWith("#") ? "tag" : "item",
            item.startsWith("#") ? item.substring(1) : item);
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
