package at.ioexception.mcaddon.script.surface;

import at.ioexception.mcaddon.bedrock.behaviour.ServerEntityDefinition;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.registry.AddonBlocks;
import at.ioexception.mcaddon.registry.AddonEntities;
import at.ioexception.mcaddon.script.ScriptCoverage;
import at.ioexception.mcaddon.server.EntityPropertySync;
import at.ioexception.mcaddon.state.AddonState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.graalvm.polyglot.HostAccess;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The parts of `Entity` that need more than a handle and a number.
 *
 * The ray casts, the standing-on lookup and the bounding box each answer with several fields at once,
 * so each returns JSON text the guest parses: a Java map or record reaches a script with none of its
 * members on it.
 */
public final class EntityMembers extends Surface {
    /** How many blocks a filtered ray may reject before it gives up rather than walking the world. */
    private static final int FILTER_STEPS = 16;

    /** Both halves of the food bar run to this, which is the ceiling Bedrock reports for either. */
    private static final int FULL_FOOD_BAR = 20;

    private final AddonState state;

    public EntityMembers(MinecraftServer server, ScriptCoverage coverage, AddonState state) {
        super(server, coverage);
        this.state = state;
    }

    @HostAccess.Export
    public boolean isSprinting(String id) {
        Entity entity = entity(id);
        return entity != null && entity.isSprinting();
    }

    /**
     * The key the entity's name is translated under.
     *
     * Bedrock's own key keeps the colon of the identifier and ends in `.name`. A pack's `.lang` file
     * is rewritten into Java's spelling when the pack is converted, so this is the spelling that
     * finds the translation the pack shipped.
     */
    @HostAccess.Export
    public String localizationKey(String id) {
        Entity entity = entity(id);
        return entity == null ? "" : entity.getType().getDescriptionId();
    }

    /** Bedrock's knockback replaces the movement rather than halving what is already there. */
    @HostAccess.Export
    public void applyKnockback(String id, double forceX, double forceZ, double verticalStrength) {
        Entity entity = entity(id);
        if (entity == null) return;
        entity.setDeltaMovement(forceX, verticalStrength, forceZ);
        entity.hurtMarked = true;
    }

    @HostAccess.Export
    public boolean setOnFire(String id, int seconds) {
        Entity entity = entity(id);
        if (entity == null || seconds <= 0) return false;
        ignite(entity, seconds);
        return true;
    }

    /** 1.21 renamed the setter and moved it from whole seconds to a float. */
    private static void ignite(Entity entity, int seconds) {
        //? if >=1.21 {
        entity.igniteForSeconds(seconds);
        //?} else {
        /*entity.setSecondsOnFire(seconds);
        *///?}
    }

    /** False when there was no fire to put out, which is the answer `extinguishFire` reports. */
    @HostAccess.Export
    public boolean extinguishFire(String id) {
        Entity entity = entity(id);
        if (entity == null || entity.getRemainingFireTicks() <= 0) return false;
        entity.clearFire();
        return true;
    }

    /** The collision box as a centre and a half size, which is the shape Bedrock's `AABB` carries. */
    @HostAccess.Export
    public double[] boundingBox(String id) {
        Entity entity = entity(id);
        if (entity == null) return new double[] {0, 0, 0, 0, 0, 0};

        AABB box = entity.getBoundingBox();
        Vec3 centre = box.getCenter();
        return new double[] {centre.x, centre.y, centre.z,
            box.getXsize() / 2, box.getYsize() / 2, box.getZsize() / 2};
    }

    /**
     * The solid blocks directly under the entity's feet, as `[x, y, z]` each.
     *
     * Empty while the entity is in the air, the way Bedrock answers for one that is jumping or
     * flying. A block with no collision shape is not stood on, which leaves out pressure plates and
     * everything else a foot passes through.
     */
    @HostAccess.Export
    public String blocksStandingOn(String id, String optionsJson) {
        Entity entity = entity(id);
        JsonArray found = new JsonArray();
        if (entity == null || !(entity.level() instanceof ServerLevel level) || !entity.onGround()) {
            return found.toString();
        }

        JsonObject options = JsonParser.parseString(optionsJson).getAsJsonObject();
        BlockFilter filter = BlockFilter.parse(options.getAsJsonObject("blockFilter"));
        boolean ignoreThin = flag(options, "ignoreThinBlocks");

        AABB box = entity.getBoundingBox();
        Set<BlockPos> standing = new LinkedHashSet<>();
        for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                BlockPos under = supporting(level, new BlockPos(x, Mth.floor(box.minY - 0.02), z), ignoreThin);
                if (under != null && filter.keeps(level.getBlockState(under))) standing.add(under);
            }
        }

        standing.stream().map(EntityMembers::triple).forEach(found::add);
        return found.toString();
    }

    /** The block that carries the weight in this column, stepping past a carpet when asked to. */
    private static BlockPos supporting(ServerLevel level, BlockPos start, boolean ignoreThin) {
        double height = level.getBlockState(start).getCollisionShape(level, start).isEmpty()
            ? 0 : level.getBlockState(start).getCollisionShape(level, start).max(Direction.Axis.Y);
        if (height <= 0) return null;
        if (!ignoreThin || height > 0.2) return start;

        BlockPos below = start.below();
        return level.getBlockState(below).getCollisionShape(level, below).isEmpty() ? null : below;
    }

    /**
     * The first block the entity is looking at, or null when the ray reaches its limit.
     *
     * The face comes back as the index of its direction so the guest can name it, and the hit point
     * is measured from the block's own corner the way Bedrock measures it. A filtered ray carries on
     * past a block the filter rejects instead of stopping there.
     */
    @HostAccess.Export
    public String blockFromViewDirection(String id, String optionsJson) {
        Entity entity = entity(id);
        if (entity == null) return null;

        JsonObject options = JsonParser.parseString(optionsJson).getAsJsonObject();
        Vec3 from = entity.getEyePosition();
        Vec3 direction = entity.getViewVector(1);
        Vec3 to = from.add(direction.scale(distance(options)));

        BlockFilter filter = BlockFilter.parse(options.getAsJsonObject("blockFilter"));
        BlockHitResult hit = cast(entity, from, to, direction, shapeOf(options), fluidOf(options), filter);
        if (hit == null) return null;

        BlockPos position = hit.getBlockPos();
        Vec3 point = hit.getLocation();
        JsonObject found = new JsonObject();
        found.addProperty("x", position.getX());
        found.addProperty("y", position.getY());
        found.addProperty("z", position.getZ());
        found.addProperty("face", hit.getDirection().ordinal());
        JsonArray face = new JsonArray();
        face.add(point.x - position.getX());
        face.add(point.y - position.getY());
        face.add(point.z - position.getZ());
        found.add("faceLocation", face);
        return found.toString();
    }

    /**
     * Clips against the world, restarting past each block the filter rejects.
     *
     * The restart point is where the ray leaves the rejected block rather than where it entered,
     * because a start inside a block hits that same block again.
     */
    private static BlockHitResult cast(Entity entity, Vec3 from, Vec3 to, Vec3 direction,
        ClipContext.Block shape, ClipContext.Fluid fluid, BlockFilter filter) {
        Vec3 start = from;
        for (int step = 0; step < FILTER_STEPS; step++) {
            BlockHitResult hit = entity.level().clip(new ClipContext(start, to, shape, fluid, entity));
            if (hit.getType() != HitResult.Type.BLOCK) return null;
            if (filter.keeps(entity.level().getBlockState(hit.getBlockPos()))) return hit;

            Vec3 exit = new AABB(hit.getBlockPos()).clip(to, start).orElse(hit.getLocation());
            start = exit.add(direction.scale(0.001));
            if (start.distanceToSqr(from) >= from.distanceToSqr(to)) return null;
        }
        return null;
    }

    /**
     * Every entity the line of sight crosses, nearest first, as `{ id, distance }` each.
     *
     * The ray stops at the first block unless the pack says to ignore them, so an entity behind a
     * wall is not reported. Which entities count is decided on the guest side, where the query the
     * pack wrote is already understood.
     */
    @HostAccess.Export
    public String entitiesFromViewDirection(String id, String optionsJson) {
        Entity entity = entity(id);
        JsonArray hits = new JsonArray();
        if (entity == null) return hits.toString();

        JsonObject options = JsonParser.parseString(optionsJson).getAsJsonObject();
        Vec3 from = entity.getEyePosition();
        Vec3 to = reach(entity, from, entity.getViewVector(1), options);

        entity.level().getEntities(entity, new AABB(from, to).inflate(1), other -> true).stream()
            .map(other -> measure(other, from, to))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingDouble(hit -> hit.get("distance").getAsDouble()))
            .forEach(hits::add);
        return hits.toString();
    }

    /** How far the ray may travel: to its limit, or to the first block standing in the way. */
    private static Vec3 reach(Entity entity, Vec3 from, Vec3 direction, JsonObject options) {
        Vec3 limit = from.add(direction.scale(distance(options)));
        if (flag(options, "ignoreBlockCollision")) return limit;

        BlockHitResult blocked = entity.level().clip(
            new ClipContext(from, limit, shapeOf(options), fluidOf(options), entity));
        return blocked.getType() == HitResult.Type.BLOCK ? blocked.getLocation() : limit;
    }

    private static JsonObject measure(Entity candidate, Vec3 from, Vec3 to) {
        Vec3 point = candidate.getBoundingBox().clip(from, to).orElse(null);
        if (point == null) return null;

        JsonObject hit = new JsonObject();
        hit.addProperty("id", candidate.getStringUUID());
        hit.addProperty("distance", from.distanceTo(point));
        return hit;
    }

    @HostAccess.Export
    public void lookAt(String id, double x, double y, double z) {
        Entity entity = entity(id);
        if (entity != null) entity.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(x, y, z));
    }

    /**
     * Puts an entity property back to the value the pack declared for it.
     *
     * A property the pack never declared has no default to go back to, so that is reported rather
     * than answered with a guess.
     */
    @HostAccess.Export
    public Object resetProperty(String id, String name) {
        Entity entity = entity(id);
        if (entity == null) return null;

        ServerEntityDefinition definition = AddonEntities.definitionOf(entity.getType());
        ServerEntityDefinition.Property property = definition == null ? null : definition.properties().get(name);
        if (property == null) return unsupported("Entity.resetProperty(" + name + ")");

        state.setProperty(entity.getUUID(), name, property.initial());
        EntityPropertySync.broadcast(entity, state);
        return property.initial();
    }

    /** Measured the way the world's own count is, so the two numbers a pack compares mean the same. */
    @HostAccess.Export
    public int dynamicPropertyBytes(String id) {
        Entity entity = entity(id);
        if (entity == null) return 0;

        UUID uuid = entity.getUUID();
        return state.dynamicPropertyNames(uuid).stream()
            .mapToInt(name -> name.length() + String.valueOf(state.dynamicProperty(uuid, name)).length())
            .sum();
    }

    /**
     * The scoreboard identity an entity answers to.
     *
     * Java has no number of its own for a participant, so the entity's network id stands in: a pack
     * uses it to tell two identities apart rather than to look one up.
     */
    @HostAccess.Export
    public String scoreboardIdentity(String id) {
        Entity entity = entity(id);
        if (entity == null) return null;

        JsonObject identity = new JsonObject();
        identity.addProperty("displayName", entity.getScoreboardName());
        identity.addProperty("id", entity.getId());
        identity.addProperty("type", entity instanceof ServerPlayer ? "Player" : "Entity");
        return identity.toString();
    }

    /** Vines and flowers stop a ray only when the pack asks for them; Java draws that line by shape. */
    private static ClipContext.Block shapeOf(JsonObject options) {
        return flag(options, "includePassableBlocks") ? ClipContext.Block.OUTLINE : ClipContext.Block.COLLIDER;
    }

    private static ClipContext.Fluid fluidOf(JsonObject options) {
        return flag(options, "includeLiquidBlocks") ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE;
    }

    private static double distance(JsonObject options) {
        return options.has("maxDistance") && options.get("maxDistance").isJsonPrimitive()
            ? options.get("maxDistance").getAsDouble() : 16;
    }

    private static boolean flag(JsonObject options, String key) {
        return options.has(key) && options.get(key).isJsonPrimitive() && options.get(key).getAsBoolean();
    }

    private static JsonArray triple(BlockPos position) {
        JsonArray at = new JsonArray();
        at.add(position.getX());
        at.add(position.getY());
        at.add(position.getZ());
        return at;
    }

    /**
     * `BlockFilter`, as the four lists a pack can fill in.
     *
     * Permutations are left out: a filter naming one is asking about block states, which this takes
     * no view on.
     */
    private record BlockFilter(Set<String> includeTypes, Set<String> excludeTypes,
        Set<String> includeTags, Set<String> excludeTags) {
        static BlockFilter parse(JsonObject filter) {
            return filter == null ? new BlockFilter(Set.of(), Set.of(), Set.of(), Set.of())
                : new BlockFilter(types(filter, "includeTypes"), types(filter, "excludeTypes"),
                    names(filter, "includeTags"), names(filter, "excludeTags"));
        }

        boolean keeps(BlockState block) {
            String type = AddonBlocks.identifierOf(block);
            if (!includeTypes.isEmpty() && !includeTypes.contains(type)) return false;
            if (excludeTypes.contains(type)) return false;
            if (includeTags.isEmpty() && excludeTags.isEmpty()) return true;

            Set<String> tags = block.getBlock().builtInRegistryHolder().tags()
                .map(tag -> tag.location().toString())
                .collect(Collectors.toSet());
            if (!includeTags.isEmpty() && Collections.disjoint(includeTags, tags)) return false;
            return Collections.disjoint(excludeTags, tags);
        }

        /** A type or tag written without a namespace is a vanilla one, the way Bedrock writes it. */
        /** Block types under the id a block reports itself by, whichever edition's spelling named them. */
        private static Set<String> types(JsonObject filter, String key) {
            return names(filter, key).stream().map(AddonBlocks::identifierOf).collect(Collectors.toSet());
        }

        private static Set<String> names(JsonObject filter, String key) {
            if (!filter.has(key) || !filter.get(key).isJsonArray()) return Set.of();
            Set<String> names = new LinkedHashSet<>();
            for (JsonElement element : filter.getAsJsonArray(key)) {
                String name = element.getAsString();
                names.add(name.indexOf(':') < 0 ? "minecraft:" + name : name);
            }
            return names;
        }
    }

    /**
     * One component of the pack's own definition for this entity, as the pack wrote it.
     *
     * A script asks about its own pack's data as often as it asks about the world: what kind of
     * container an entity carries, how far it follows, what it breeds with. All of that is in the
     * behaviour pack rather than in anything Java holds, so it is answered from there.
     */
    @HostAccess.Export
    public String packComponent(String id, String component) {
        Entity found = entity(id);
        if (found == null) return null;

        ServerEntityDefinition definition = AddonEntities.definitionOf(found.getType());
        JsonObject body = definition == null ? null : definition.components().get(component);
        return body == null ? null : body.toString();
    }

    /** Every component the pack declared for this entity, in the order the pack wrote them. */
    @HostAccess.Export
    public String[] packComponents(String id) {
        Entity found = entity(id);
        ServerEntityDefinition definition = found == null ? null
            : AddonEntities.definitionOf(found.getType());
        return definition == null ? new String[0]
            : definition.components().keySet().toArray(String[]::new);
    }

    /** Who is holding this entity on a lead, or null when nobody is. */
    @HostAccess.Export
    public String leashHolder(String id) {
        Entity holder = Platform.leashHolder(entity(id));
        return holder == null ? null : holder.getStringUUID();
    }

    /** False when this entity is not the kind that can be led, which is what Bedrock reports. */
    @HostAccess.Export
    public boolean leashTo(String id, String holderId) {
        Entity entity = entity(id);
        Entity holder = entity(holderId);
        return entity != null && holder != null && Platform.leashTo(entity, holder);
    }

    @HostAccess.Export
    public boolean unleash(String id) {
        Entity entity = entity(id);
        return entity != null && Platform.unleash(entity);
    }

    // Attributes

    /**
     * The four numbers behind one of Bedrock's attribute components, as JSON.
     *
     * Bedrock gives every such number a component of its own. Java keeps most of them in the
     * attribute map, health on the entity and hunger in a food bar beside it, so the component name
     * says where to read. Null when this game holds no number for the name, which the script side
     * reports rather than answering with one that is made up.
     */
    @HostAccess.Export
    public String attribute(String id, String component) {
        if (!(entity(id) instanceof LivingEntity living)) return null;

        FoodData food = living instanceof Player player ? player.getFoodData() : null;
        return switch (component) {
            case "minecraft:health" ->
                attributeJson(living.getHealth(), living.getMaxHealth(), 0, living.getMaxHealth());
            case "minecraft:player.hunger" -> food == null ? null
                : attributeJson(food.getFoodLevel(), FULL_FOOD_BAR, 0, FULL_FOOD_BAR);
            case "minecraft:player.saturation" -> food == null ? null
                : attributeJson(food.getSaturationLevel(), FULL_FOOD_BAR, 0, FULL_FOOD_BAR);
            default -> {
                AttributeInstance instance = attributeOf(living, component);
                yield instance == null ? null : attributeJson(instance);
            }
        };
    }

    /** False when the component names nothing this game holds, so the script side can report it. */
    @HostAccess.Export
    public boolean setAttribute(String id, String component, double value) {
        if (!(entity(id) instanceof LivingEntity living)) return false;

        FoodData food = living instanceof Player player ? player.getFoodData() : null;
        switch (component) {
            case "minecraft:health" -> living.setHealth((float) value);
            case "minecraft:player.hunger" -> {
                if (food == null) return false;
                food.setFoodLevel((int) value);
            }
            case "minecraft:player.saturation" -> {
                if (food == null) return false;
                food.setSaturation((float) value);
            }
            default -> {
                AttributeInstance instance = attributeOf(living, component);
                if (instance == null) return false;
                instance.setBaseValue(value);
            }
        }
        return true;
    }

    /**
     * The attribute a Bedrock component name reads.
     *
     * Named through {@link Attributes} rather than looked up by id, because the constants carry the
     * type this version's {@code getAttribute} takes and a lookup by id does not.
     */
    private static AttributeInstance attributeOf(LivingEntity living, String component) {
        return switch (component) {
            case "minecraft:movement" -> living.getAttribute(Attributes.MOVEMENT_SPEED);
            case "minecraft:flying_speed" -> living.getAttribute(Attributes.FLYING_SPEED);
            default -> null;
        };
    }

    /** An attribute with no declared range is unbounded, which is what Bedrock reports for one. */
    private static String attributeJson(AttributeInstance instance) {
        Attribute attribute = definitionOf(instance);
        RangedAttribute ranged = attribute instanceof RangedAttribute found ? found : null;
        return attributeJson(instance.getValue(), attribute.getDefaultValue(),
            ranged == null ? -Double.MAX_VALUE : ranged.getMinValue(),
            ranged == null ? Double.MAX_VALUE : ranged.getMaxValue());
    }

    /** An attribute map holds the attribute itself before 1.21 and a registry holder from then on. */
    private static Attribute definitionOf(AttributeInstance instance) {
        //? if >=1.21 {
        return instance.getAttribute().value();
        //?} else {
        /*return instance.getAttribute();
        *///?}
    }

    private static String attributeJson(double current, double fallback, double min, double max) {
        JsonObject values = new JsonObject();
        values.addProperty("current", current);
        values.addProperty("default", fallback);
        values.addProperty("min", min);
        values.addProperty("max", max);
        return values.toString();
    }

    @HostAccess.Export
    public double armour(String id) {
        return entity(id) instanceof LivingEntity living ? living.getArmorValue() : 0;
    }

    @HostAccess.Export
    public double armourToughness(String id) {
        return entity(id) instanceof LivingEntity living
            ? living.getAttributeValue(Attributes.ARMOR_TOUGHNESS) : 0;
    }

    @HostAccess.Export
    public int fireTicks(String id) {
        Entity found = entity(id);
        return found == null ? 0 : found.getRemainingFireTicks();
    }

    // Taming

    /** Who a tamed animal belongs to, or null when nothing tamed it or it cannot be tamed at all. */
    @HostAccess.Export
    public String owner(String id) {
        if (!(entity(id) instanceof TamableAnimal animal) || !animal.isTame()) return null;

        UUID tamer = Platform.ownerOf(animal);
        return tamer == null ? null : tamer.toString();
    }

    /** False when this is not the kind of animal a player tames, which is what Bedrock reports. */
    @HostAccess.Export
    public boolean tame(String id, String playerId) {
        if (!(entity(id) instanceof TamableAnimal animal)) return false;
        if (!(entity(playerId) instanceof ServerPlayer player)) return false;

        animal.tame(player);
        return true;
    }
}
