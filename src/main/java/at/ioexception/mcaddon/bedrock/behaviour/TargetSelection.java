package at.ioexception.mcaddon.bedrock.behaviour;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
 * Java picks its targets by class, and a family maps onto one closely enough to keep a pack's
 * meaning: a goal about players stays about players and one about monsters stays about monsters.
 */
public record TargetSelection(Class<? extends LivingEntity> kind, double maxDistance) {
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
        if (goal == null) return new TargetSelection(fallbackKind, fallbackDistance);

        List<Class<? extends LivingEntity>> kinds = new ArrayList<>();
        double distance = 0;
        for (JsonElement element : at.ioexception.mcaddon.util.Json.list(goal, "entity_types")) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();

            distance = Math.max(distance, ServerEntityDefinition.number(entry, "max_dist", 0));
            families(entry.get("filters"), kinds);
        }

        return new TargetSelection(
            kinds.isEmpty() ? fallbackKind : kinds.stream().max(TargetSelection::width).orElseThrow(),
            distance > 0 ? distance : fallbackDistance);
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
