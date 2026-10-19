package at.ioexception.mcaddon.state;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.resource.AddonSet;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which add-ons a world plays with, kept in `data/mcaddon-addons.json` inside the world.
 *
 * An installed add-on the list does not name is off, as in Bedrock, so an add-on installed after a
 * world was made stays out of it. A listed add-on that is no longer installed stays in the list, so
 * installing it again restores its choice.
 */
public record WorldAddonList(List<Entry> addons) {
    private static final int FORMAT = 1;
    private static final Gson GSON = new Gson();

    public record Entry(UUID uuid, String name, boolean on) {}

    public WorldAddonList {
        addons = List.copyOf(addons);
    }

    private static Path fileOf(Path world) {
        return world.resolve("data").resolve("mcaddon-addons.json");
    }

    /** The list of a world, or empty for a world that has none or whose file cannot be read. */
    public static Optional<WorldAddonList> read(Path world) {
        Path file = fileOf(world);
        if (!Files.isRegularFile(file)) return Optional.empty();

        try {
            List<Entry> entries = new ArrayList<>();
            Json.list(Json.parseObject(file), "addons").forEach(element -> {
                JsonObject entry = element.getAsJsonObject();
                entries.add(new Entry(UUID.fromString(Json.text(entry, "uuid", "")),
                    Json.text(entry, "name", ""), Json.bool(entry, "on", false)));
            });
            return Optional.of(new WorldAddonList(entries));
        } catch (IOException | RuntimeException error) {
            McAddon.LOGGER.error("Could not read {}; treating the world as having no add-on list.", file, error);
            return Optional.empty();
        }
    }

    /** Every installed add-on on, for a world that has no list yet. */
    public static WorldAddonList allOn(AddonSet installed) {
        return new WorldAddonList(installed.converted().stream()
            .map(one -> new Entry(one.pack().manifest().uuid(), one.displayName(), true))
            .toList());
    }

    public void write(Path world) {
        JsonArray entries = new JsonArray();
        addons.forEach(entry -> {
            JsonObject json = new JsonObject();
            json.addProperty("uuid", entry.uuid().toString());
            json.addProperty("name", entry.name());
            json.addProperty("on", entry.on());
            entries.add(json);
        });
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("addons", entries);

        Path file = fileOf(world);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException error) {
            McAddon.LOGGER.error("Could not write {}.", file, error);
        }
    }

    /** This list with one add-on set on or off; an add-on it does not name is appended under an empty name. */
    public WorldAddonList with(UUID uuid, boolean on) {
        List<Entry> changed = new ArrayList<>(addons);
        int index = changed.stream().map(Entry::uuid).toList().indexOf(uuid);
        if (index < 0) {
            changed.add(new Entry(uuid, "", on));
        } else {
            changed.set(index, new Entry(uuid, changed.get(index).name(), on));
        }
        return new WorldAddonList(changed);
    }

    /** The installed set cut down to the add-ons this list names as on. */
    public AddonSet resolve(AddonSet installed) {
        Set<UUID> on = addons.stream().filter(Entry::on).map(Entry::uuid).collect(Collectors.toSet());
        return installed.only(one -> on.contains(one.pack().manifest().uuid()));
    }
}
