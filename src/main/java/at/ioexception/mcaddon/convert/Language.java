package at.ioexception.mcaddon.convert;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.ComponentOrientation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One locale a pack ships text for, as the language Java's own language screen lists.
 *
 * `texts/languages.json` is the list of locales the pack has text for and `texts/language_names.json`
 * pairs each with the name it is shown under, written as one string in the form
 * `Deutsch (Deutschland)`. Java asks for those two halves apart, as a name and a region, in the
 * `language` section of `pack.mcmeta`, and a locale the game carries no entry of its own for is
 * reachable only through that section: without it the `.lang` this mod converted is text nobody can
 * select.
 */
public record Language(String code, String name, String region, boolean bidirectional) {

    /** The list of locales, and the names to show them under, from every half of the add-on. */
    public static List<Language> readAll(List<BedrockPack> packs) {
        Map<String, String> names = new LinkedHashMap<>();
        List<String> codes = new ArrayList<>();
        for (BedrockPack pack : packs) {
            names.putAll(displayNames(pack));
            for (String code : locales(pack)) {
                if (!codes.contains(code)) codes.add(code);
            }
        }

        List<Language> languages = new ArrayList<>();
        for (String code : codes) languages.add(of(code, names.get(code)));
        return languages;
    }

    /**
     * The name and the region Java asks for, taken out of the one string Bedrock writes.
     *
     * A name with no bracketed part behind it leaves the region as the locale itself, because Java
     * shows the two beside each other and an empty region reads as a missing entry rather than as a
     * language without one.
     */
    private static Language of(String code, String shown) {
        String java = code.toLowerCase(Locale.ROOT);
        String display = shown == null || shown.isBlank() ? code : shown;

        int bracket = display.indexOf('(');
        String name = bracket < 0 ? display : display.substring(0, bracket).trim();
        String region = bracket < 0 ? code
            : display.substring(bracket + 1).replace(")", "").trim();

        return new Language(java, name, region.isBlank() ? code : region, rightToLeft(java));
    }

    /** Arabic, Hebrew, Persian and Urdu are read the other way, which Java draws differently. */
    private static boolean rightToLeft(String code) {
        Locale locale = Locale.forLanguageTag(code.replace('_', '-'));
        return ComponentOrientation.getOrientation(locale) == ComponentOrientation.RIGHT_TO_LEFT;
    }

    private static List<String> locales(BedrockPack pack) {
        List<String> codes = new ArrayList<>();
        for (JsonElement element : array(pack, "texts/languages.json")) {
            if (element.isJsonPrimitive()) codes.add(element.getAsString());
        }
        return codes;
    }

    /** Each entry is a pair, the locale first and the name it is shown under second. */
    private static Map<String, String> displayNames(BedrockPack pack) {
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonElement element : array(pack, "texts/language_names.json")) {
            if (!element.isJsonArray()) continue;
            JsonArray pair = element.getAsJsonArray();
            if (pair.size() >= 2) names.put(pair.get(0).getAsString(), pair.get(1).getAsString());
        }
        return names;
    }

    private static JsonArray array(BedrockPack pack, String path) {
        Path file = pack.file(path);
        if (!pack.has(path)) return new JsonArray();

        try {
            return Json.parseArray(file);
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.warn("Could not read {} in {}: {}", path, pack.origin(), error.toString());
            return new JsonArray();
        }
    }

    /** The entry Java reads out of `pack.mcmeta`, under the locale as its key. */
    public JsonObject declaration() {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("region", region);
        entry.addProperty("bidirectional", bidirectional);
        return entry;
    }
}
