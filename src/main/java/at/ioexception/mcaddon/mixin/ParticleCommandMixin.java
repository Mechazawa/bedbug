package at.ioexception.mcaddon.mixin;

import at.ioexception.mcaddon.registry.AddonParticles;
import at.ioexception.mcaddon.server.Particles;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.commands.ParticleCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Starts a pack's effect when Java's own `/particle` is given its name.
 *
 * A pack's effects are registered as particle types, so `particle tp:burst ~ ~ ~` typed unquoted is
 * read by Java's command as readily as by this mod's, and Java's is asked first. What an effect
 * names is an emitter rather than one particle, so the emitter is what starts.
 */
@Mixin(ParticleCommand.class)
public abstract class ParticleCommandMixin {
    @Inject(method = "sendParticles", at = @At("HEAD"), cancellable = true)
    private static void mcaddon$startPackEffect(CommandSourceStack source, ParticleOptions options, Vec3 position,
        Vec3 delta, float speed, int count, boolean force, Collection<ServerPlayer> viewers,
        CallbackInfoReturnable<Integer> answer) {
        String effect = BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()).toString();
        if (AddonParticles.definitionOf(effect) == null) return;

        Particles.start(source.getLevel(), effect, position.x, position.y, position.z);
        answer.setReturnValue(1);
    }
}
