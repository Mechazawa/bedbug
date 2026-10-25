package at.ioexception.mcaddon.registry;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.convert.ConvertedPack;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.resource.AddonSet;
import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonObject;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The `priority` of the active add-ons' recipes, which decides between recipes matching one grid.
 *
 * Bedrock crafts the recipe with the lowest number, and a recipe that states none has 0. Java takes
 * the first match in load order, so the matches are put in priority order and the sort keeps load
 * order between equal numbers.
 */
public final class RecipePriorities {
    /** Written beside a converted add-on's `data/`: the id of each recipe with a priority other than 0. */
    public static final String FILE = "mcaddon_recipe_priorities.json";

    private static AddonSet readFrom;
    private static Map<String, Integer> read = Map.of();

    private RecipePriorities() {
    }

    public static <T extends net.minecraft.world.item.crafting.Recipe<?>> Stream<RecipeHolder<T>> sorted(
        Stream<RecipeHolder<T>> matches) {
        Map<String, Integer> priorities = ofActive();
        if (priorities.isEmpty()) return matches;

        return matches.sorted(Comparator.comparingInt(
            holder -> priorities.getOrDefault(Platform.idOf(holder.id()).toString(), 0)));
    }

    private static synchronized Map<String, Integer> ofActive() {
        AddonSet active = ActiveAddons.active();
        if (active != readFrom) {
            Map<String, Integer> priorities = new HashMap<>();
            for (ConvertedPack pack : active.converted()) {
                Path file = pack.root().resolve(FILE);
                if (!Files.isRegularFile(file)) continue;

                try {
                    JsonObject json = Json.parseObject(file);
                    json.entrySet().forEach(entry -> priorities.put(entry.getKey(), entry.getValue().getAsInt()));
                } catch (IOException | RuntimeException unreadable) {
                    McAddon.LOGGER.warn("Could not read {}: {}", file, unreadable.toString());
                }
            }
            read = Map.copyOf(priorities);
            readFrom = active;
        }
        return read;
    }
}
