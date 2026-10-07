package at.ioexception.mcaddon.mixin;

import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.Predicate;

/**
 * Adds an option to Java's entity selector under a name of this mod's choosing.
 *
 * Fabric's own registry for selector options builds the name out of a namespace and a path, so it
 * cannot register one spelled the way a Bedrock pack writes it.
 */
@Mixin(EntitySelectorOptions.class)
public interface SelectorOptionsInvoker {
    @Invoker("register")
    static void mcaddon$register(String name, EntitySelectorOptions.Modifier modifier,
        Predicate<EntitySelectorParser> available, Component description) {
        throw new AssertionError("Mixin did not apply to EntitySelectorOptions");
    }
}
