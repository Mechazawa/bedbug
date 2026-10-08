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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The selector arguments of one translated line that Java's selector has no form of, answered for
 * one run of it.
 *
 * Java's selector cannot ask about an inventory, an input permission or an entity property, and its
 * options stay the ones Java defines. Each `hasitem`, `haspermission` and `has_property` argument
 * becomes a tag instead: every entity answering the argument is given the tag before the line runs
 * and loses it once the line and everything it started have run. The tag names the run, so a line a
 * function runs while the line calling that function is still open cannot take away a tag the caller
 * is still selecting by.
 */
public final class SelectorTags {
    /** Where the translation writes an argument, which is inside a selector's brackets. */
    private static final Pattern ARGUMENT = Pattern.compile("(?<=[\\[,])(hasitem|haspermission|has_property)=");

    private static final AtomicInteger RUNS = new AtomicInteger();

    /** Runs whose tags are still on entities, so a run cut short by the command limit is still undone. */
    private static final Set<SelectorTags> OPEN = new LinkedHashSet<>();

    private final String line;
    private final Map<String, Predicate<Entity>> asked;
    private final List<Entity> tagged = new ArrayList<>();

    private SelectorTags(String line, Map<String, Predicate<Entity>> asked) {
        this.line = line;
        this.asked = asked;
    }

    /** The line with each such argument asked through a tag of this run, or null when it has none. */
    public static SelectorTags tryFrom(String line) throws CommandSyntaxException {
        Matcher matcher = ARGUMENT.matcher(line);
        if (!matcher.find()) return null;

        int run = RUNS.incrementAndGet();
        Map<String, Predicate<Entity>> asked = new LinkedHashMap<>();
        StringBuilder written = new StringBuilder();
        int at = 0;
        do {
            StringReader reader = new StringReader(line);
            reader.setCursor(matcher.end());
            String tag = "mcaddon." + matcher.group(1) + "." + run + "." + asked.size();
            asked.put(tag, read(matcher.group(1), reader));
            written.append(line, at, matcher.start()).append("tag=").append(tag);
            at = reader.getCursor();
        } while (matcher.find(at));

        return new SelectorTags(written.append(line.substring(at)).toString(), asked);
    }

    private static Predicate<Entity> read(String argument, StringReader reader) throws CommandSyntaxException {
        return switch (argument) {
            case "haspermission" -> HasPermission.read(reader);
            case "has_property" -> HasProperty.read(reader);
            default -> {
                List<HasItem> items = HasItem.readAll(reader);
                yield entity -> items.stream().allMatch(item -> item.carriedBy(entity));
            }
        };
    }

    public String line() {
        return line;
    }

    public SelectorTags tag(MinecraftServer server) {
        synchronized (OPEN) {
            OPEN.add(this);
        }
        for (ServerLevel level : server.getAllLevels()) {
            level.getAllEntities().forEach(this::tagIfAnswering);
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
        List<SelectorTags> open;
        synchronized (OPEN) {
            open = List.copyOf(OPEN);
        }
        open.forEach(SelectorTags::finish);
    }

    private void tagIfAnswering(Entity entity) {
        List<String> answered = asked.entrySet().stream()
            .filter(argument -> argument.getValue().test(entity))
            .map(Map.Entry::getKey)
            .toList();
        if (answered.isEmpty()) return;

        answered.forEach(entity::addTag);
        tagged.add(entity);
    }
}
