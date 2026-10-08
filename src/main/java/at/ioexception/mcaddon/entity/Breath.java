package at.ioexception.mcaddon.entity;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;

/**
 * What `minecraft:breathable` says an entity can breathe in, and how long it holds its breath.
 *
 * Bedrock states the supply and the time to fill it again in seconds, and Java counts air in ticks:
 * a supply of fifteen seconds is the three hundred Java gives every mob, and Mojang's dolphin holds
 * its breath for the two hundred and forty seconds Java's dolphin counts as 4800. An entity carrying
 * no such component never runs out, which is the answer Bedrock gives its iron golem and its vex.
 */
public final class Breath {
    /** Where the entity's eyes are, which is what decides whether it is breathing. */
    enum Medium { AIR, WATER, LAVA, SOLID }

    private final int supply;
    private final int inhale;
    private final boolean air;
    private final boolean water;
    private final boolean lava;
    private final boolean solids;

    private Breath(JsonObject component) {
        this.supply = Math.max(0, Goals.ticks(component, "total_supply", 15));
        this.inhale = Math.max(0, Goals.ticks(component, "inhale_time", 0));
        this.air = Goals.flag(component, "breathes_air", true);
        this.water = Goals.flag(component, "breathes_water", false);
        this.lava = Goals.flag(component, "breathes_lava", true);
        this.solids = Goals.flag(component, "breathes_solids", false);
    }

    /** Null for an entity whose pack gave it nothing to breathe, which therefore cannot drown. */
    static Breath tryFrom(JsonObject component) {
        return component == null ? null : new Breath(component);
    }

    /** How many ticks of air the entity holds when it is full. */
    int supply() {
        return supply;
    }

    /**
     * Whether the entity can breathe where its eyes are.
     *
     * A bubble column counts as air, because Java refills a mob's breath inside one and so does
     * Bedrock.
     */
    boolean breathesAround(LivingEntity entity) {
        return switch (around(entity)) {
            case AIR -> air;
            case WATER -> water;
            case LAVA -> lava;
            case SOLID -> solids;
        };
    }

    /** Whether Java's own drowning is the one counting down, which it is for water alone. */
    static boolean underwater(LivingEntity entity) {
        return around(entity) == Medium.WATER;
    }

    private static Medium around(LivingEntity entity) {
        BlockPos eyes = BlockPos.containing(entity.getX(), entity.getEyeY(), entity.getZ());
        if (entity.isEyeInFluid(FluidTags.WATER)) {
            return entity.level().getBlockState(eyes).is(Blocks.BUBBLE_COLUMN) ? Medium.AIR : Medium.WATER;
        }
        if (entity.isEyeInFluid(FluidTags.LAVA)) return Medium.LAVA;

        return entity.isInWall() ? Medium.SOLID : Medium.AIR;
    }

    /**
     * The air one tick of breathing leaves the entity with.
     *
     * `inhale_time` is how long a full supply takes to come back, and Bedrock's default of nought
     * fills it in one breath; a mob in Java gains four a tick whatever it is, which is the three and
     * three quarter seconds Mojang writes on its own player.
     */
    int inhale(int current) {
        if (inhale <= 0) return supply;

        return Math.min(supply, current + Math.max(1, (int) Math.ceil((double) supply / inhale)));
    }
}
