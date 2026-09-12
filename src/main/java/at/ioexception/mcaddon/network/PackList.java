package at.ioexception.mcaddon.network;

import at.ioexception.mcaddon.pack.BedrockPack;
import at.ioexception.mcaddon.pack.PackSet;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The packs one side has, as the other side needs to see them.
 *
 * A server and its clients have to be given the same add-ons by hand, because a pack is a file on
 * disk and not something a server can hand out. Comparing the two lists is what turns "the models
 * are invisible and nobody knows why" into a line that names the missing pack.
 */
public record PackList(List<Entry> packs) {
    public record Entry(String uuid, String version, String name) {
    }

    public static PackList of(PackSet packs) {
        List<Entry> entries = new ArrayList<>();
        for (BedrockPack pack : packs.packs()) {
            entries.add(new Entry(pack.manifest().uuid().toString(),
                pack.manifest().version().toString(), pack.displayName()));
        }
        return new PackList(entries);
    }

    public String encode() {
        JsonArray array = new JsonArray();
        for (Entry entry : packs) {
            JsonObject json = new JsonObject();
            json.addProperty("uuid", entry.uuid());
            json.addProperty("version", entry.version());
            json.addProperty("name", entry.name());
            array.add(json);
        }
        return array.toString();
    }

    public static PackList decode(String encoded) {
        List<Entry> entries = new ArrayList<>();
        JsonElement parsed = JsonParser.parseString(encoded);
        if (parsed.isJsonArray()) {
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject json = element.getAsJsonObject();
                entries.add(new Entry(Json.text(json, "uuid", ""),
                    Json.text(json, "version", ""), Json.text(json, "name", "")));
            }
        }
        return new PackList(entries);
    }

    /** The packs in this list that the other side has not got, by uuid and version together. */
    public List<Entry> missingFrom(PackList other) {
        Set<String> present = new LinkedHashSet<>();
        for (Entry entry : other.packs()) present.add(entry.uuid() + "@" + entry.version());

        List<Entry> missing = new ArrayList<>();
        for (Entry entry : packs) {
            if (!present.contains(entry.uuid() + "@" + entry.version())) missing.add(entry);
        }
        return missing;
    }
}
