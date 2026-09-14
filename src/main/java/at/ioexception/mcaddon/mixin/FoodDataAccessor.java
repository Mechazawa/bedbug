package at.ioexception.mcaddon.mixin;

import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The exhaustion a player has run up, which only `addExhaustion` can otherwise move.
 *
 * A pack stating a starting exhaustion or a ceiling for it needs the value written outright, and 26
 * dropped the getter and the setter the older versions had. The field is in all four, so this is how
 * all four are asked the same way.
 */
@Mixin(FoodData.class)
public interface FoodDataAccessor {
    @Accessor("exhaustionLevel")
    float mcaddon$exhaustion();

    @Accessor("exhaustionLevel")
    void mcaddon$setExhaustion(float level);
}
