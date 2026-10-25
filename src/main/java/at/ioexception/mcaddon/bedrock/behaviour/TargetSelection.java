package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.server.ServerHooks;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * What an `entity_types` list on a goal says the goal is about.
 *
 * Bedrock names the target by family and states how far the goal reaches inside the same list, so a
 * goal read without it hunts or flees whatever the mod guessed, at whatever distance it guessed.
 * Java picks its targets by class, so the kind is the widest class the named families map onto,
 * and each entry's own filters then decide about a candidate of that class.
 */
public record TargetSelection(Class<? extends LivingEntity> kind, double maxDistance, List<Entry> entries) {
    /** One entry of the list: the filters naming who it is about, and how far it reaches. */
    public record Entry(Filter filters, double maxDistance) {
    }

    private static final List<Class<? extends LivingEntity>> ORDER =
        List.of(Player.class, Monster.class, Animal.class, Mob.class, LivingEntity.class);

    /**
     * The narrowest kind the list names, and the furthest reach any of its entries allows.
     *
     * A goal listing several families needs one Java class covering all of them, which is the widest
     * of the ones named; a goal listing none reaches the fallback rather than nothing.
     */
    public static TargetSelection from(JsonObject goal, Class<? extends LivingEntity> fallbackKind,
        double fallbackDistance) {
        return fromEntries(goal == null ? List.of()
            : at.ioexception.mcaddon.util.Json.list(goal, "entity_types"), fallbackKind, fallbackDistance);
    }

    /** The same for an `entity_types` list a goal has already taken out of its component. */
    public static TargetSelection fromEntries(List<JsonElement> listed, Class<? extends LivingEntity> fallbackKind,
        double fallbackDistance) {
        List<Class<? extends LivingEntity>> kinds = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        double distance = 0;
        for (JsonElement element : listed) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();

            double reach = ServerEntityDefinition.number(entry, "max_dist", 0);
            distance = Math.max(distance, reach);
            families(entry.get("filters"), kinds);
            entries.add(new Entry(Filter.parse(entry.get("filters")), reach));
        }

        return new TargetSelection(
            kinds.isEmpty() ? fallbackKind : kinds.stream().max(TargetSelection::width).orElseThrow(),
            distance > 0 ? distance : fallbackDistance, entries);
    }

    /**
     * Whether the candidate is one the list is about, inside the reach of the entry naming it.
     *
     * The entry's own filters decide, asked about the candidate as `other`, which is how a pack
     * names a family Java has no class for: a goal about villagers is about villagers rather than
     * about whatever class the families closest to it map to. A list with no entries is about
     * anything of the kind the goal was given.
     */
    public boolean accepts(Entity self, LivingEntity candidate) {
        if (entries.isEmpty()) return kind.isInstance(candidate);

        double distance = self.distanceTo(candidate);
        Filter.Context context = new Filter.Context(self, candidate, candidate, ServerHooks.state());
        return entries.stream().anyMatch(entry ->
            (entry.maxDistance() <= 0 || distance <= entry.maxDistance()) && entry.filters().matches(context));
    }

    private static int width(Class<? extends LivingEntity> left, Class<? extends LivingEntity> right) {
        return Integer.compare(ORDER.indexOf(left), ORDER.indexOf(right));
    }

    /** Walks a filter tree for the families it tests, which is where the target's kind is stated. */
    private static void families(JsonElement filters, List<Class<? extends LivingEntity>> into) {
        if (filters == null) return;
        if (filters.isJsonArray()) {
            filters.getAsJsonArray().forEach(child -> families(child, into));
            return;
        }
        if (!filters.isJsonObject()) return;

        JsonObject test = filters.getAsJsonObject();
        for (String group : List.of("any_of", "all_of", "none_of")) {
            families(test.get(group), into);
        }

        String operator = at.ioexception.mcaddon.util.Json.text(test, "operator", "==");
        if (!"is_family".equals(at.ioexception.mcaddon.util.Json.text(test, "test", null))
            || operator.startsWith("!")) {
            return;
        }
        Class<? extends LivingEntity> kind = kindOf(at.ioexception.mcaddon.util.Json.text(test, "value", ""));
        if (kind != null) into.add(kind);
    }

    private static Class<? extends LivingEntity> kindOf(String family) {
        return switch (family) {
            case "player" -> Player.class;
            case "monster", "undead", "zombie", "skeleton", "illager", "pillager" -> Monster.class;
            case "animal", "pig", "cow", "sheep", "chicken", "rabbit", "horse", "wolf" -> Animal.class;
            case "mob", "creature" -> Mob.class;
            default -> null;
        };
    }
}
