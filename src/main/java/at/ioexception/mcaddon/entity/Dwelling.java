package at.ioexception.mcaddon.entity;

import at.ioexception.mcaddon.state.PropertyVector;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

import java.util.List;
import java.util.function.Predicate;

/**
 * `minecraft:dweller`: the points of interest an inhabitant claims, in Java's own point of interest
 * manager, the way Java's villager claims a home, a meeting point and a job site.
 *
 * An entity whose `dweller_role` is `inhabitant` and which `can_find_poi` takes a ticket on the
 * nearest free bed, bell and job site within Java's villager search range, every
 * `update_interval_base` plus up to `update_interval_variant` ticks, and holds each until it dies.
 * A `preferred_profession` narrows the job site to that profession's. Java's villagers and other
 * dwellers then pass over what it holds.
 */
final class Dwelling {
    /** How far Java's villager looks for a point of interest to claim, as `AcquirePoi` does. */
    private static final int SEARCH_RADIUS = 48;
    /** Every vanilla dweller checks every 60 ticks plus up to 40, and Mojang states no default. */
    private static final int BASE = 60;
    private static final int VARIANT = 40;
    private static final List<String> KEYS = List.of("dweller_home", "dweller_meeting", "dweller_job_site");

    private final boolean findsPoi;
    private final boolean inhabitant;
    private final int base;
    private final int variant;
    private final Predicate<Holder<PoiType>> jobSite;

    private Dwelling(JsonObject component) {
        this.findsPoi = Goals.flag(component, "can_find_poi", false);
        String role = Goals.text(component, "dweller_role");
        this.inhabitant = "inhabitant".equals(role == null ? Goals.text(component, "dwelling_role") : role);
        this.base = (int) Goals.number(component, "update_interval_base", BASE);
        this.variant = (int) Goals.number(component, "update_interval_variant", VARIANT);
        this.jobSite = profession(Goals.text(component, "preferred_profession"));
    }

    static Dwelling tryFrom(JsonObject component) {
        return component == null ? null : new Dwelling(component);
    }

    /** Ticks until the next look round the dwelling. */
    int interval(RandomSource random) {
        return Math.max(1, base) + random.nextInt(Math.max(0, variant) + 1);
    }

    void claim(AddonEntity mob) {
        if (!findsPoi || !inhabitant || !(mob.level() instanceof ServerLevel level)) return;

        claim(mob, level, KEYS.get(0), type -> type.is(PoiTypes.HOME));
        claim(mob, level, KEYS.get(1), type -> type.is(PoiTypes.MEETING));
        claim(mob, level, KEYS.get(2), jobSite);
    }

    private static void claim(AddonEntity mob, ServerLevel level, String key, Predicate<Holder<PoiType>> kind) {
        BlockPos held = claimed(mob, key);
        if (held != null && level.getPoiManager().exists(held, kind)) return;

        level.getPoiManager().findClosest(kind, mob.blockPosition(), SEARCH_RADIUS, PoiManager.Occupancy.HAS_SPACE)
            .flatMap(nearest -> level.getPoiManager().take(kind, (type, at) -> at.equals(nearest), nearest, 1))
            .ifPresent(at -> {
                mob.setValue(key, new PropertyVector(at.getX(), at.getY(), at.getZ()));
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getEyeY() + 0.5, mob.getZ(),
                    5, 0.3, 0.3, 0.3, 0);
            });
    }

    /** The bed the entity claimed, or null. */
    static BlockPos home(AddonEntity mob) {
        return claimed(mob, KEYS.get(0));
    }

    /** The job site the entity claimed, or null. */
    static BlockPos jobSite(AddonEntity mob) {
        return claimed(mob, KEYS.get(2));
    }

    /** Gives back every ticket the entity holds, as Java's villager does when it dies. */
    static void release(AddonEntity mob) {
        if (!(mob.level() instanceof ServerLevel level)) return;

        KEYS.stream().map(key -> claimed(mob, key)).filter(java.util.Objects::nonNull)
            .forEach(at -> level.getPoiManager().release(at));
    }

    private static BlockPos claimed(AddonEntity mob, String key) {
        return mob.value(key) instanceof PropertyVector at ? BlockPos.containing(at.x(), at.y(), at.z()) : null;
    }

    /**
     * The job sites a Bedrock profession name takes, through Java's profession of that name. Four
     * names are written apart in Bedrock; a name Java has no profession for takes any job site.
     */
    private static Predicate<Holder<PoiType>> profession(String bedrock) {
        Predicate<Holder<PoiType>> any = type -> type.is(PoiTypeTags.ACQUIRABLE_JOB_SITE);
        if (bedrock == null) return any;

        String java = bedrock.equals("stone_mason") ? "mason" : bedrock.replace("_", "");
        return BuiltInRegistries.VILLAGER_PROFESSION.getOptional(Identifier.withDefaultNamespace(java))
            .map(found -> found.acquirableJobSite())
            .orElse(any);
    }
}
