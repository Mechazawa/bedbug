package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.tags.PoiTypeTags;

import java.util.EnumSet;
import java.util.function.Predicate;

/**
 * Walks to the nearest place of the kind the pack named.
 *
 * `minecraft:behavior.move_to_poi` names a point of interest type, which Java keeps an index of per
 * chunk: the beds, the workstations and the bells a village is made of. A type the game does not
 * have is one nothing will ever be found for, so the goal never starts rather than walking nowhere.
 */
public final class MoveToPoiGoal extends Goal {
    /** How far a Bedrock entity looks for a place, which is a village's own reach. */
    static final int SEARCH_RADIUS = 48;

    /** How close counts as arrived, squared, so the walk ends instead of pushing into the block. */
    private static final double ARRIVED = 4;

    private final AddonEntity mob;
    private final double speed;
    private final Predicate<Holder<PoiType>> wanted;
    private BlockPos found;

    public MoveToPoiGoal(AddonEntity mob, JsonObject component) {
        this.mob = mob;
        this.speed = Goals.number(component, "speed_multiplier", 1);
        this.wanted = kind(component);
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (wanted == null || !(mob.level() instanceof ServerLevel level)) return false;

        PoiManager places = level.getPoiManager();
        found = places.findClosest(wanted, mob.blockPosition(), SEARCH_RADIUS,
            PoiManager.Occupancy.ANY).orElse(null);
        return found != null;
    }

    @Override
    public boolean canContinueToUse() {
        return found != null && mob.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(found)) > ARRIVED
            && !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(found.getX() + 0.5, found.getY(), found.getZ() + 0.5, speed);
    }

    /**
     * The point of interest type a pack named, under either spelling it writes one with.
     *
     * `poi_type` is a string on most packs and an object naming one on the rest. Bedrock's own three
     * kinds are `bed`, `meeting_area` and `jobsite`, which Java calls `home`, `meeting` and any
     * acquirable job site; any other name is a Java type, with or without `minecraft:`.
     */
    static Predicate<Holder<PoiType>> kind(JsonObject component) {
        JsonElement value = component == null ? null : component.get("poi_type");
        String text = value == null ? null
            : value.isJsonPrimitive() ? value.getAsString()
            : value.isJsonObject() ? Json.text(value.getAsJsonObject(), "poi_type", null) : null;
        if (text == null) return null;

        return switch (text) {
            case "bed" -> type -> type.is(PoiTypes.HOME);
            case "meeting_area" -> type -> type.is(PoiTypes.MEETING);
            case "jobsite" -> type -> type.is(PoiTypeTags.ACQUIRABLE_JOB_SITE);
            default -> {
                Identifier id = Identifier.tryParse(text.contains(":") ? text : "minecraft:" + text);
                ResourceKey<PoiType> key = id == null
                    ? null : ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, id);
                yield key == null ? null : type -> type.is(key);
            }
        };
    }
}
