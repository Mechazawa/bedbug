package at.ioexception.mcaddon.client.render;

import at.ioexception.mcaddon.bedrock.behaviour.BedrockUnits;
import at.ioexception.mcaddon.platform.Platform;
import at.ioexception.mcaddon.server.BedrockRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.RandomSource;

/**
 * The red dust a border block sends up and down its column, which Bedrock calls
 * `rising_border_dust_particle` and `falling_border_dust_particle`.
 *
 * Bedrock starts each at 0.2 to 0.6 blocks a second along the column, with a sideways drift of at
 * most 0.016 blocks a second.
 */
public final class BorderDust {
    private static final DustParticleOptions RED = Platform.dust(0xC81414, 1.0F);

    private BorderDust() {
    }

    public static void emit(BlockPos pos, RandomSource random) {
        if (!PlayerRules.on(BedrockRules.SHOW_BORDER_EFFECT, true)) return;

        launch(pos.getX() + random.nextDouble(), pos.getY() + 1.0, pos.getZ() + random.nextDouble(), 1, random);
        launch(pos.getX() + random.nextDouble(), pos.getY(), pos.getZ() + random.nextDouble(), -1, random);
    }

    private static void launch(double x, double y, double z, int upward, RandomSource random) {
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(RED, x, y, z, 0, 0, 0);
        if (particle == null) return;
        particle.setParticleSpeed(
            BedrockUnits.blocksPerTick(random.nextDouble() * 0.032 - 0.016),
            upward * BedrockUnits.blocksPerTick(0.2 + random.nextDouble() * 0.4),
            BedrockUnits.blocksPerTick(random.nextDouble() * 0.032 - 0.016));
    }
}
