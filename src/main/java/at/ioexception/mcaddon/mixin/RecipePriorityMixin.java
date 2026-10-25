package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.RecipePriorities;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.stream.Stream;

@Mixin(RecipeMap.class)
public abstract class RecipePriorityMixin {
    @ModifyReturnValue(method = "getRecipesFor", at = @At("RETURN"))
    private <T extends Recipe<?>> Stream<RecipeHolder<T>> mcaddon$byPriority(Stream<RecipeHolder<T>> matches) {
        return RecipePriorities.sorted(matches);
    }
}
