package at.ioexception.mcaddon.server;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The `hasitem` arguments of one translated line, answered for one run of it.
 *
 * Java's selector has no way to ask about an inventory, and its options stay the ones Java defines.
 * Each `hasitem` argument becomes a tag instead: every entity carrying what the argument asks for is
 * given the tag before the line runs and loses it once the line and everything it started have run.
 * The tag names the run, so a line a function runs while the line calling that function is still
 * open cannot take away a tag the caller is still selecting by.
 */
public final class CarriedItems {
    /** Where the translation writes the argument, which is inside a selector's brackets. */
    private static final Pattern ARGUMENT = Pattern.compile("(?<=[\\[,])hasitem=");

    private static final AtomicInteger RUNS = new AtomicInteger();

    /** Runs whose tags are still on entities, so a run cut short by the command limit is still undone. */
    private static final Set<CarriedItems> OPEN = new LinkedHashSet<>();

    private final String line;
    private final Map<String, List<HasItem>> asked;
    private final List<Entity> tagged = new ArrayList<>();

    private CarriedItems(String line, Map<String, List<HasItem>> asked) {
        this.line = line;
        this.asked = asked;
    }

    /** The line with each `hasitem` argument asked through a tag of this run, or null when it has none. */
    public static CarriedItems tryFrom(String line) throws CommandSyntaxException {
        Matcher matcher = ARGUMENT.matcher(line);
        if (!matcher.find()) return null;

        int run = RUNS.incrementAndGet();
        Map<String, List<HasItem>> asked = new LinkedHashMap<>();
        StringBuilder written = new StringBuilder();
        int at = 0;
        do {
            StringReader reader = new StringReader(line);
            reader.setCursor(matcher.end());
            String tag = "mcaddon.hasitem." + run + "." + asked.size();
            asked.put(tag, HasItem.readAll(reader));
            written.append(line, at, matcher.start()).append("tag=").append(tag);
            at = reader.getCursor();
        } while (matcher.find(at));

        return new CarriedItems(written.append(line.substring(at)).toString(), asked);
    }

    public String line() {
        return line;
    }

    public CarriedItems tag(MinecraftServer server) {
        synchronized (OPEN) {
            OPEN.add(this);
        }
        for (ServerLevel level : server.getAllLevels()) {
            level.getAllEntities().forEach(this::tagIfCarrying);
        }
        return this;
    }

    public void finish() {
        synchronized (OPEN) {
            if (!OPEN.remove(this)) return;
        }
        tagged.forEach(entity -> asked.keySet().forEach(entity::removeTag));
        tagged.clear();
    }

    /** Takes back the tags of every run that has not finished, which is asked once a tick. */
    public static void finishAll() {
        List<CarriedItems> open;
        synchronized (OPEN) {
            open = List.copyOf(OPEN);
        }
        open.forEach(CarriedItems::finish);
    }

    private void tagIfCarrying(Entity entity) {
        List<String> answered = asked.entrySet().stream()
            .filter(argument -> argument.getValue().stream().allMatch(item -> item.carriedBy(entity)))
            .map(Map.Entry::getKey)
            .toList();
        if (answered.isEmpty()) return;

        answered.forEach(entity::addTag);
        tagged.add(entity);
    }
}
