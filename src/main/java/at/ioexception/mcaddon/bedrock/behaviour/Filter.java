package at.ioexception.mcaddon.bedrock.behaviour;

import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bedrock's entity filters, which are a small expression language of their own and nothing to do
 * with Molang.
 *
 * A filter is a tree of `all_of`, `any_of` and `none_of` over leaves that name a test, a subject to
 * run it against, an operator and a value. A test this mod does not implement reads as false and is
 * counted, so a pack whose condition never holds says why.
 */
public sealed interface Filter {
    boolean matches(Context context);

    /** Everything a test can be asked about: who is being tested, and who is doing the testing. */
    record Context(Entity self, Entity other, Entity target, AddonState state) {
        public Entity subject(String name) {
            return switch (name) {
                case "other", "damager", "player", "parent" -> other;
                case "target" -> target;
                default -> self;
            };
        }
    }

    record All(List<Filter> parts) implements Filter {
        @Override
        public boolean matches(Context context) {
            return parts.stream().allMatch(part -> part.matches(context));
        }
    }

    record Any(List<Filter> parts) implements Filter {
        @Override
        public boolean matches(Context context) {
            return parts.stream().anyMatch(part -> part.matches(context));
        }
    }

    record None(List<Filter> parts) implements Filter {
        @Override
        public boolean matches(Context context) {
            return parts.stream().noneMatch(part -> part.matches(context));
        }
    }

    /** One test, with the domain a few of them take (an equipment slot, a property name). */
    record Test(String name, String subject, String operator, String domain, JsonElement value)
        implements Filter {

        @Override
        public boolean matches(Context context) {
            Entity entity = context.subject(subject);
            if (entity == null) return false;

            Boolean outcome = evaluate(entity, context);
            if (outcome == null) {
                AddonEntities.unsupported("filter " + name);
                return false;
            }
            return negated() != outcome;
        }

        /** Null when the test is not implemented, which is different from a test that came out false. */
        private Boolean evaluate(Entity entity, Context context) {
            return switch (name) {
                case "is_family" -> family(entity, text());
                case "has_tag" -> Platform.tagsOf(entity).contains(text());
                case "has_target" -> entity instanceof net.minecraft.world.entity.Mob mob
                    && mob.getTarget() != null;
                case "is_baby" -> entity instanceof LivingEntity living && living.isBaby();
                case "is_sneaking" -> entity.isShiftKeyDown();
                case "is_sleeping" -> entity instanceof LivingEntity living && living.isSleeping();
                case "is_riding" -> entity.isPassenger();
                case "is_leashed" -> entity instanceof net.minecraft.world.entity.Mob mob && mob.isLeashed();
                case "is_moving" -> entity.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4;
                case "on_ground" -> entity.onGround();
                case "in_water" -> entity.isInWater();
                case "in_lava" -> entity.isInLava();
                case "is_visible" -> !entity.isInvisible();
                case "is_underground" -> entity.getY() < entity.level().getSeaLevel() - 8;
                case "is_daytime" -> Platform.dayTime(entity.level()) % 24000L < 12000L;
                case "is_difficulty" -> entity.level().getDifficulty().name().equalsIgnoreCase(text());
                case "actor_health" -> compare(entity instanceof LivingEntity living ? living.getHealth() : 0);
                case "light_level" -> compare(entity.level().getMaxLocalRawBrightness(entity.blockPosition()));
                case "hourly_clock_time" -> compare(Platform.dayTime(entity.level()) % 24000L);
                case "weather_at_position" -> weather(entity);
                case "distance_to_nearest_player" -> compare(distanceToNearestPlayer(entity));
                case "has_mob_effect" -> effect(entity);
                case "has_equipment" -> equipment(entity);
                case "bool_property" -> property(entity, context) instanceof Boolean flag
                    ? flag == (value == null || value.getAsBoolean()) : false;
                case "int_property", "float_property" -> property(entity, context) instanceof Number number
                    && compare(number.doubleValue());
                case "enum_property" -> String.valueOf(property(entity, context)).equals(text());
                case "has_property" -> property(entity, context) != null;
                case "has_component" -> component(entity);
                default -> null;
            };
        }

        /**
         * `!=` on a Bedrock filter negates the whole test rather than comparing, because most tests
         * have no value to compare against.
         */
        private boolean negated() {
            return "!=".equals(operator) || "not".equals(operator) || "<>".equals(operator);
        }

        private boolean compare(double actual) {
            double expected = value != null && value.isJsonPrimitive() ? value.getAsDouble() : 0;
            return switch (operator == null ? "==" : operator) {
                case "<" -> actual < expected;
                case "<=", "=<" -> actual <= expected;
                case ">" -> actual > expected;
                case ">=", "=>" -> actual >= expected;
                // `!=` is handled by negation, so an equality test is what is left.
                default -> actual == expected;
            };
        }

        private String text() {
            return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
        }

        private Object property(Entity entity, Context context) {
            String property = domain == null ? text() : domain;
            return context.state() == null ? null : context.state().property(entity.getUUID(), property);
        }

        private static boolean family(Entity entity, String family) {
            if (entity instanceof Player) return family.equals("player") || family.equals("mob");
            return AddonEntities.definitionOf(entity.getType()) != null
                && AddonEntities.definitionOf(entity.getType()).families().contains(family);
        }

        private boolean weather(Entity entity) {
            return switch (text()) {
                case "rain" -> entity.level().isRaining();
                case "thunder" -> entity.level().isThundering();
                case "clear" -> !entity.level().isRaining();
                default -> false;
            };
        }

        private static double distanceToNearestPlayer(Entity entity) {
            Player nearest = entity.level().getNearestPlayer(entity, 128);
            return nearest == null ? Double.MAX_VALUE : Math.sqrt(nearest.distanceToSqr(entity));
        }

        private boolean effect(Entity entity) {
            Identifier id = Identifier.tryParse(text().contains(":") ? text() : "minecraft:" + text());
            return id != null && entity instanceof LivingEntity living && Platform.hasEffect(living, id);
        }

        private boolean equipment(Entity entity) {
            if (!(entity instanceof LivingEntity living)) return false;
            ItemStack held = living.getItemBySlot(slot());
            if (held.isEmpty()) return text().isEmpty();

            Identifier id = BuiltInRegistries.ITEM.getKey(held.getItem());
            String wanted = text();
            return id.toString().equals(wanted) || id.getPath().equals(wanted);
        }

        private EquipmentSlot slot() {
            return switch (domain == null ? "any" : domain.toLowerCase(Locale.ROOT)) {
                case "head", "armor.head" -> EquipmentSlot.HEAD;
                case "chest", "armor.chest" -> EquipmentSlot.CHEST;
                case "legs", "armor.legs" -> EquipmentSlot.LEGS;
                case "feet", "armor.feet" -> EquipmentSlot.FEET;
                case "offhand" -> EquipmentSlot.OFFHAND;
                default -> EquipmentSlot.MAINHAND;
            };
        }

        private boolean component(Entity entity) {
            ServerEntityDefinition definition = AddonEntities.definitionOf(entity.getType());
            return definition != null && definition.components().containsKey(text());
        }
    }

    /** A filter that is always true, which is what an empty or unreadable block becomes. */
    record Always() implements Filter {
        @Override
        public boolean matches(Context context) {
            return true;
        }
    }

    static Filter parse(JsonElement json) {
        if (json == null || json.isJsonNull()) return new Always();
        if (json.isJsonArray()) return new All(parseAll(json));

        if (!json.isJsonObject()) return new Always();
        JsonObject body = json.getAsJsonObject();

        if (body.has("all_of")) return new All(parseAll(body.get("all_of")));
        if (body.has("any_of")) return new Any(parseAll(body.get("any_of")));
        if (body.has("none_of")) return new None(parseAll(body.get("none_of")));
        if (!body.has("test")) return new Always();

        return new Test(
            body.get("test").getAsString(),
            body.has("subject") ? body.get("subject").getAsString() : "self",
            body.has("operator") ? body.get("operator").getAsString() : null,
            body.has("domain") ? body.get("domain").getAsString() : null,
            body.get("value"));
    }

    private static List<Filter> parseAll(JsonElement json) {
        List<Filter> parts = new ArrayList<>();
        if (json.isJsonArray()) {
            json.getAsJsonArray().forEach(part -> parts.add(parse(part)));
        } else {
            parts.add(parse(json));
        }
        return parts;
    }

    /** Convenience for the common case of testing one entity against itself on a server. */
    static boolean matches(Filter filter, Entity entity, ServerLevel level, AddonState state) {
        return filter.matches(new Context(entity, entity, null, state));
    }
}
